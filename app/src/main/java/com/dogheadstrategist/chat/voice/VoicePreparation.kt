package com.dogheadstrategist.chat.voice

import com.dogheadstrategist.chat.reply.ReplyContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object VoicePreparation {
    suspend fun reply(context: ReplyContext, transcribe: suspend (VoiceSource) -> String): ReplyContext {
        var budget = ReplyContext.MAX_CHARACTERS
        var trimmed = context.trimmed
        val messages = context.messages.asReversed().mapNotNull { message ->
            currentCoroutineContext().ensureActive()
            if (budget <= 0) { trimmed = true; return@mapNotNull null }
            val prepared = if (message.voice == null) message else try {
                val text = transcribe(message.voice).trim()
                check(text.isNotBlank())
                message.copy(text = text, voiceState = VoiceState.READY)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.copy(text = VoiceText.FAILED, voiceState = VoiceState.FAILED) }
            val end = minOf(budget, prepared.text.length).let {
                if (it < prepared.text.length && it > 0 && prepared.text[it - 1].isHighSurrogate() && prepared.text[it].isLowSurrogate()) it - 1 else it
            }
            if (end < prepared.text.length) trimmed = true
            budget -= end
            prepared.copy(text = prepared.text.take(end))
        }.asReversed()
        currentCoroutineContext().ensureActive()
        return context.copy(messages = messages, trimmed = trimmed)
    }
}
