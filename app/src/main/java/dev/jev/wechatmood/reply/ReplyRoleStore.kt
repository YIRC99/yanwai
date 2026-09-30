package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheDatabase
import dev.jev.wechatmood.core.AnalysisCacheKey
import org.json.JSONObject
import java.util.UUID

/** Management IDs stay local. Neither names nor backgrounds belong in diagnostic output. */
class ReplyRole(val id: String, val name: String, val background: String, val revision: String) {
    val fromContact get() = id.startsWith("contact:")
    init {
        require(id.matches(Regex("role:[0-9a-f-]{36}|contact:[0-9a-f]{64}")))
        require(name.isNotBlank() && name.length <= ReplyRelationship.MAX_CUSTOM_LENGTH)
        require(background.length <= ContactBackground.MAX_LENGTH && revision.length in 1..64)
    }
    fun encode(includeBackground: Boolean = true): String = JSONObject().put("id", id).put("name", name)
        .put("background", if (includeBackground) background else "").put("revision", revision).toString()
    companion object {
        fun decode(payload: String): ReplyRole {
            require(payload.length <= 16000)
            val json = JSONObject(payload)
            return ReplyRole(json.getString("id"), json.getString("name"), json.getString("background"), json.getString("revision"))
        }
    }
}

class AppliedReplyRole(val identity: ReplyIdentitySetting, val background: ContactBackground)

/** Shares the identity database and lock; edits to a contact's identity/background are atomic. */
class ReplyRoleStore(private val db: AnalysisCacheDatabase, private val identities: ReplyIdentityStore) {
    init {
        db.execute("CREATE TABLE IF NOT EXISTS reply_roles (role_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execute("CREATE TABLE IF NOT EXISTS reply_role_revision (id INTEGER PRIMARY KEY, revision INTEGER NOT NULL)")
        db.execute("INSERT OR IGNORE INTO reply_role_revision(id, revision) VALUES(1, 0)")
    }
    fun revision(): Long = synchronized(identities) {
        db.query("SELECT revision FROM reply_role_revision WHERE id = 1", emptyList())!!.toLong()
    }
    private fun keys(table: String, column: String): List<String> = buildList {
        var cursor = ""
        while (true) {
            val key = db.query("SELECT $column FROM $table WHERE $column > ? ORDER BY $column LIMIT 1", listOf(cursor)) ?: break
            add(key); cursor = key
        }
    }
    fun templates(): List<ReplyRole> = synchronized(identities) {
        keys("reply_roles", "role_id").mapNotNull(::find)
    }
    fun list(): List<ReplyRole> = synchronized(identities) {
        templates() + keys("reply_identities", "contact_key").mapNotNull { find("contact:$it") }
    }
    fun find(id: String): ReplyRole? = synchronized(identities) {
        if (id.startsWith("contact:")) {
            val key = ReplyContactKey(id.removePrefix("contact:"))
            val identity = identities.find(key)
            if (identity.relationship == ReplyRelationship.UNSPECIFIED ||
                identity.relationship == ReplyRelationship.OTHER && identity.customText.isBlank()) return@synchronized null
            val background = identities.background(key)
            ReplyRole(id, identity.relationship.displayLabel(identity.customText), background.text,
                AnalysisCacheKey.digest(identity.encode(), background.encode()))
        } else {
            require(id.matches(Regex("role:[0-9a-f-]{36}")))
            db.query("SELECT payload FROM reply_roles WHERE role_id = ?", listOf(id))?.let(ReplyRole::decode)
        }
    }
    fun save(id: String?, name: String, background: String, expectedRevision: String? = null): ReplyRole = synchronized(identities) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= ReplyRelationship.MAX_CUSTOM_LENGTH) { "角色名称请填写 1–40 个字" }
        require(clean.none { it.isISOControl() }) { "角色名称不能换行" }
        require(background.length <= ContactBackground.MAX_LENGTH) { "背景请控制在 2000 字以内" }
        val previous = id?.let { checkNotNull(find(it)) { "角色已删除，请刷新列表" } }
        check(previous == null || previous.revision == expectedRevision) { "角色已被修改，请重新打开编辑" }
        if (previous == null) require(templates().size < 100) { "最多保存 100 个角色，请先整理已有角色" }
        transaction {
            val target = id ?: "role:${UUID.randomUUID()}"
            if (previous?.fromContact == true) {
                val key = ReplyContactKey(target.removePrefix("contact:"))
                val identity = identities.find(key)
                identities.save(key, if (clean == previous.name) identity else ReplyIdentitySetting(ReplyRelationship.OTHER, clean))
                identities.saveBackground(key, background)
            } else {
                val value = ReplyRole(target, clean, background, UUID.randomUUID().toString())
                db.execute("INSERT OR REPLACE INTO reply_roles(role_id, payload) VALUES(?, ?)", listOf(target, value.encode()))
            }
            db.execute("UPDATE reply_role_revision SET revision = revision + 1 WHERE id = 1")
            requireNotNull(find(target))
        }
    }
    fun delete(id: String, expectedRevision: String) = synchronized(identities) {
        val previous = checkNotNull(find(id)) { "角色已删除，请刷新列表" }
        check(previous.revision == expectedRevision) { "角色已被修改，请重新打开" }
        transaction {
            if (previous.fromContact) {
                val key = ReplyContactKey(id.removePrefix("contact:"))
                identities.save(key, ReplyIdentitySetting())
                // Keep a new empty-background revision, so an old result cannot survive deletion.
                identities.saveBackground(key, "")
            } else db.execute("DELETE FROM reply_roles WHERE role_id = ?", listOf(id))
            db.execute("UPDATE reply_role_revision SET revision = revision + 1 WHERE id = 1")
        }
    }
    fun apply(id: String, expectedRevision: String, key: ReplyContactKey): AppliedReplyRole = synchronized(identities) {
        require(id.startsWith("role:"))
        val role = checkNotNull(find(id)) { "角色已删除，请重新选择" }
        check(role.revision == expectedRevision) { "角色已修改，请重新选择" }
        transaction {
            val identity = ReplyIdentitySetting(ReplyRelationship.OTHER, role.name)
            identities.save(key, identity)
            AppliedReplyRole(identity, identities.saveBackground(key, role.background))
        }
    }
    private fun <T> transaction(block: () -> T): T {
        db.execute("BEGIN TRANSACTION")
        try { return block().also { db.execute("COMMIT") } }
        catch (error: Exception) { db.execute("ROLLBACK"); throw error }
    }
}
