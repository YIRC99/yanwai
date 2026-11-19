package com.dogheadstrategist.chat.reply

import com.dogheadstrategist.chat.core.CacheDatabase

/**
 * 保存当前联系人的角色卡：身份和背景。
 *
 * 这里曾经还维护一个「角色库」——用户手建的可复用模板，而且每保存一张卡都会在
 * 模板表里留一份副本，于是同一件事有了两处存储、两套界面，改了一边另一边还是旧的。
 * 现在只保留一种东西：每个联系人一张卡。身份进 reply_identities，背景进
 * contact_backgrounds，不再有模板，也不再产生副本。
 */
class AppliedReplyRole(val identity: ReplyIdentitySetting, val background: ContactBackground)

/** 与身份库共用同一个连接和锁，改身份和背景是一个原子操作。 */
class ReplyRoleStore(private val db: CacheDatabase, private val identities: ReplyIdentityStore) {

    /**
     * 保存微信里填的身份和背景。
     *
     * [identity] 里可能还带着旧版本的 roleId，那是已废弃的角色库引用。这里有意忽略它：
     * 它指向的记录早就不存在了，校验它只会让老用户每次保存都报「角色已删除」。
     */
    fun saveFromChat(key: ReplyContactKey, identity: ReplyIdentitySetting, text: String,
        talkerTag: String? = null): AppliedReplyRole = synchronized(identities) {
        require(identity.relationship != ReplyRelationship.UNSPECIFIED)
        require(identity.relationship != ReplyRelationship.OTHER || identity.customText.isNotBlank())
        require(text.length <= ContactBackground.MAX_LENGTH)
        transaction {
            val clean = ReplyIdentitySetting(identity.relationship, identity.customText)
            identities.save(key, clean, talkerTag)
            identities.saveBackground(key, text)
            AppliedReplyRole(clean, identities.background(key))
        }
    }

    private fun <T> transaction(block: () -> T): T {
        db.execute("BEGIN TRANSACTION")
        try { return block().also { db.execute("COMMIT") } }
        catch (error: Exception) { db.execute("ROLLBACK"); throw error }
    }
}
