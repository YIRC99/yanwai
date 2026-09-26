package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.core.ContextCoverage
import dev.jev.wechatmood.core.MessagePolicy

/** Bounded local adapter reads only; keep whole messages and disclose holes in the evidence. */
object MessageContext {
    fun collect(message: MessageMetadata, position: Int, itemAt: (Int) -> MessageMetadata?): AnalysisInput? {
        val text = message.incomingText() ?: return null
        val recent = mutableListOf<ContextMessage>()
        var scanned = 0
        var media = 0
        var missing = 0
        var omittedText = 0
        var invalidTime = 0
        var characters = 0
        var cursor = position - 1
        var newerTime = message.createdAt
        while (cursor >= 0 && scanned < MessagePolicy.MAX_CONTEXT_SCAN && recent.size < MessagePolicy.MAX_CONTEXT_MESSAGES) {
            val previous = runCatching { itemAt(cursor) }.getOrNull()
            cursor--; scanned++
            if (previous == null) { missing++; continue }
            if (previous.talker != message.talker) { missing++; continue }
            if (previous.createdAt > 0 && newerTime > 0 && previous.createdAt > newerTime) {
                invalidTime++; continue
            }
            if (previous.createdAt > 0) newerTime = previous.createdAt
            if (previous.type != 1) { media++; continue }
            val previousText = previous.plainText()
            if (previousText == null) { omittedText++; continue }
            if (characters + previousText.length > MessagePolicy.MAX_CONTEXT_CHARACTERS) {
                omittedText++; break // Do not cherry-pick older short messages around a missing long turn.
            }
            characters += previousText.length
            recent += ContextMessage(previous.speaker(), previousText, previous.createdAt, previous.messageId)
        }
        return AnalysisInput(text, message.talker, recent.asReversed().toList(), message.messageId, message.speaker(),
            message.createdAt, ContextCoverage("loaded_page", scanned, media, missing, omittedText, invalidTime,
                cursor >= 0 || omittedText > 0))
    }
}
