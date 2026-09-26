package dev.jev.wechatmood.reply

/** Local editor state. Opening, selecting and changing roles never initiate a request. */
class ReplyComposition(remembered: RememberedReply? = null) {
    var relationship = remembered?.relationship ?: ReplyRelationship.UNSPECIFIED
    var result: RememberedReply? = remembered?.copy(selectedPart = remembered.selectedPart.coerceIn(0, remembered.suggestion.parts.lastIndex))
        private set
    val selectedPart: Int get() = result?.selectedPart ?: 0
    val canUse: Boolean get() = result?.relationship == relationship
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
        requestedRelationship: ReplyRelationship): Boolean {
        if (requestedRelationship != relationship) return false
        result = RememberedReply(context, suggestion, direction, focusMessageId, requestedRelationship)
        return true
    }
}
