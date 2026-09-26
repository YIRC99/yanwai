package dev.jev.wechatmood.reply

data class TopicSuggestion(val title: String, val opener: String, val reason: String) {
    init { require(title.isNotBlank() && title.length <= 100 && opener.isNotBlank() && opener.length <= 1000 && reason.length <= 500) }
    fun asReply() = ReplySuggestion(opener, reason)
}

/** Only in process memory. Input changes require a new batch instead of relabeling old suggestions. */
data class TopicKey(val fingerprint: String, val limit: Int, val relationship: ReplyRelationship,
    val notes: String, val draft: String, val date: String)

data class TopicBatch(val key: TopicKey, val items: List<TopicSuggestion>, val order: List<Int> = items.indices.shuffled(),
    val position: Int = 0) {
    init {
        require(items.size == SIZE && items.map { it.title }.distinct().size == SIZE && items.map { it.opener }.distinct().size == SIZE)
        require(order.sorted() == items.indices.toList() && position in items.indices)
    }
    val current get() = items[order[position]]
    val shownCount get() = position + 1
    val hasNext get() = position < items.lastIndex
    fun next() = if (hasNext) copy(position = position + 1) else null
    companion object { const val SIZE = 5 }
}
