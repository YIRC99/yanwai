package com.dogheadstrategist.chat.core

import android.content.Context
import android.os.Bundle
import com.dogheadstrategist.chat.reply.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** All persistence goes through the existing UID-restricted provider, never host storage. */
object ReplyIdentityBridge {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "yanwai-reply-identity") }
    private val backgrounds = ContactBackgroundCache()
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<BackgroundScope, ReplyContactKey>>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<Pair<BackgroundScope, ReplyContactKey>, Long>()
    private class BackgroundScope(val generation: String, val revision: Long) {
        val token get() = "$generation:$revision"
        fun current() = ModulePrefs.settings()?.let { it.generation == generation && it.rolesRevision == revision } == true
        override fun equals(other: Any?) = other is BackgroundScope && token == other.token
        override fun hashCode() = token.hashCode()
    }
    private fun generation(): BackgroundScope = requireNotNull(ModulePrefs.settings()) { "设置尚未连接" }
        .let { BackgroundScope(it.generation, it.rolesRevision) }.also { backgrounds.selectGeneration(it.token) }
    private fun accept(generation: BackgroundScope, key: ReplyContactKey, value: ContactBackground): ContactBackground {
        check(generation.current() && backgrounds.put(generation.token, key, value)) { "角色或设置已变化，请重新打开" }
        return value
    }
    private fun readBackground(context: Context, key: ReplyContactKey, generation: BackgroundScope): ContactBackground {
        val result = SettingsTransport.call(context, "contact_background_get", key.value,
            Bundle().apply { putString(SettingsProvider.KEY_GENERATION, generation.generation) })
        return accept(generation, key, ContactBackground.decode(requireNotNull(result?.getString("payload"))))
    }
    fun background(context: Context, key: ReplyContactKey): ContactBackground? {
        val generation = runCatching { generation() }.getOrNull() ?: return null
        backgrounds.get(generation.token, key)?.let { return it }
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
    /**
     * 读这个人最新的画像，用来补进生成请求。
     *
     * 画像存在 app 私有目录，微信进程读不到，只能借这一跳。传 talker 而不是
     * contact_key，是因为画像那边本来就按 talker 存。
     */
    suspend fun loadProfile(context: Context, talker: String): String = suspendCancellableCoroutine { c ->
        worker.execute {
            val result = runCatching {
                SettingsTransport.call(context, "reply_profile_for_talker", talker, null)
                    ?.getString("text").orEmpty()
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun saveBackground(context: Context, owner: ReplyIdentityOwner, text: String,
        verify: () -> String?): ContactBackground = suspendCancellableCoroutine { c ->
        val generation = generation()
        worker.execute {
            val result = runCatching {
                check(owner.acceptsAccount(verify())) { "账号已变化，请重新打开" }
                check(generation.current()) { "设置已重置，请重新打开" }
                val key = requireNotNull(owner.key)
                val response = SettingsTransport.call(context, "contact_background_put", key.value,
                    Bundle().apply {
                        putString("text", text)
                        putString("talker", owner.talker)
                        putString(SettingsProvider.KEY_GENERATION, generation.generation)
                        putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, generation.revision)
                    })
                ContactBackground.decode(requireNotNull(response?.getString("payload"))).also {
                    accept(generation, key, it)
                }
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    /**
     * 删掉这个联系人的角色卡：身份、背景、画像一起清。
     *
     * 缓存同步置空，否则清完当场再生成，读到的还是清除前那份背景。
     */
    suspend fun clearRole(context: Context, owner: ReplyIdentityOwner, verify: () -> String?): Unit =
        suspendCancellableCoroutine { c ->
            val captured = generation()
            worker.execute {
                val result = runCatching {
                    check(owner.acceptsAccount(verify()) && captured.current()) { "账号或角色已变化，请重新打开" }
                    val key = requireNotNull(owner.key)
                    SettingsTransport.call(context, "reply_role_clear", key.value,
                        Bundle().apply {
                            putString(SettingsProvider.KEY_GENERATION, captured.generation)
                            putString("talker", owner.talker)
                        })
                    accept(captured, key, ContactBackground())
                    Unit
                }
                if (c.isActive) c.resumeWith(result)
            }
        }

    suspend fun saveRole(context: Context, owner: ReplyIdentityOwner, identity: ReplyIdentitySetting, text: String,
        verify: () -> String?): AppliedReplyRole = suspendCancellableCoroutine { c ->
        val captured = generation()
        worker.execute {
            val result = runCatching {
                check(owner.acceptsAccount(verify()) && captured.current()) { "账号或角色已变化，请重新打开" }
                val key = requireNotNull(owner.key)
                val response = requireNotNull(SettingsTransport.call(context, "reply_role_put", key.value,
                    Bundle().apply {
                        putString(SettingsProvider.KEY_GENERATION, captured.generation)
                        putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, captured.revision)
                        putString("identity", identity.encode()); putString("text", text)
                        putString("talker", owner.talker)
                    }))
                // 角色库取消后没有目录版本可比对，保存本身就改写了设置版本，
                // 所以这里只校验 generation（设置没被重置）并采用最新内存快照。
                ModulePrefs.reload(force = true)
                val updated = generation()
                check(updated.generation == captured.generation) { "设置已变化，请重新打开" }
                val background = accept(updated, key, ContactBackground.decode(requireNotNull(response.getString("background"))))
                AppliedReplyRole(ReplyIdentitySetting.decode(requireNotNull(response.getString("identity"))), background)
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    private fun queue(context: Context): ReplyIdentityQueue {
        val captured = generation()
        fun extras() = Bundle().apply {
            putString(SettingsProvider.KEY_GENERATION, captured.generation)
            putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, captured.revision)
        }
        return ReplyIdentityQueue({ worker.execute(it) }, { key ->
            val result = SettingsTransport.call(context, "reply_identity_get", key.value, extras())
            check(captured.current()) { "角色已变化，请重新打开" }
            ReplyIdentitySetting.decode(requireNotNull(result?.getString("payload")))
        }, { key, value ->
            check(captured.current()) { "角色已变化，请重新打开" }
            val result = SettingsTransport.call(context, "reply_identity_put", key.value,
                extras().apply { putString("payload", value.encode()) })
            check(result?.getBoolean("saved") == true)
            readBackground(context, key, captured)
        })
    }
    suspend fun load(context: Context, key: ReplyContactKey): ReplyIdentitySetting = suspendCancellableCoroutine { continuation ->
        queue(context.applicationContext).load(key) { result ->
            if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    }
    fun save(context: Context, owner: ReplyIdentityOwner, value: ReplyIdentitySetting, verify: () -> String?, complete: (Boolean) -> Unit) {
        queue(context.applicationContext).save(owner, value, verify) { saved ->
            complete(saved)
        }
    }
}
