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
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<BackgroundScope, ReplyContactKey>>()
    private val failedAt = java.util.concurrent.ConcurrentHashMap<Pair<BackgroundScope, ReplyContactKey>, Long>()
    private class BackgroundScope(val generation: String, val revision: Long) {
        val token get() = "$generation:$revision"
        fun current() = ModulePrefs.analysisSettings()?.let { it.generation == generation && it.rolesRevision == revision } == true
        override fun equals(other: Any?) = other is BackgroundScope && token == other.token
        override fun hashCode() = token.hashCode()
    }
    private fun generation(): BackgroundScope = requireNotNull(ModulePrefs.analysisSettings()) { "设置尚未连接" }
        .let { BackgroundScope(it.generation, it.rolesRevision) }.also { backgrounds.selectGeneration(it.token) }
    private fun accept(generation: BackgroundScope, key: ReplyContactKey, value: ContactBackground): ContactBackground {
        check(generation.current() && backgrounds.put(generation.token, key, value)) { "角色或设置已变化，请重新打开" }
        return value
    }
    private fun readBackground(context: Context, key: ReplyContactKey, generation: BackgroundScope): ContactBackground {
        val result = context.contentResolver.call(SettingsProvider.URI, "contact_background_get", key.value,
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
    suspend fun saveBackground(context: Context, owner: ReplyIdentityOwner, text: String,
        verify: () -> String?): ContactBackground = suspendCancellableCoroutine { c ->
        val generation = generation()
        worker.execute {
            val result = runCatching {
                check(owner.acceptsAccount(verify())) { "账号已变化，请重新打开" }
                check(generation.current()) { "设置已重置，请重新打开" }
                val key = requireNotNull(owner.key)
                val response = context.contentResolver.call(SettingsProvider.URI, "contact_background_put", key.value,
                    Bundle().apply { putString("text", text); putString(SettingsProvider.KEY_GENERATION, generation.generation); putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, generation.revision) })
                ContactBackground.decode(requireNotNull(response?.getString("payload"))).also {
                    accept(generation, key, it)
                    ModulePrefs.backgroundChanged(owner.talker)
                }
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun roles(context: Context): List<ReplyRole> = suspendCancellableCoroutine { c ->
        val captured = generation()
        worker.execute {
            val result = runCatching {
                val response = context.contentResolver.call(SettingsProvider.URI, "reply_role_list", null,
                    Bundle().apply { putString(SettingsProvider.KEY_GENERATION, captured.generation) })
                check(captured.current()) { "角色已变化，请重试" }
                requireNotNull(response?.getStringArrayList("roles")).map(ReplyRole::decode)
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
                val response = requireNotNull(context.contentResolver.call(SettingsProvider.URI, "reply_role_put", key.value,
                    Bundle().apply {
                        putString(SettingsProvider.KEY_GENERATION, captured.generation)
                        putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, captured.revision)
                        putString("identity", identity.encode()); putString("text", text)
                    }))
                val revision = response.getLong(ReplyIdentityProvider.KEY_ROLE_REVISION)
                // A role edit affects every contact explicitly using that role. Adopt its new catalog
                // revision before filling the cache, and reject a concurrent newer edit or reset.
                ModulePrefs.reload(force = true)
                val updated = generation()
                check(updated.generation == captured.generation && updated.revision == revision) { "角色或设置已变化，请重新打开" }
                val background = accept(updated, key, ContactBackground.decode(requireNotNull(response.getString("background"))))
                ModulePrefs.backgroundChanged(owner.talker)
                AppliedReplyRole(ReplyIdentitySetting.decode(requireNotNull(response.getString("identity"))), background, revision)
            }
            if (c.isActive) c.resumeWith(result)
        }
    }
    suspend fun applyRole(context: Context, owner: ReplyIdentityOwner, role: ReplyRole, verify: () -> String?): AppliedReplyRole =
        suspendCancellableCoroutine { c ->
            val captured = generation()
            worker.execute {
                val result = runCatching {
                    check(owner.acceptsAccount(verify()) && captured.current()) { "账号或角色已变化，请重新打开" }
                    val key = requireNotNull(owner.key)
                    val response = requireNotNull(context.contentResolver.call(SettingsProvider.URI, "reply_role_apply", key.value,
                        Bundle().apply {
                            putString(SettingsProvider.KEY_GENERATION, captured.generation)
                            putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, captured.revision)
                            putString("role_id", role.id); putString("role_revision", role.revision)
                        }))
                    val identity = ReplyIdentitySetting.decode(requireNotNull(response.getString("identity")))
                    val background = accept(captured, key, ContactBackground.decode(requireNotNull(response.getString("background"))))
                    ModulePrefs.backgroundChanged(owner.talker)
                    AppliedReplyRole(identity, background)
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
            val result = context.contentResolver.call(SettingsProvider.URI, "reply_identity_get", key.value, extras())
            check(captured.current()) { "角色已变化，请重新打开" }
            ReplyIdentitySetting.decode(requireNotNull(result?.getString("payload")))
        }, { key, value ->
            check(captured.current()) { "角色已变化，请重新打开" }
            val result = context.contentResolver.call(SettingsProvider.URI, "reply_identity_put", key.value,
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
            if (saved) ModulePrefs.backgroundChanged(owner.talker)
            complete(saved)
        }
    }
}
