package dev.jev.wechatmood.analysis

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import dev.jev.wechatmood.core.ApiSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class JevHttpClientTest {
    private fun withServer(code: Int, body: String, block: (ApiSettings) -> Unit) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setBody(body).setHeader("Location", "/redirected"))
            server.start()
            block(ApiSettings.fromInput(server.url("/jev").toString(), "fake-private-key", "custom", "custom-jev"))
            val exchange = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", exchange.method)
            assertEquals("Bearer fake-private-key", exchange.getHeader("Authorization"))
            val request = JSONObject(exchange.body.readUtf8())
            assertEquals("custom-jev", request.getString("model"))
            assertTrue(request.has("state"))
            assertTrue(request.has("questions"))
            assertEquals(1, server.requestCount)
        }
    }

    private val payload get() = JSONObject().put("model", "custom-jev").put("state", "sample").put("questions", JSONObject())

    @Test fun `compatible answer preserves all probability and confidence fields`() {
        val body = """{"answers":{"emotion":{"type":"choice","choice":"happy","probabilities":{"happy":1.0},"confidence":1.0}}}"""
        withServer(200, body) { settings ->
            assertEquals(body, JevHttpClient().exchange(payload, settings))
        }
    }

    @Test fun `Vercel verification error explains the required account action`() {
        withServer(403, """{"error":{"type":"customer_verification_required","message":"private body fake-private-key"}}""") { settings ->
            val error = assertThrows(IllegalStateException::class.java) { JevHttpClient().exchange(payload, settings) }
            assertTrue(error.message!!.contains("绑定信用卡"))
            assertFalse(error.message!!.contains("fake-private-key"))
        }
    }

    @Test fun `quota authentication rate limit and model errors are actionable without echoing response`() {
        for ((code, expected) in mapOf(401 to "密钥", 402 to "额度", 429 to "频繁", 404 to "接口地址或模型", 500 to "暂不可用")) {
            withServer(code, "private body fake-private-key") { settings ->
                val error = assertThrows(IllegalStateException::class.java) { JevHttpClient().exchange(payload, settings) }
                assertTrue(error.message!!.contains(expected))
                assertTrue(error.message!!.contains("HTTP $code"))
                assertFalse(error.message!!.contains("fake-private-key"))
            }
        }
    }

    @Test fun `redirect is rejected instead of forwarding a paid analysis`() {
        withServer(302, "redirect") { settings ->
            val error = assertThrows(IllegalStateException::class.java) { JevHttpClient().exchange(payload, settings) }
            assertTrue(error.message!!.contains("重定向"))
        }
    }

    @Test fun `error envelope in HTTP 200 is not a successful model call`() {
        withServer(200, """{"error":{"code":402,"message":"fake-private-key"}}""") { settings ->
            val error = assertThrows(IllegalStateException::class.java) { JevHttpClient().exchange(payload, settings) }
            assertTrue(error.message!!.contains("额度"))
        }
    }
}
