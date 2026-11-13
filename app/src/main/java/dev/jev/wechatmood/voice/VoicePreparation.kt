package dev.jev.wechatmood.voice

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.core.MessagePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object VoicePreparation {
    /** 一次最多转写这么多条上下文语音；其余直接占位，群里几十条语音不会把界面拖住。 */
    private const val MAX_CONTEXT_TRANSCRIPTS = 3
    private const val SKIPPED_VOICE = "【语音，未转写】"

    suspend fun analysis(input: AnalysisInput, transcribe: suspend (VoiceSource) -> String): AnalysisInput {
        currentCoroutineContext().ensureActive()
        val target = input.voice?.let {
            requireNotNull(MessagePolicy.textOrNull(transcribe(it))) { "语音转写为空或超过 1000 字符，无法分析" }
        } ?: input.text
        var budget = MessagePolicy.MAX_CONTEXT_CHARACTERS - (input.quoted?.text?.length ?: 0)
        var voiceBudget = MAX_CONTEXT_TRANSCRIPTS
        val context = input.context.asReversed().mapNotNull { message ->
            currentCoroutineContext().ensureActive()
            if (budget <= 0) return@mapNotNull null
            if (message.voice != null && voiceBudget <= 0)
                return@mapNotNull message.copy(text = SKIPPED_VOICE, voiceState = VoiceState.FAILED)
            if (message.voice != null) voiceBudget--
            val prepared = if (message.voice == null) message else try {
                val text = requireNotNull(MessagePolicy.textOrNull(transcribe(message.voice)))
                message.copy(text = text, voiceState = VoiceState.READY)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                message.copy(text = VoiceText.FAILED, voiceState = VoiceState.FAILED)
            }
            val size = prepared.text.length + (prepared.quoted?.text?.length ?: 0)
            if (size > budget) { budget = 0; null }
            else { budget -= size; prepared }
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
        var voiceBudget = MAX_CONTEXT_TRANSCRIPTS
        val messages = context.messages.asReversed().mapNotNull { message ->
            currentCoroutineContext().ensureActive()
            if (budget <= 0) { trimmed = true; return@mapNotNull null }
            // 只有最近几条语音会真的转写，更早的直接标成未转写，避免串行等几十个 30 秒超时。
            if (message.voice != null && voiceBudget <= 0) {
                val rest = message.copy(text = SKIPPED_VOICE, voiceState = VoiceState.FAILED)
                budget -= rest.text.length
                return@mapNotNull rest
            }
            if (message.voice != null) voiceBudget--
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
