package dev.jev.wechatmood.core

import android.content.Context
import java.security.MessageDigest

/**
 * 角色与联系人背景的本地回落。
 *
 * 这两样和设置一样都要经 Provider 读模块进程；微信冷启动时主进程常常拉不起来，
 * 结果就是「设置恢复了但角色和背景读不到」，分析和帮我回都卡住。
 * 这里把最近一次成功读到的内容用同一把 Keystore 密钥加密留在微信进程目录里。
 */
object ReplySnapshotCache {
    private const val PREFS = "yanwai_reply_snapshot"
    private const val KEY_ROLES = "roles"
    private const val MAX_ENTRIES = 400

    fun saveRoles(context: Context, payloads: List<String>) {
        runCatching {
            val sealed = SettingsSnapshotCache.sealText(payloads.joinToString("\u0001")) ?: return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ROLES, sealed).apply()
        }.onFailure { MoodLog.w("REPLY_SNAPSHOT_ROLES_FAILED ${it.javaClass.simpleName}") }
    }

    fun loadRoles(context: Context): List<String>? = runCatching {
        val sealed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROLES, null)
            ?: return null
        SettingsSnapshotCache.unsealText(sealed)?.split("\u0001")?.filter { it.isNotBlank() }
    }.getOrNull()

    fun save(context: Context, key: String, field: String, payload: String) {
        runCatching {
            val name = entryName(key, field)
            val sealed = SettingsSnapshotCache.sealText(payload) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val editor = prefs.edit().putString(name, sealed)
            if (prefs.all.size > MAX_ENTRIES) prefs.all.keys.filter { it.startsWith("e_") }.take(80)
                .forEach { editor.remove(it) }
            editor.apply()
        }.onFailure { MoodLog.w("REPLY_SNAPSHOT_SAVE_FAILED ${it.javaClass.simpleName}") }
    }

    fun load(context: Context, key: String, field: String): String? = runCatching {
        val sealed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(entryName(key, field), null) ?: return null
        SettingsSnapshotCache.unsealText(sealed)
    }.getOrNull()

    private fun entryName(key: String, field: String): String =
        "e_" + field + "_" + MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
