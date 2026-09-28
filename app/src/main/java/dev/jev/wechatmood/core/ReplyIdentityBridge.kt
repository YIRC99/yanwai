package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** All persistence goes through the existing UID-restricted provider, never host storage. */
object ReplyIdentityBridge {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "yanwai-reply-identity") }
    private val backgrounds = ContactBackgroundCache()
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, ReplyContactKey>>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<Pair<String, ReplyContactKey>, Long>()
    private fun generation(): String = requireNotNull(ModulePrefs.analysisSettings()?.generation) { "设置尚未连接" }
        .also(backgrounds::selectGeneration)
    private fun accept(generation: String, key: ReplyContactKey, value: ContactBackground): ContactBackground {
        check(ModulePrefs.analysisSettings()?.generation == generation && backgrounds.put(generation, key, value)) { "设置已重置，请重新打开" }
        return value
    }
    private fun readBackground(context: Context, key: ReplyContactKey, generation: String): ContactBackground {
        val result = context.contentResolver.call(SettingsProvider.URI, "contact_background_get", key.value,
            Bundle().apply { putString(SettingsProvider.KEY_GENERATION, generation) })
        return accept(generation, key, ContactBackground.decode(requireNotNull(result?.getString("payload"))))
    }
    fun background(context: Context, key: ReplyContactKey): ContactBackground? {
        val generation = runCatching { generation() }.getOrNull() ?: return null
        backgrounds.get(generation, key)?.let { return it }
        val scope = generation to key
        if (failedAt[scope]?.let { System.nanoTime() - it < 5_000_000_000L } == true) return null
        if (loading.add(scope)) worker.execute {
            try { readBackground(context.applicationContext, key, generation); failedAt.remove(scope) }
            catch (_: Exception) { failedAt[scope] = System.nanoTime() }
            finally { loading.remove(scope) }
        }
        return null
    }
    suspend fun loadBackground(context: Context, key: ReplyContactKey): ContactBackground = suspendCancellableCoroutine { c ->
        val generation = generation()
        worker.execute {
            val result = runCatching { readBackground(context.applicationContext, key, generation) }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun saveBackground(context: Context, owner: ReplyIdentityOwner, text: String,
        verify: () -> String?): ContactBackground = suspendCancellableCoroutine { c ->
        val generation = generation()
        worker.execute {
            val result = runCatching {
                check(owner.acceptsAccount(verify())) { "账号已变化，请重新打开" }
                check(ModulePrefs.analysisSettings()?.generation == generation) { "设置已重置，请重新打开" }
                val key = requireNotNull(owner.key)
                val response = context.contentResolver.call(SettingsProvider.URI, "contact_background_put", key.value,
                    Bundle().apply { putString("text", text); putString(SettingsProvider.KEY_GENERATION, generation) })
                ContactBackground.decode(requireNotNull(response?.getString("payload"))).also {
                    accept(generation, key, it)
                    ModulePrefs.backgroundChanged(owner.talker)
                }
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    private fun queue(context: Context) = ReplyIdentityQueue({ worker.execute(it) }, { key ->
        val result = context.contentResolver.call(SettingsProvider.URI, "reply_identity_get", key.value, null)
        ReplyIdentitySetting.decode(requireNotNull(result?.getString("payload")))
    }, { key, value ->
        val result = context.contentResolver.call(SettingsProvider.URI, "reply_identity_put", key.value,
            Bundle().apply { putString("payload", value.encode()) })
        check(result?.getBoolean("saved") == true)
    })
    suspend fun load(context: Context, key: ReplyContactKey): ReplyIdentitySetting = suspendCancellableCoroutine { continuation ->
        queue(context.applicationContext).load(key) { result ->
            if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    }
    fun save(context: Context, owner: ReplyIdentityOwner, value: ReplyIdentitySetting, verify: () -> String?, complete: (Boolean) -> Unit) {
        queue(context.applicationContext).save(owner, value, verify, complete)
    }
}
