package dev.jev.wechatmood.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.reply.*
import java.io.File

/** SettingsProvider checks the Binder UID before dispatching here. */
object ReplyIdentityProvider {
    private var store: ReplyIdentityStore? = null
    private fun storage(context: Context): ReplyIdentityStore {
        check(context.packageName == BuildConfig.APPLICATION_ID)
        store?.let { return it }
        val db = SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "reply_identities_v1.db"), null)
        return try {
            ReplyIdentityStore(object : AnalysisCacheDatabase {
                override fun execute(sql: String, args: List<String>) {
                    if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
                }
                override fun query(sql: String, args: List<String>): String? = db.rawQuery(sql, args.toTypedArray()).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                override fun close() = db.close()
            }).also { store = it }
        } catch (error: Exception) { db.close(); throw error }
    }
    @Synchronized fun call(context: Context, caller: Int, method: String, arg: String?, extras: Bundle?): Bundle {
        if (method.startsWith("contact_background_")) {
            val generation = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getString(SettingsProvider.KEY_GENERATION, null)
            check(generation != null && extras?.getString(SettingsProvider.KEY_GENERATION) == generation) { "设置已重置，请重新打开" }
        }
        val requested = ReplyContactKey(requireNotNull(arg))
        val key = ReplyContactKey(AnalysisCacheKey.digest(caller.toString(), requested.value))
        return when (method) {
            "contact_background_get" -> Bundle().apply { putString("payload", storage(context).background(key).encode()) }
            "contact_background_put" -> Bundle().apply {
                putString("payload", storage(context).saveBackground(key, requireNotNull(extras?.getString("text"))).encode())
            }
            "reply_identity_get" -> Bundle().apply { putString("payload", storage(context).find(key).encode()) }
            "reply_identity_put" -> {
                storage(context).save(key, ReplyIdentitySetting.decode(requireNotNull(extras?.getString("payload"))))
                Bundle().apply { putBoolean("saved", true) }
            }
            else -> throw IllegalArgumentException("Unknown identity method")
        }
    }
}
