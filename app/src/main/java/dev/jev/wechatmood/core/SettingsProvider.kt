package dev.jev.wechatmood.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Settings bridge restricted to this app and WeChat, where the model requests run. */
class SettingsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val caller = Binder.getCallingUid()
        val own = caller == Process.myUid()
        val wechat = ctx.packageManager.getPackagesForUid(caller)?.contains("com.tencent.mm") == true
        if (!own && !wechat) throw SecurityException("Caller is not allowed")
        return when (method) {
            "set_switch" -> {
                require(arg == ModulePrefs.KEY_ENABLED || arg == ModulePrefs.KEY_SHOW_BADGE)
                require(extras?.containsKey("value") == true)
                check(ctx.getSharedPreferences(ModulePrefs.FILE_NAME, 0).edit()
                    .putBoolean(arg, extras.getBoolean("value")).commit())
                call("config", null, null)
            }
            "config" -> {
                val prefs = ctx.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
                Bundle().apply {
                    putBoolean(ModulePrefs.KEY_ENABLED, prefs.getBoolean(ModulePrefs.KEY_ENABLED, true))
                    putBoolean(ModulePrefs.KEY_SHOW_BADGE, prefs.getBoolean(ModulePrefs.KEY_SHOW_BADGE, true))
                    putBoolean(ModulePrefs.KEY_EXPLORE, prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false))
                    putString(ModulePrefs.KEY_API_BASE, prefs.getString(ModulePrefs.KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT))
                    putString(ModulePrefs.KEY_API_KEY, prefs.getString(ModulePrefs.KEY_API_KEY, ""))
                }
            }
            "report" -> {
                ctx.getSharedPreferences(RUNTIME_FILE, 0).edit()
                    .putString("status", arg.orEmpty().take(200))
                    .putLong("last_seen", System.currentTimeMillis()).apply()
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
    }
}
