package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.AnalysisCacheDatabase
import dev.jev.wechatmood.core.AnalysisCacheKey
import org.json.JSONObject
import java.io.Closeable

/** Raw editor text is retained, including an unfinished custom identity. */
data class ReplyIdentitySetting(val relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, val customText: String = "") {
    init { require(customText.length <= ReplyRelationship.MAX_CUSTOM_LENGTH) }
    fun encode(): String = JSONObject().put("format", 1).put("role", relationship.id).put("text", customText).toString()
    companion object {
        fun decode(payload: String): ReplyIdentitySetting {
            require(payload.length <= 1024)
            val json = JSONObject(payload)
            require(json.getInt("format") == 1)
            return ReplyIdentitySetting(ReplyRelationship.entries.single { it.id == json.getString("role") }, json.getString("text"))
        }
    }
}

data class ReplyContactKey(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{64}"))) }
    companion object {
        fun of(account: String?, talker: String?): ReplyContactKey? {
            if (account == null || !account.matches(Regex("[0-9a-f]{64}")) || talker == null ||
                !talker.matches(Regex("[A-Za-z0-9_@.\\-]{1,256}")) || talker.endsWith("@chatroom")) return null
            return ReplyContactKey(AnalysisCacheKey.digest("reply-contact-v1", account, talker))
        }
    }
}

/** Captured on the main thread; never rebind a queued operation to the current conversation. */
data class ReplyIdentityOwner(val epoch: String, val account: String?, val talker: String) {
    val key: ReplyContactKey? get() = ReplyContactKey.of(account, talker)
    val historyScope: String get() = account ?: epoch
    fun isCurrent(currentEpoch: String, currentTalker: String?) = epoch == currentEpoch && talker == currentTalker
    fun acceptsAccount(currentAccount: String?) = account == currentAccount
}

/** Same exact-message verification and database-path hash as the analysis cache. No nickname lookup. */
object ReplyAccountIdentity {
    fun resolve(page: ReplyContext, sources: List<Pair<String, ReplyHistoryQuery>>): String? {
        val anchor = page.historyAnchor?.takeIf { it.id > 0 && it.time > 0 } ?: return null
        return sources.mapNotNull { (account, source) -> runCatching {
            val record = source.query("SELECT * FROM message WHERE talker = ? AND msgId = ? LIMIT 1",
                arrayOf(page.talker, anchor.id.toString())).singleOrNull()
            account.takeIf { it.matches(Regex("[0-9a-f]{64}")) && record != null &&
                record.talker == page.talker && anchor.matches(record) }
        }.getOrNull() }.distinct().singleOrNull()
    }
}

/** Long-lived settings, not an evictable result cache. Opened only by the module provider. */
class ReplyIdentityStore(private val db: AnalysisCacheDatabase) : Closeable {
    init { db.execute("CREATE TABLE IF NOT EXISTS reply_identities (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)") }
    init { db.execute("CREATE TABLE IF NOT EXISTS contact_backgrounds (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)") }
    val roles = ReplyRoleStore(db, this)
    @Synchronized fun background(key: ReplyContactKey): ContactBackground =
        db.query("SELECT payload FROM contact_backgrounds WHERE contact_key = ?", listOf(key.value))
            ?.let(ContactBackground::decode) ?: ContactBackground()
    @Synchronized fun saveBackground(key: ReplyContactKey, text: String): ContactBackground {
        require(text.length <= ContactBackground.MAX_LENGTH)
        val previous = background(key)
        if (previous.text == text) return previous
        val value = ContactBackground(text, java.util.UUID.randomUUID().toString())
        db.execute("INSERT OR REPLACE INTO contact_backgrounds(contact_key, payload) VALUES(?, ?)", listOf(key.value, value.encode()))
        return value
    }
    @Synchronized fun find(key: ReplyContactKey): ReplyIdentitySetting {
        val payload = db.query("SELECT payload FROM reply_identities WHERE contact_key = ?", listOf(key.value))
            ?: return ReplyIdentitySetting()
        return runCatching { ReplyIdentitySetting.decode(payload) }.getOrDefault(ReplyIdentitySetting())
    }
    @Synchronized fun save(key: ReplyContactKey, setting: ReplyIdentitySetting) {
        if (setting.relationship == ReplyRelationship.UNSPECIFIED) {
            db.execute("DELETE FROM reply_identities WHERE contact_key = ?", listOf(key.value))
        } else {
            db.execute("INSERT OR REPLACE INTO reply_identities(contact_key, payload) VALUES(?, ?)", listOf(key.value, setting.encode()))
        }
    }
    override fun close() = db.close()
}
