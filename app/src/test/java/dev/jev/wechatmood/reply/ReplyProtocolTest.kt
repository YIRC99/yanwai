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
