package com.dogheadstrategist.chat.reply

import com.dogheadstrategist.chat.core.CacheDatabase
import com.dogheadstrategist.chat.core.CacheKey
import org.json.JSONObject
import java.io.Closeable

/** Raw editor text is retained, including an unfinished custom identity. */
data class ReplyIdentitySetting(val relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, val customText: String = "",
    val roleId: String? = null, val roleRevision: String? = null) {
    init {
        require(customText.length <= ReplyRelationship.MAX_CUSTOM_LENGTH)
        require((roleId == null) == (roleRevision == null))
        require(roleId == null || roleId.matches(Regex("role:[0-9a-f-]{36}")))
        require(roleRevision == null || roleRevision.length in 1..64)
    }
    fun encode(): String = JSONObject().put("format", 1).put("role", relationship.id).put("text", customText)
        .put("roleId", roleId).put("roleRevision", roleRevision).toString()
    companion object {
        fun decode(payload: String): ReplyIdentitySetting {
            require(payload.length <= 1024)
            val json = JSONObject(payload)
            require(json.getInt("format") == 1)
            return ReplyIdentitySetting(ReplyRelationship.entries.single { it.id == json.getString("role") }, json.getString("text"),
                json.optString("roleId").takeIf { it.isNotEmpty() }, json.optString("roleRevision").takeIf { it.isNotEmpty() })
        }
    }
}

data class ReplyContactKey(val value: String) {
    init { require(value.matches(Regex("[0-9a-f]{64}"))) }
    companion object {
        fun of(account: String?, talker: String?): ReplyContactKey? {
            if (account == null || !account.matches(Regex("[0-9a-f]{64}")) || talker == null ||
                !talker.matches(Regex("[A-Za-z0-9_@.\\-]{1,256}")) || talker.endsWith("@chatroom")) return null
            return ReplyContactKey(CacheKey.digest("reply-contact-v1", account, talker))
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
class ReplyIdentityStore(private val db: CacheDatabase) : Closeable {
    // 拼批量结果用的分隔符。选控制字符是因为它们不可能出现在身份名或 JSON 正文里。
    private companion object {
        const val UNIT_SEPARATOR = "\u001f"
        const val RECORD_SEPARATOR = "\u001e"
    }

    init {
        db.execute("CREATE TABLE IF NOT EXISTS reply_identities (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, talker_tag TEXT)")
        // 老库没有这列。加不上通常就是已经有了，忽略即可。
        runCatching { db.execute("ALTER TABLE reply_identities ADD COLUMN talker_tag TEXT") }
        db.execute("CREATE TABLE IF NOT EXISTS contact_backgrounds (contact_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
    }
    val roles = ReplyRoleStore(db, this)
    private fun storedBackground(key: ReplyContactKey): ContactBackground =
        db.query("SELECT payload FROM contact_backgrounds WHERE contact_key = ?", listOf(key.value))
            ?.let(ContactBackground::decode) ?: ContactBackground()
    /**
     * 这个联系人的背景。
     *
     * 角色库取消后，背景只有 contact_backgrounds 这一处。旧版本保存时一直是双写的
     * （角色库一份、这里一份），所以切换之后老数据仍然在，不需要迁移。
     */
    @Synchronized fun background(key: ReplyContactKey): ContactBackground = storedBackground(key)
    @Synchronized fun saveBackground(key: ReplyContactKey, text: String): ContactBackground {
        require(text.length <= ContactBackground.MAX_LENGTH)
        val previous = storedBackground(key)
        if (previous.text == text) return previous
        val value = ContactBackground(text, java.util.UUID.randomUUID().toString())
        db.execute("INSERT OR REPLACE INTO contact_backgrounds(contact_key, payload) VALUES(?, ?)", listOf(key.value, value.encode()))
        return value
    }
    private fun storedIdentity(key: ReplyContactKey): ReplyIdentitySetting {
        val payload = db.query("SELECT payload FROM reply_identities WHERE contact_key = ?", listOf(key.value))
            ?: return ReplyIdentitySetting()
        return runCatching { ReplyIdentitySetting.decode(payload) }.getOrDefault(ReplyIdentitySetting())
    }
    @Synchronized fun find(key: ReplyContactKey): ReplyIdentitySetting = storedIdentity(key)

    /**
     * 绑了角色卡的联系人的 talker 摘要。
     *
     * 存摘要而不是明文，是为了保住「不落任何身份」这条设计：contact_key 里有登录账号，
     * 反过来又算不出 talker。补一个只跟 talker 有关的摘要，app 进程拿聊天记录里的
     * talker 自己算一遍就能对照，谁也不需要知道对方是谁。
     */
    @Synchronized fun boundTalkerTags(): Set<String> = buildSet {
        var cursor = ""
        while (true) {
            val tag = db.query(
                "SELECT talker_tag FROM reply_identities" +
                    " WHERE talker_tag IS NOT NULL AND talker_tag != ''" +
                    " AND talker_tag > ?" +
                    " ORDER BY talker_tag LIMIT 1",
                listOf(cursor),
            ) ?: break
            add(tag)
            cursor = tag
        }
    }

    /**
     * 按 talker 摘要取角色卡背景，取不到返回空串。
     *
     * 人物档案分析把它当画像初稿。只读不写——角色卡有乐观锁，后台自动写回会让用户
     * 在微信里保存失败（revision 对不上就会提示「角色已变化」）。
     */
    /**
     * 所有绑过角色卡的 talker 摘要 → 用户设的身份名（如「老婆」）。
     *
     * 人物档案列表要显示「谁是谁」，但列表是同步渲染的，逐个走下去问 Binder 会卡主线程
     * （几十个人就是几十次跨进程）。这里用一次 group_concat 全量取回，调用方一次拿完。
     *
     * 没设身份（UNSPECIFIED）的人不返回：那种情况下「未指定」比空白更没有信息量。
     */
    @Synchronized fun displayLabels(): Map<String, String> {
        val joined = db.query(
            "SELECT group_concat(talker_tag || ? || payload, ?) FROM reply_identities" +
                " WHERE talker_tag IS NOT NULL AND talker_tag != ''",
            listOf(UNIT_SEPARATOR, RECORD_SEPARATOR),
        ) ?: return emptyMap()
        return buildMap {
            for (record in joined.split(RECORD_SEPARATOR)) {
                val cut = record.indexOf(UNIT_SEPARATOR)
                if (cut <= 0) continue
                val setting = runCatching { ReplyIdentitySetting.decode(record.substring(cut + 1)) }.getOrNull() ?: continue
                if (setting.relationship == ReplyRelationship.UNSPECIFIED) continue
                put(record.substring(0, cut), setting.relationship.displayLabel(setting.customText))
            }
        }
    }

    @Synchronized fun backgroundForTag(tag: String): String {
        val keyValue = db.query("SELECT contact_key FROM reply_identities WHERE talker_tag = ?", listOf(tag))
            ?: return ""
        return runCatching { background(ReplyContactKey(keyValue)).text }.getOrDefault("")
    }

    /**
     * @param talkerTag 传入时一并记下「这行属于哪个联系人」；传 null 保留原值。
     *   角色库改名、队列写入这些路径拿不到 talker，不能让它们把已有的摘要抹掉。
     */
    @Synchronized fun save(key: ReplyContactKey, setting: ReplyIdentitySetting, talkerTag: String? = null) {
        if (setting.relationship == ReplyRelationship.UNSPECIFIED) {
            db.execute("DELETE FROM reply_identities WHERE contact_key = ?", listOf(key.value))
        } else {
            val tag = talkerTag
                ?: db.query("SELECT talker_tag FROM reply_identities WHERE contact_key = ?", listOf(key.value))
            db.execute("INSERT OR REPLACE INTO reply_identities(contact_key, payload, talker_tag) VALUES(?, ?, ?)",
                listOf(key.value, setting.encode(), tag.orEmpty()))
        }
    }
    /**
     * 删掉这张角色卡：身份和背景一起清。
     *
     * 和 [save] 传 UNSPECIFIED 不是一回事——那条路只删身份行，背景还留着。
     * 用户点「清除角色卡」时，他要的是这个人从来没被设置过，包括背景。
     */
    @Synchronized fun clearAll(key: ReplyContactKey) {
        db.execute("DELETE FROM reply_identities WHERE contact_key = ?", listOf(key.value))
        db.execute("DELETE FROM contact_backgrounds WHERE contact_key = ?", listOf(key.value))
    }

    override fun close() = db.close()
}
