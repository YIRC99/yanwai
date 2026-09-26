package dev.jev.wechatmood.core

/** Evidence embedded in a reply, not a new chronological turn or a verified current speaker. */
data class QuotedMessage(val text: String?, val displayName: String?, val type: Int?,
    val serverId: String?, val unavailableReason: String? = null)
