package dev.jev.wechatmood.hook

import dev.jev.wechatmood.voice.*
import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoicePreparationTest {
    private val MAX_CONTEXT_TRANSCRIPTS_EXPECTED = 3
    private fun source(id: Long) = VoiceSource("friend", id, id * 1000, 0, "file-$id", id + 100)
    @Test fun targetAndPrecedingVoiceAreResolvedBeforeAnalysisWithOriginalIdentity() = runBlocking {
        val input = AnalysisInput(VoiceText.WAITING, "friend",
            listOf(ContextMessage("我", VoiceText.WAITING, 1000, 1, source(1))), 2, createdAt = 2000, voice = source(2))
        val prepared = VoicePreparation.analysis(input) { "转写${it.id}" }
        assertEquals("转写2", prepared.text)
        assertEquals("转写1", prepared.context.single().text)
        assertEquals(VoiceState.READY, prepared.voiceState)
        assertEquals(VoiceState.READY, prepared.context.single().voiceState)
        assertEquals(input.messageId, prepared.messageId)
        val state = dev.jev.wechatmood.analysis.AnalysisState.build(prepared)
        assertEquals("voice_transcript", state.getString("message_source"))
    }
    @Test fun missingContextVoiceIsExplicitAndTargetFailureNeverAnalyzesPlaceholder() = runBlocking {
        val input = AnalysisInput("明天见", "friend", listOf(ContextMessage("对方", VoiceText.WAITING, 1000, 1, source(1))))
        val prepared = VoicePreparation.analysis(input) { error("unavailable") }
        assertEquals(1, prepared.coverage.unavailableVoice)
        assertEquals(VoiceState.FAILED, prepared.context.single().voiceState)
        assertEquals(VoiceText.FAILED, prepared.context.single().text)
        try {
            VoicePreparation.analysis(input.copy(voice = source(2), voiceState = VoiceState.WAITING)) { error("unavailable") }
            fail("must stop")
        } catch (_: IllegalStateException) { }
    }
    @Test fun cancellationIsNotConvertedToMissingEvidence() = runBlocking {
        try {
            VoicePreparation.reply(ReplyContext("friend", listOf(ReplyMessage(1, "对方", 1000, VoiceText.WAITING, source(1))))) {
                throw CancellationException("leave chat")
            }
            fail("must cancel")
        } catch (_: CancellationException) { }
    }
    @Test fun voiceReplyBudgetIsAppliedAfterTranscriptionAndKeepsNewestMessage() = runBlocking {
        val context = ReplyContext("friend", (1L..60L).map { ReplyMessage(it, "对方", it * 1000, VoiceText.WAITING, source(it)) })
        val prepared = VoicePreparation.reply(context) { "字".repeat(1000) }
        // 只有最近三条语音真的会转写，其余保留占位；最新一条必须完整留在上下文里。
        assertEquals(MAX_CONTEXT_TRANSCRIPTS_EXPECTED, prepared.messages.count { it.voiceState == VoiceState.READY })
        assertEquals(60L, prepared.messages.last().id)
        assertEquals("字".repeat(1000), prepared.messages.last().text)
        assertTrue(prepared.messages.size <= ReplyContext.MAX_MESSAGES)
        assertNotEquals(context.fingerprint, prepared.fingerprint)
    }

    @Test fun selectedVoiceIsTranscribedBeforeNewerContextVoices() = runBlocking {
        val messages = listOf(
            ReplyMessage(1, "对方", 1000, VoiceText.WAITING, source(1)),
            ReplyMessage(2, "对方", 2000, VoiceText.WAITING, source(2)),
            ReplyMessage(3, "对方", 3000, VoiceText.WAITING, source(3)),
            ReplyMessage(4, "对方", 4000, VoiceText.WAITING, source(4)))
        val calls = mutableListOf<Long>()
        val prepared = VoicePreparation.reply(ReplyContext("friend", messages, focusMessageId = 1)) {
            calls += it.id
            "转写${it.id}"
        }
        // 用户长按的 1 号语音排在三条较新语音之前，不能被转写预算挤掉。
        assertTrue(calls.contains(1L))
        assertEquals("转写1", prepared.messages.first { it.id == 1L }.text)
        assertEquals(VoiceState.READY, prepared.messages.first { it.id == 1L }.voiceState)
    }
    @Test fun replyFailurePreservesTimelineAndIsDisclosedToModel() = runBlocking {
        val context = ReplyContext("friend", listOf(ReplyMessage(1, "我", 1000, VoiceText.WAITING, source(1)),
            ReplyMessage(2, "对方", 2000, "收到")))
        val prepared = VoicePreparation.reply(context) { error("gone") }
        assertEquals(listOf(1L, 2L), prepared.messages.map { it.id })
        val evidence = ReplyProtocol.evidence(prepared, "", "")
        assertEquals(1, evidence.getInt("unavailable_voice"))
        assertEquals("FAILED", evidence.getJSONArray("messages").getJSONObject(0).getString("voice_state"))
        assertFalse(evidence.toString().contains("file-1"))
    }
    @Test fun pendingVoiceCannotAccidentallyReachEitherModelProtocol() {
        assertThrows(IllegalArgumentException::class.java) {
            dev.jev.wechatmood.analysis.AnalysisState.build(AnalysisInput(VoiceText.WAITING, "friend", voice = source(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReplyProtocol.evidence(ReplyContext("friend", listOf(ReplyMessage(1, "我", 1000, VoiceText.WAITING, source(1)))), "", "")
        }
    }

    @Test fun olderVoicesAreNotTranscribedOnceTheContextBudgetIsSpent() = runBlocking {
        val context = (1L..24L).map { ContextMessage("对方", VoiceText.WAITING, it * 1000, it, source(it)) }
        val calls = mutableListOf<Long>()
        val prepared = VoicePreparation.analysis(AnalysisInput("好的", "friend", context)) {
            calls += it.id
            if (it.id <= 12) error("older unavailable voice")
            "字".repeat(1000)
        }
        // 只转写最近的 3 条；更早的语音直接占位，不启动转写任务。
        assertEquals(listOf(24L, 23L, 22L), calls)
        assertEquals((1L..24L).toList(), prepared.context.map { it.messageId })
        assertEquals(0, prepared.coverage.unavailableVoice)
    }

    @Test fun voiceIdentityIncludesFileTimeAndDirectionForAnalysisCache() {
        val input = AnalysisInput(VoiceText.WAITING, "friend", messageId = 1, voice = source(1))
        assertNotEquals(input.key, input.copy(voice = source(1).copy(fileToken = "other-account-file")).key)
        assertNotEquals(input.key, input.copy(voice = source(1).copy(time = 2000)).key)
        assertNotEquals(input.key, input.copy(voice = source(1).copy(sent = 1)).key)
        assertFalse(source(1).toString().contains("file-1"))
    }
}
