package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ReplyModelsTest {
    @Test fun `list uses sibling models route and key without requiring chosen model`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"chat-b"},{"id":"chat-a"},{"id":"chat-b"}]}"""))
            val settings = ReplySettings.fromInput(server.url("/api/v3").toString(), "secret", "")
            assertEquals(listOf("chat-a", "chat-b"), ReplyModelsClient().list(settings))
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/v3/models", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals(0, request.bodySize)
        }
    }

    @Test fun `catalog excludes explicit nontext models but retains unknown chat models`() {
        val body = """{"data":[{"id":"mimo-v2-tts"},{"id":"text-embedding-3-small"},
            {"id":"future-model"},{"id":"future-image","output_modalities":["image"]},
            {"id":"multimodal","output_modalities":["text","audio"]},{"id":"bad\nname"},{"id":123}]}"""
        assertEquals(listOf("future-model", "multimodal"), ReplyModelsClient.parse(body))
    }

    @Test fun `empty malformed and business errors fail explicitly`() {
        listOf("{}", "<html>login</html>", "{\"data\":[]}", "{\"error\":{\"message\":\"secret\"},\"data\":[{\"id\":\"x\"}]}")
            .forEach { body ->
                val error = assertThrows(IllegalStateException::class.java) { ReplyModelsClient.parse(body) }
                assertFalse(error.message.orEmpty().contains("secret"))
            }
    }

    @Test fun `redirect unauthorized and unsupported list do not leak remote response or follow redirects`() = runBlocking {
        MockWebServer().use { server ->
            for (code in listOf(302, 401, 404, 429, 500)) {
                server.enqueue(MockResponse().setResponseCode(code).addHeader("Location", server.url("/other"))
                    .setBody("secret response"))
                val settings = ReplySettings.fromInput(server.url("/v1").toString(), "secret", "")
                val error = runCatching { ReplyModelsClient().list(settings) }.exceptionOrNull()
                assertNotNull(error)
                assertFalse(error!!.message.orEmpty().contains("secret"))
            }
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun `large response is rejected before parsing`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(" ".repeat(1024 * 1024 + 1)))
            val error = runCatching { ReplyModelsClient().list(ReplySettings.fromInput(server.url("/v1").toString(), "key", "")) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message.orEmpty().contains("过大"))
        }
    }
}
