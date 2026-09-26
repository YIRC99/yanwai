package dev.jev.wechatmood.voice

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.core.MessagePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object VoicePreparation {
    suspend fun analysis(input: AnalysisInput, transcribe: suspend (VoiceSource) -> String): AnalysisInput {
        currentCoroutineContext().ensureActive()
        val target = input.voice?.let {
            requireNotNull(MessagePolicy.textOrNull(transcribe(it))) { "语音转写为空或超过 1000 字符，无法分析" }
        } ?: input.text
        var budget = MessagePolicy.MAX_CONTEXT_CHARACTERS
        val context = input.context.asReversed().mapNotNull { message ->
            currentCoroutineContext().ensureActive()
            if (budget <= 0) return@mapNotNull null
            val prepared = if (message.voice == null) message else try {
                val text = requireNotNull(MessagePolicy.textOrNull(transcribe(message.voice)))
                message.copy(text = text, voiceState = VoiceState.READY)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                message.copy(text = VoiceText.FAILED, voiceState = VoiceState.FAILED)
            }
            if (prepared.text.length > budget) { budget = 0; null }
            else { budget -= prepared.text.length; prepared }
        }.asReversed()
        currentCoroutineContext().ensureActive()
        return input.copy(text = target, context = context,
            voiceState = if (input.voice == null) VoiceState.NONE else VoiceState.READY,
            coverage = input.coverage.copy(unavailableVoice = context.count { it.voiceState == VoiceState.FAILED },
                truncated = input.coverage.truncated || context.size != input.context.size))
    }

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
