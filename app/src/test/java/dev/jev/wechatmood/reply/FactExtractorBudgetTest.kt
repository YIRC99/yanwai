package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽取的预算与失败语义。
 *
 * 这两条都来自实际日志：素材超长时截掉的是最新消息；推理模型把预算吃光后正文为空，
 * 旧代码把它当成「没有新事实」，顺手把水位推进，这批消息再也不会重抽。
 */
class FactExtractorBudgetTest {
    private fun envelope(content: String, finish: String = "stop") =
        """{"choices":[{"message":{"content":${JSONObject.quote(content)}},"finish_reason":"$finish"}]}"""

    private fun settings(server: MockWebServer) =
        ReplySettings.fromInput(server.url("/v1/chat/completions").toString(), "sk-test", "test-model")

    private val now = 1_700_000_000_000L

    @Test fun `long transcript keeps the newest messages`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("[]")))
        server.start()
        try {
            // 单条正文会被 take(80)，所以用长署名把每行撑到 170 字符左右：
            // 45 条 × 170 ≈ 7600，超过 6000 预算，但仍在 takeLast(50) 的范围里。
            val speaker = "说".repeat(60)
            val messages = (1..45).map { i ->
                ReplyMessage(i.toLong(), speaker, now + i * 60_000, "第${i}句#" + "长".repeat(200))
            }
            runBlocking { FactExtractor().extract(settings(server), messages, now = now) }
            val body = server.takeRequest().body.readUtf8()
            assertTrue("最新一句必须进素材", body.contains("第45句#"))
            assertFalse("超出预算时不该留下最旧那句、把最新的挤掉", body.contains("第1句#"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `empty content retries once with a larger budget`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("", "length")))
        server.enqueue(MockResponse().setBody(envelope(
            """[{"kind":"约定","text":"周六帮他搬家","occurred_at":"2026-11-22T09:00"}]""")))
        server.start()
        try {
            val facts = runBlocking {
                FactExtractor().extract(settings(server),
                    listOf(ReplyMessage(1, "对方", now, "周六帮我搬个家")), now = now)
            }
            assertEquals(1, facts.size)
            assertEquals(2, server.requestCount)
            val first = JSONObject(server.takeRequest().body.readUtf8()).optInt("max_tokens")
            val second = JSONObject(server.takeRequest().body.readUtf8()).optInt("max_tokens")
            assertEquals(6000, first)
            assertEquals(8000, second)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `empty content twice is a failure rather than an empty result`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(envelope("", "length")))
        server.enqueue(MockResponse().setBody(envelope("", "length")))
        server.start()
        try {
            // 抛错才算失败：调用方据此不推进水位，不会把这批消息当成「闲聊」丢掉。
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    FactExtractor().extract(settings(server),
                        listOf(ReplyMessage(1, "对方", now, "周六帮我搬个家")), now = now)
                }
            }
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
