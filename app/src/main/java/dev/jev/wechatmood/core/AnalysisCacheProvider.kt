package dev.jev.wechatmood.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import dev.jev.wechatmood.BuildConfig
import java.io.File

/** Called only after SettingsProvider has checked the Binder caller's UID. */
object AnalysisCacheProvider {
    private var store: SqliteAnalysisCache? = null
    private fun storage(context: Context): SqliteAnalysisCache {
        check(context.packageName == BuildConfig.APPLICATION_ID) { "Cache storage must belong to the module" }
        store?.let { return it }
        val db = SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "analysis_cache_v1.db"), null)
        return try {
            SqliteAnalysisCache(object : AnalysisCacheDatabase {
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
        require(arg != null && arg.matches(Regex("[0-9a-f]{64}"))) { "Invalid cache key" }
        val key = AnalysisCacheKey(AnalysisCacheKey.digest(caller.toString(), arg))
        return when (method) {
            "cache_get" -> Bundle().apply { storage(context).find(key)?.let { putString("payload", it.encode()) } }
            "cache_put" -> {
                val result = CachedAnalysisResult.decode(requireNotNull(extras?.getString("payload")))
                storage(context).save(key, result.mood, result.evidence)
                Bundle().apply { putBoolean("saved", true) }
            }
            else -> throw IllegalArgumentException("Unknown cache method")
        }
    }
}
