package dev.jev.wechatmood.analysis

import org.junit.Assert.*
import org.junit.Test

class JevQualityCasesTest {
    @Test fun `frozen cases cover sixteen pairs with valid labels and preserved legacy protocol`() {
        val cases = JevQualityCases.cases
        assertEquals(32, cases.size)
        assertEquals(32, cases.map { it.getString("id") }.toSet().size)
        assertEquals(16, cases.groupBy { it.getString("group") }.size)
        cases.forEach {
            assertTrue(JevQualityCases.strings(it.getJSONArray("emotions")).all { label -> label in JevProtocol.emotions })
            assertTrue(JevQualityCases.strings(it.getJSONArray("intents")).all { label -> label in ChatFacts.questions.getValue("speech_act").options })
            val input = JevQualityCases.input(it)
            val modern = JevProtocol.payload(input, "test")
            assertTrue(modern.getJSONObject("state").has("sent_at_ms"))
            val legacy = JevQualityCases.legacy(input, "test", false)
            assertEquals(9, legacy.getJSONObject("questions").length())
            assertFalse(legacy.getJSONObject("state").has("sent_at_ms"))
            assertTrue(legacy.getJSONObject("state").getJSONArray("context").length() <= 10)
        }
    }
}
