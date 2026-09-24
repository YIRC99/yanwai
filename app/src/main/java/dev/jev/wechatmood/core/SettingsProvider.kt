package dev.jev.wechatmood.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import java.util.UUID

/** Settings bridge restricted to this app and WeChat, where the model requests run. */
class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean { context?.let(MoodLog::init); return true }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val caller = Binder.getCallingUid()
        val own = caller == Process.myUid()
        val wechat = ctx.packageManager.getPackagesForUid(caller)?.contains("com.tencent.mm") == true
        if (!own && !wechat) {
            MoodLog.w("PROVIDER_CALL_DENIED uid=$caller method=$method")
            throw SecurityException("Caller is not allowed: uid=$caller")
        }
        return when (method) {
            "config" -> snapshot(ctx)
            "report" -> {
                ctx.getSharedPreferences(RUNTIME_FILE, 0).edit()
                    .putString("status", arg.orEmpty().take(200))
                    .putLong("last_seen", System.currentTimeMillis()).apply {
                        extras?.getString("host_log")?.let {
                            putString("host_log", MoodLog.sanitize(it).takeLast(48 * 1024))
                            putLong("host_log_at", System.currentTimeMillis())
                        }
                    }.apply()
                Bundle()
            }
            else -> throw IllegalArgumentException("Unknown method")
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object {
        val URI: Uri = Uri.parse("content://dev.jev.wechatmood.settings")
        const val RUNTIME_FILE = "wechat_runtime"
        const val KEY_REVISION = "settings_revision"
        const val KEY_GENERATION = "settings_generation"

        @Synchronized fun snapshot(context: Context): Bundle {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
            val generation = prefs.getString(KEY_GENERATION, null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString(KEY_GENERATION, it).commit())
            }
            return Bundle().apply {
                putString(KEY_GENERATION, generation)
                putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L))
                putBoolean(ModulePrefs.KEY_EXPLORE, prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false))
                putString(ModulePrefs.KEY_API_BASE, prefs.getString(ModulePrefs.KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT))
                putString(ModulePrefs.KEY_API_KEY, prefs.getString(ModulePrefs.KEY_API_KEY, ""))
                putString(ModulePrefs.KEY_API_PROVIDER, prefs.getString(ModulePrefs.KEY_API_PROVIDER, null))
                putString(ModulePrefs.KEY_API_MODEL, prefs.getString(ModulePrefs.KEY_API_MODEL, ""))
            }
        }

        @Synchronized fun save(context: Context, edit: SharedPreferences.Editor.() -> Unit): Boolean {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
            val saved = prefs.edit().apply(edit)
                .putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L) + 1L).commit()
            MoodLog.i("SETTINGS_DISK_SAVE saved=$saved revision=${prefs.getLong(KEY_REVISION, 0L)}")
            if (saved) publish(context)
            return saved
        }

        fun publish(context: Context) {
            // A transport failure must not misreport a successful disk write as a failed save.
            runCatching { SettingsSync.publish(context, snapshot(context)) }
                .onFailure { MoodLog.e("SYNC_PUBLISH_FAILED 设置已落盘但同步失败", it) }
        }
    }
}
