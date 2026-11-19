package dev.jev.wechatmood.hook

import dev.jev.wechatmood.voice.*
import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VoicePreparationTest {
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
        assertEquals(48000, prepared.messages.sumOf { it.text.length })
        assertEquals(60L, prepared.messages.last().id)
        assertTrue(prepared.trimmed)
        assertTrue(prepared.messages.all { it.voiceState == VoiceState.READY })
        assertNotEquals(context.fingerprint, prepared.fingerprint)
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

    @Test fun exhaustedAnalysisBudgetNeverStartsOlderVoiceWorkOrCountsDiscardedFailures() = runBlocking {
        val context = (1L..24L).map { ContextMessage("对方", VoiceText.WAITING, it * 1000, it, source(it)) }
        val calls = mutableListOf<Long>()
        val prepared = VoicePreparation.analysis(AnalysisInput("好的", "friend", context)) {
            calls += it.id
            if (it.id <= 12) error("older unavailable voice")
            "字".repeat(1000)
        }
        assertEquals((24L downTo 13L).toList(), calls)
        assertEquals((13L..24L).toList(), prepared.context.map { it.messageId })
        assertEquals(0, prepared.coverage.unavailableVoice)
        assertTrue(prepared.coverage.truncated)
    }

    @Test fun voiceIdentityIncludesFileTimeAndDirectionForAnalysisCache() {
        val input = AnalysisInput(VoiceText.WAITING, "friend", messageId = 1, voice = source(1))
        assertNotEquals(input.key, input.copy(voice = source(1).copy(fileToken = "other-account-file")).key)
        assertNotEquals(input.key, input.copy(voice = source(1).copy(time = 2000)).key)
        assertNotEquals(input.key, input.copy(voice = source(1).copy(sent = 1)).key)
        assertFalse(source(1).toString().contains("file-1"))
    }
}
