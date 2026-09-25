package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class ManualAnalysisTest {
    private val message = AnalysisInput("好的", "alice", messageId = 42)

    @Test fun `manual choice authorizes only selected message without saving chat opt in`() {
        val chats = ConversationSwitches(emptySet()) { error("Manual analysis must not save switches") }
        val manual = ManualAnalysis()
        assertFalse(manual.allows(message, chats.isEnabled(message.talker)))
        assertTrue(manual.select(message))
        assertTrue(manual.allows(message, chats.isEnabled(message.talker)))
        assertFalse(chats.isEnabled(message.talker))
        assertFalse(manual.allows(message.copy(messageId = 43), false))
        assertFalse(manual.allows(message.copy(talker = "group@chatroom"), false))
        assertTrue(manual.allows(message.copy(messageId = 43), true))
    }

    @Test fun `returning to a message preserves its chosen input despite loaded context changes`() {
        val manual = ManualAnalysis()
        val chosen = message.copy(context = listOf(ContextMessage("我", "明天见？")))
        manual.select(chosen)
        assertEquals(chosen, manual.selectedInput(message))
        manual.select(message)
        assertEquals(chosen.key, manual.selectedInput(message)?.key)
        assertNull(manual.selectedInput(message.copy(text = "变了")))
        assertNull(ManualAnalysis().selectedInput(chosen))
    }

    @Test fun `multiple selections stay independent and invalid identities fail closed`() {
        val manual = ManualAnalysis()
        assertTrue(manual.select(message))
        assertTrue(manual.select(message.copy(messageId = 43)))
        assertTrue(manual.allows(message, false))
        assertTrue(manual.allows(message.copy(messageId = 43), false))
        for (invalid in listOf(message.copy(messageId = 0), message.copy(talker = ""),
            message.copy(text = " "), message.copy(text = "字".repeat(1001)))) {
            assertFalse(manual.select(invalid))
            assertNull(manual.selectedInput(invalid))
        }
    }
}
