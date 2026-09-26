package dev.jev.wechatmood.reply

data class RememberedReply(val context: ReplyContext, val suggestion: ReplySuggestion,
    val direction: String = "", val focusMessageId: Long? = null,
    val relationship: ReplyRelationship = ReplyRelationship.UNSPECIFIED, val selectedPart: Int = 0,
    val topics: TopicBatch? = null, val customRelationship: String = "")

/** Successful replies only. Never persisted, logged, or shared across conversations. */
class ReplyHistory(private val capacity: Int = 12) {
    init { require(capacity > 0) }
    private val replies = LinkedHashMap<String, RememberedReply>(capacity, 0.75f, true)
    fun remember(reply: RememberedReply) {
        replies[reply.context.talker] = reply
        while (replies.size > capacity) replies.remove(replies.keys.first())
    }
    fun recall(talker: String, focusMessageId: Long? = null): RememberedReply? =
        replies[talker]?.takeIf { focusMessageId == null || it.focusMessageId == focusMessageId }
    fun clear() { replies.clear() }
    companion object { val process = ReplyHistory() }
}
