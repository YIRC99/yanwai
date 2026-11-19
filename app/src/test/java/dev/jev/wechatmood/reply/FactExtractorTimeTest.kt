package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** 历史消息的相对日期要按每条消息自己的发送时间换算，不能按整理当天。 */
class FactExtractorTimeTest {
    private fun envelope(content: String) =
        """{"choices":[{"message":{"content":${JSONObject.quote(content)}},"finish_reason":"stop"}]}"""

    @Test fun `transcript carries each message own timestamp`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("[]")))
        server.start()
        try {
            val settings = ReplySettings.fromInput(server.url("/v1/chat/completions").toString(), "sk-test", "test-model")
            val monday = FactTimes.parseLocal("2026-11-16T10:00")
            runBlocking {
                FactExtractor().extract(settings, listOf(ReplyMessage(1, "对方", monday, "明天见")))
            }
            val request = server.takeRequest().body.readUtf8()
            // 周一 10:00 的「明天见」要能看出基准日期是 11-16，而不是整理当天。
            assertTrue(request, request.contains("11-16"))
            assertTrue(request, request.contains("明天见"))
            assertTrue(request, request.contains("以该条消息自己的发送时间为基准"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `messages with unknown time fall back to the current time`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("[]")))
        server.start()
        try {
            val settings = ReplySettings.fromInput(server.url("/v1/chat/completions").toString(), "sk-test", "test-model")
            runBlocking {
                FactExtractor().extract(settings, listOf(ReplyMessage(2, "对方", 0, "改天吃饭")))
            }
            val request = server.takeRequest().body.readUtf8()
            assertTrue(request, request.contains("时间未知"))
        } finally {
            server.shutdown()
        }
    }
}
