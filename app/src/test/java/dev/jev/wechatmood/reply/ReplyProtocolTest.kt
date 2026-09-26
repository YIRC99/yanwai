package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReplyProtocolTest {
    private val settings = ReplySettings.fromInput("https://example.com", "key", "test-model")
    private val context = ReplyContext("a", listOf(ReplyMessage(1, "对方", 1700000000000, "周末想去哪？")))

    @Test fun `protocol includes evidence draft and user direction separately from instructions`() {
        val payload = ReplyProtocol.payload(settings, context, "想看电影", "自然一点", "资料")
        assertEquals("test-model", payload.getString("model"))
        assertFalse(payload.getBoolean("stream"))
        val messages = payload.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        val input = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("想看电影", input.getString("draft"))
        assertEquals("自然一点", input.getString("direction"))
        assertEquals("对方", input.getJSONArray("messages").getJSONObject(0).getString("speaker"))
        assertTrue(input.getJSONArray("messages").getJSONObject(0).getString("time").contains("2023"))
    }

    @Test fun `valid structured reply parses but incomplete or refusal results fail closed`() {
        assertEquals("去看电影吧？", ReplyProtocol.parse(envelope("{\"reply\":\"去看电影吧？\",\"reason\":\"回应邀约\"}")).text)
        assertThrows(IllegalStateException::class.java) { ReplyProtocol.parse(envelope("", "stop")) }
        assertThrows(IllegalStateException::class.java) { ReplyProtocol.parse(envelope("半句话", "length")) }
        assertThrows(IllegalStateException::class.java) { ReplyProtocol.parse("{\"choices\":[]}") }
    }

    @Test fun `model sees whether evidence was expanded or limited to loaded page`() {
        for (source in ReplyContextSource.entries) {
            val payload = ReplyProtocol.payload(settings, context.copy(source = source), "", "", "")
            val evidence = JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(source.name, evidence.getString("context_source"))
            assertEquals(source == ReplyContextSource.LOADED_PAGE, evidence.getBoolean("page_only"))
            assertFalse(evidence.getBoolean("media_included"))
        }
    }

    @Test fun `short messages stay ordered and are not alternatives or one merged reply`() {
        val result = ReplyProtocol.parse(envelope("""{"replies":[" 好呀 ","周六下午怎么样？"],"reason":"先回应再确认时间"}"""))
        assertEquals(listOf("好呀", "周六下午怎么样？"), result.parts)
        assertEquals("好呀\n周六下午怎么样？", result.text)
        assertEquals(listOf("好的"), ReplyProtocol.parse(envelope("""{"reply":"好的"}""")).parts)
    }

    @Test fun `malformed segments never fall back to a misleading partial reply`() {
        listOf("[]", "[\"\"]", "[\"好的\",null]", "[\"好的\",3]", "[{}]", "\"好的\"",
            org.json.JSONArray(List(7) { "好" }).toString(),
            org.json.JSONArray(listOf("字".repeat(4000), "字".repeat(4001))).toString()
        ).forEach { parts ->
            assertThrows(IllegalStateException::class.java) {
                ReplyProtocol.parse(envelope("""{"replies":$parts,"reply":"不要静默退回这条"}"""))
            }
        }
    }

    @Test fun `http carries selected relationship and returns individual messages`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(envelope("""{"replies":["嗯好","你也早点休息"],"reason":"关心家人"}""")))
            val config = ReplySettings.fromInput(server.url("/v1").toString(), "secret", "m")
            val result = ReplyHttpClient().generate(config, context, "", "别太正式", "家庭资料",
                relationship = ReplyRelationship.YOUNGER_SIBLING)
            assertEquals(listOf("嗯好", "你也早点休息"), result.parts)
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            val input = JSONObject(messages.getJSONObject(1).getString("content"))
            assertEquals("younger_sibling", input.getJSONObject("relationship").getString("id"))
            assertEquals("弟弟妹妹", input.getJSONObject("relationship").getString("label"))
            assertTrue(messages.getJSONObject(0).getString("content").contains(ReplyRelationship.YOUNGER_SIBLING.guidance))
        }
    }

    @Test fun `http detects business errors and never returns raw sensitive service body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(envelope("{\"reply\":\"好的\",\"reason\":\"确认\"}")))
            val config = ReplySettings.fromInput(server.url("/v1").toString(), "secret", "m")
            val client = ReplyHttpClient()
            assertEquals("好的", client.generate(config, context, "", "", "").text)
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret 用户聊天"))
            val failure = runCatching { client.generate(config, context, "", "", "") }.exceptionOrNull()!!
            assertFalse(failure.message.orEmpty().contains("secret"))
            assertFalse(failure.message.orEmpty().contains("用户聊天"))
        }
    }

    private fun envelope(content: String, finish: String = "stop") = JSONObject().put("choices", org.json.JSONArray().put(
        JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", content)))).toString()
}
