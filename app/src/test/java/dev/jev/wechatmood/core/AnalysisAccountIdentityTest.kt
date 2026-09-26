package dev.jev.wechatmood.core

import dev.jev.wechatmood.hook.MessageMetadata
import dev.jev.wechatmood.voice.VoiceText
import org.junit.Assert.*
import org.junit.Test

class AnalysisAccountIdentityTest {
    @Test fun `account verification rejects stale database records including identical local ids`() {
        val input = AnalysisInput("你好", "alice", messageId = 42, createdAt = 1000)
        val record = MessageMetadata(1, 0, "你好", "alice", 42, 1000)
        assertTrue(AnalysisAccountIdentity.matches(input, record))
        listOf(record.copy(talker = "bob"), record.copy(isSend = 1), record.copy(createdAt = 2000),
            record.copy(content = "再见"), record.copy(messageId = 43), record.copy(type = 3)).forEach {
            assertFalse(AnalysisAccountIdentity.matches(input, it))
        }
        assertFalse(AnalysisAccountIdentity.matches(input.copy(createdAt = 0), record.copy(createdAt = 0)))
    }

    @Test fun `voice cache verifies audio identity even when both display the same placeholder`() {
        val record = MessageMetadata(34, 0, "", "alice", 42, 1000, "audio-a", 500)
        val input = AnalysisInput(VoiceText.WAITING, "alice", messageId = 42, createdAt = 1000, voice = record.voiceSource())
        assertTrue(AnalysisAccountIdentity.matches(input, record))
        assertFalse(AnalysisAccountIdentity.matches(input, record.copy(imagePath = "audio-b")))
        assertFalse(AnalysisAccountIdentity.matches(input, record.copy(serverId = 501)))
    }
}
