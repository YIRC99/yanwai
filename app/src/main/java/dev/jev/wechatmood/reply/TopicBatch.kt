package dev.jev.wechatmood.reply

data class TopicSuggestion(val title: String, val parts: List<String>, val reason: String) {
    init {
        require(title.isNotBlank() && title.length <= 100 && reason.length <= 500)
        require(parts.size in 1..3 && parts.all { it.isNotBlank() && it.codePointCount(0, it.length) <= 60 })
        require(parts.sumOf { it.codePointCount(0, it.length) } <= 120)
    }
    constructor(title: String, opener: String, reason: String) : this(title, listOf(opener), reason)
    /** Combined text is only for deduplication; copy/fill always uses one selected part. */
    val opener get() = parts.joinToString("\n")
    fun asReply() = ReplySuggestion(parts, reason)
}

/** Only in process memory. Input changes require a new batch instead of relabeling old suggestions. */
data class TopicKey(val fingerprint: String, val limit: Int, val relationship: ReplyRelationship,
    val notes: String, val draft: String, val date: String, val customRelationship: String = "")

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
