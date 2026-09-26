package dev.jev.wechatmood.reply

/** Local editor state. Opening, selecting and changing roles never initiate a request. */
class ReplyComposition(remembered: RememberedReply? = null) {
    var relationship = remembered?.relationship ?: ReplyRelationship.UNSPECIFIED
    var customRelationship = remembered?.customRelationship.orEmpty()
    val activeCustomRelationship: String get() = relationship.customValue(customRelationship)
    val hasValidRelationship: Boolean get() = relationship != ReplyRelationship.OTHER || activeCustomRelationship.isNotBlank()
    var historyLimit = remembered?.context?.requestedMessages ?: ReplyContext.MAX_MESSAGES
    var result: RememberedReply? = remembered?.copy(selectedPart = remembered.selectedPart.coerceIn(0, remembered.suggestion.parts.lastIndex))
        private set
    val selectedPart: Int get() = result?.selectedPart ?: 0
    val canUse: Boolean get() = hasValidRelationship && result?.relationship == relationship &&
        result?.customRelationship == activeCustomRelationship && result?.context?.requestedMessages == historyLimit
    val selectedText: String? get() = result?.takeIf { canUse }?.suggestion?.parts?.get(selectedPart)
    val previousText: String get() = result?.takeIf { canUse }?.suggestion?.text.orEmpty()

    fun select(index: Int) {
        val current = requireNotNull(result)
        require(index in current.suggestion.parts.indices)
        result = current.copy(selectedPart = index)
    }

    /** Advancing means filled, never sent. The user remains in control of sending and re-selection. */
    fun afterFill() {
        if (canUse) result?.let { select((selectedPart + 1).coerceAtMost(it.suggestion.parts.lastIndex)) }
    }

    fun restoreSelection(filled: RememberedReply) {
        val current = result ?: return
        if (current.suggestion === filled.suggestion && current.context == filled.context) select(filled.selectedPart)
    }

    fun accept(context: ReplyContext, suggestion: ReplySuggestion, direction: String, focusMessageId: Long?,
        requestedRelationship: ReplyRelationship, requestedCustomRelationship: String = ""): Boolean {
        if (!hasValidRelationship || requestedRelationship != relationship || context.requestedMessages != historyLimit ||
            requestedRelationship.customValue(requestedCustomRelationship) != activeCustomRelationship) return false
        result = RememberedReply(context, suggestion, direction, focusMessageId, requestedRelationship, customRelationship = activeCustomRelationship)
        return true
    }

    fun acceptTopics(context: ReplyContext, topics: List<TopicSuggestion>, key: TopicKey, focusMessageId: Long?): Boolean {
        if (!hasValidRelationship || key.fingerprint != context.fingerprint || key.limit != historyLimit || context.requestedMessages != historyLimit ||
            key.relationship != relationship || key.customRelationship != activeCustomRelationship) return false
        val batch = TopicBatch(key, topics.toList())
        result = RememberedReply(context, batch.current.asReply(), key.notes, focusMessageId, relationship, topics = batch,
            customRelationship = activeCustomRelationship)
        return true
    }

    fun nextTopic(key: TopicKey): Boolean {
        val current = result ?: return false
        val batch = current.topics?.takeIf { canUse && it.key == key }?.next() ?: return false
        result = current.copy(suggestion = batch.current.asReply(), selectedPart = 0, topics = batch)
        return true
    }
}
