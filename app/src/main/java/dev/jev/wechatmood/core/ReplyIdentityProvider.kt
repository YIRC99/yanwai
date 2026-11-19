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
    /** 清空所有联系人的关系档案（App 进程内直接调用）。 */
    @Synchronized fun clearAllFacts(context: Context) {
        val db = SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "reply_identities_v1.db"), null)
        try {
            db.execSQL("DELETE FROM relation_facts")
            db.execSQL("DELETE FROM relation_meta")
        } finally { db.close() }
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
        if (method == "relation_facts_enabled") {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
            // 默认关闭：没明确开启过就不读聊天、不上传，和设置页文案保持一致。
            return Bundle().apply { putBoolean("enabled", prefs.getBoolean(ModulePrefs.KEY_RELATION_FACTS, false)) }
        }
        val requested = ReplyContactKey(requireNotNull(arg))
        val key = ReplyContactKey(AnalysisCacheKey.digest(caller.toString(), requested.value))
        return when (method) {
            "relation_facts_get" -> {
                val store = storage(context).facts
                val meta = store.meta(key)
                Bundle().apply {
                    putStringArrayList("facts", ArrayList(store.list(key).map { it.encode().toString() }))
                    putLong("last_at", meta.lastAt)
                    putLong("last_message_id", meta.lastMessageId)
                }
            }
            "relation_facts_put" -> {
                val store = storage(context).facts
                val incoming = extras?.getString("facts").orEmpty().let { payload ->
                    runCatching { org.json.JSONArray(payload) }.getOrNull()?.let { array ->
                        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(RelationFact::decode) }
                    }.orEmpty()
                }
                val merged = RelationFacts.merge(store.list(key), incoming, System.currentTimeMillis())
                store.save(key, merged)
                store.saveMeta(key, RelationMeta(extras?.getLong("last_at", 0L) ?: 0L,
                    extras?.getLong("last_message_id", 0L) ?: 0L))
                Bundle().apply { putStringArrayList("facts", ArrayList(merged.map { it.encode().toString() })) }
            }
            "relation_facts_clear" -> {
                storage(context).facts.clear(key)
                Bundle()
            }
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
                // 手动改过背景就不再跟随角色模板，否则会连带改到用同一角色的其他聊天。
                val current = storage(context).find(key)
                if (current.roleId != null) storage(context).save(key, current.copy(roleId = null, roleRevision = null))
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
