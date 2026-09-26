package dev.jev.wechatmood.analysis

import org.junit.Assert.*
import org.junit.Test

class ChatTemplatesTest {
    private fun profile(scene: String, progress: String) = JevFixtures.profile(scene, progress)

    @Test fun `specialist cards and independent intent feelings have distinct complete options`() {
        assertEquals(43, ChatTemplates.all.size)
        assertEquals(43, ChatTemplates.all.map { it.id }.toSet().size)
        assertEquals(8, ChatTemplates.all.count { it.id.startsWith("intent_") })
        assertEquals(3, ChatTemplates.all.count { it.id.startsWith("feeling_") })
        ChatTemplates.all.filterNot { it.id.startsWith("intent_") || it.id.startsWith("feeling_") }
            .groupBy { it.scene }.values.forEach { assertEquals(4, it.size) }
        ChatTemplates.all.forEach {
            assertTrue(it.title.isNotBlank() && it.question.isNotBlank())
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
