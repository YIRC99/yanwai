package dev.jev.wechatmood.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.reply.*
import java.io.File

/** SettingsProvider checks the Binder UID before dispatching here. */
object ReplyIdentityProvider {
    const val KEY_ROLE_REVISION = "reply_roles_revision"
    val CHANGES_URI: android.net.Uri = SettingsProvider.URI.buildUpon().appendPath("roles").build()
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
    @Synchronized fun roles(context: Context): List<ReplyRole> = storage(context).roles.list()
    @Synchronized fun rolesRevision(context: Context): Long = storage(context).roles.revision()
    @Synchronized fun saveRole(context: Context, id: String?, name: String, background: String, revision: String?): ReplyRole =
        storage(context).roles.save(id, name, background, revision)
    @Synchronized fun deleteRole(context: Context, role: ReplyRole) = storage(context).roles.delete(role.id, role.revision)

    @Synchronized fun call(context: Context, caller: Int, method: String, arg: String?, extras: Bundle?): Bundle {
        if (method.startsWith("contact_background_") || method.startsWith("reply_role_") || extras?.containsKey(SettingsProvider.KEY_GENERATION) == true) {
            val generation = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getString(SettingsProvider.KEY_GENERATION, null)
            check(generation != null && extras?.getString(SettingsProvider.KEY_GENERATION) == generation) { "设置已重置，请重新打开" }
        }
        if (method.endsWith("_put") && extras?.containsKey(KEY_ROLE_REVISION) == true) {
            check(extras.getLong(KEY_ROLE_REVISION) == storage(context).roles.revision()) { "角色已变化，请重新打开" }
        }
        if (method == "reply_role_list") return Bundle().apply {
            putStringArrayList("roles", ArrayList(storage(context).roles.templates().map { it.encode(includeBackground = false) }))
        }
        val requested = ReplyContactKey(requireNotNull(arg))
        val key = ReplyContactKey(AnalysisCacheKey.digest(caller.toString(), requested.value))
        return when (method) {
            "reply_role_put" -> {
                val applied = storage(context).roles.saveFromChat(key,
                    ReplyIdentitySetting.decode(requireNotNull(extras?.getString("identity"))),
                    requireNotNull(extras?.getString("text")))
                Bundle().apply {
                    putString("identity", applied.identity.encode())
                    putString("background", applied.background.encode())
                    putLong(KEY_ROLE_REVISION, requireNotNull(applied.catalogRevision))
                }
            }
            "reply_role_apply" -> {
                check(extras?.getLong(KEY_ROLE_REVISION) == storage(context).roles.revision()) { "角色已变化，请重新选择" }
                val applied = storage(context).roles.apply(requireNotNull(extras?.getString("role_id")),
                    requireNotNull(extras?.getString("role_revision")), key)
                Bundle().apply {
                    putString("identity", applied.identity.encode())
                    putString("background", applied.background.encode())
                }
            }
            "contact_background_get" -> Bundle().apply { putString("payload", storage(context).background(key).encode()) }
            "contact_background_put" -> Bundle().apply {
                putString("payload", storage(context).saveBackground(key, requireNotNull(extras?.getString("text"))).encode())
            }
            "reply_identity_get" -> Bundle().apply { putString("payload", storage(context).find(key).encode()) }
            "reply_identity_put" -> {
                val identity = ReplyIdentitySetting.decode(requireNotNull(extras?.getString("payload")))
                storage(context).save(key, identity)
                if (identity.roleId == null) storage(context).saveBackground(key, "")
                Bundle().apply { putBoolean("saved", true) }
            }
            else -> throw IllegalArgumentException("Unknown identity method")
        }
    }
}
