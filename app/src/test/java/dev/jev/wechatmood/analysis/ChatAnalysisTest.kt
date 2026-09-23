package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ChatAnalysisTest {
    private val input = AnalysisInput("所以呢？", "friend")

    @Test fun `normal analysis sends two sequential requests and second sees first result`() {
        val calls = mutableListOf<JSONObject>()
        val result = ChatAnalysis.analyze(input, "test", { body ->
            calls += body
            if (calls.size == 1) JevFixtures.reply(body)
            else JevFixtures.reply(body, mapOf("focus" to "promise_action"))
        })
        assertEquals(2, calls.size)
        assertEquals("act", calls[1].getJSONObject("state").getJSONObject("first_pass").getJSONObject("progress").getString("choice"))
        assertTrue(result.detail.contains("什么时候做"))
    }

    @Test fun `unrelated or uncertain scene stops after first call`() {
        for (uncertain in listOf(false, true)) {
            var calls = 0
            val result = ChatAnalysis.analyze(input, "test", { body ->
                calls++
                val response = JSONObject(JevFixtures.reply(body, mapOf("scene" to if (uncertain) "promise" else "other")))
                if (uncertain) response.getJSONObject("answers").getJSONObject("scene").put("confidence", 0.1)
                response.toString()
            })
            assertEquals(1, calls)
            assertEquals(2, result.detail.lines().size)
            assertTrue(result.detail.contains("情绪："))
            assertFalse(result.detail.contains("建议："))
        }
    }

    @Test fun `leaving the screen between rounds prevents the next paid request`() {
        var visible = true
        var calls = 0
        assertThrows(CancellationException::class.java) {
            ChatAnalysis.analyze(input, "test", { body ->
                calls++; visible = false; JevFixtures.reply(body)
            }, { visible })
        }
        assertEquals(1, calls)
    }

    @Test fun `second round failure is not returned as a successful partial card`() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            ChatAnalysis.analyze(input, "test", { body ->
                calls++
                if (calls == 2) error("model unavailable")
                JevFixtures.reply(body)
            })
        }
        assertEquals(2, calls)
    }
}
