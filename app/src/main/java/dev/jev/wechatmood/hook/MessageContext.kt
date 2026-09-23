package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.core.MessagePolicy

/** Reads only the bounded predecessor window of a visible target from its loaded adapter. */
object MessageContext {
    fun collect(message: MessageMetadata, position: Int, itemAt: (Int) -> MessageMetadata?): AnalysisInput? {
        val text = message.incomingText() ?: return null
        val context = ((position - MessagePolicy.MAX_CONTEXT_MESSAGES).coerceAtLeast(0) until position)
            .mapNotNull { index ->
                val previous = runCatching { itemAt(index) }.getOrNull() ?: return@mapNotNull null
                if (previous.talker != message.talker) return@mapNotNull null
                val previousText = previous.plainText() ?: return@mapNotNull null
                ContextMessage(previous.speaker(), previousText)
            }
        return AnalysisInput(text, message.talker, context, message.messageId, message.speaker())
    }
}
