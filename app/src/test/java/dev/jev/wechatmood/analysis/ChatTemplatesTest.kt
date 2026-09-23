package dev.jev.wechatmood.analysis

import org.junit.Assert.*
import org.junit.Test

class ChatTemplatesTest {
    private fun decision(value: String) = ChatDecision(value, mapOf(value to 1.0), 1.0)
    private fun profile(scene: String, progress: String) =
        ChatProfile(decision(scene), decision("calm"), decision(progress))

    @Test fun `eight scenes have four distinct complete cards each`() {
        assertEquals(32, ChatTemplates.all.size)
        assertEquals(32, ChatTemplates.all.map { it.id }.toSet().size)
        assertEquals(8, ChatTemplates.all.groupBy { it.scene }.size)
        ChatTemplates.all.groupBy { it.scene }.values.forEach { assertEquals(4, it.size) }
        ChatTemplates.all.forEach {
            assertTrue(it.title.isNotBlank() && it.question.isNotBlank())
            assertTrue(it.action.isNotBlank() && it.ordinaryAction.isNotBlank())
            assertEquals(setOf("signal", "ordinary", "unclear"), it.options.keys)
        }
    }

    @Test fun `waiting for action stops repeated explanations and accepted repair stops apologies`() {
        val action = ChatTemplates.candidates(profile("promise", "act")).map { it.id }
        assertTrue(action.contains("promise_action"))
        assertFalse(action.contains("promise_care"))
        assertFalse(action.contains("promise_detail"))
        val accepted = ChatTemplates.candidates(profile("repair", "accepted")).map { it.id }
        assertEquals(listOf("repair_accept"), accepted)
    }

    @Test fun `uncertain scene cannot select specialist templates`() {
        val uncertain = profile("promise", "act").copy(scene = ChatDecision("promise", mapOf("promise" to 0.4), 0.1))
        assertTrue(ChatTemplates.candidates(uncertain).isEmpty())
        assertTrue(ChatTemplates.candidates(profile("other", "unknown")).isEmpty())
    }
}
