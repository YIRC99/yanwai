package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class TopicTest {
    private val context = ReplyContext("a", listOf(ReplyMessage(1, "对方", 1700000000000, "喜欢摄影")), requestedMessages = 30)
    private val relationship = ReplyRelationship.FRIEND
    private val time = TopicTimeContext(ZonedDateTime.parse("2026-09-26T20:30:00+08:00[Asia/Shanghai]"), "八月十六",
        listOf(TopicOccasion("国庆节", "2026-10-01", 5)))
    private val topics = (1..5).map { TopicSuggestion("摄影话题$it", "最近有想拍的地方$it 吗？", "从共同兴趣切入") }
    private fun key(notes: String = "喜欢摄影") = TopicKey(context.fingerprint, context.requestedMessages, relationship, notes, "", time.date)
    private fun composition() = ReplyComposition().apply { historyLimit = 30; this.relationship = this@TopicTest.relationship }
    private fun envelope(content: String, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", content)))).toString()
    private fun body() = JSONObject().put("topics", JSONArray(topics.map {
        JSONObject().put("title", it.title).put("opener", it.opener).put("reason", it.reason)
    })).toString()

    @Test fun `five topics are shown once before requesting another batch including after reopen`() {
        var c = composition()
        assertTrue(c.acceptTopics(context, topics, key(), null))
        val shown = mutableSetOf(c.selectedText)
        repeat(4) {
            c = ReplyComposition(c.result)
            assertTrue(c.nextTopic(key()))
            assertTrue(shown.add(c.selectedText))
        }
        assertEquals(5, shown.size)
        assertFalse(c.nextTopic(key()))
        assertEquals(5, c.result!!.topics!!.shownCount)
        assertEquals(5, c.result!!.topics!!.items.size)
        assertEquals(1, c.result!!.suggestion.parts.size)
    }

    @Test fun `changed evidence role notes draft date or limit cannot reuse cached topics`() {
        val c = composition()
        c.acceptTopics(context, topics, key(), null)
        listOf(key("生日快到了"), key().copy(fingerprint = "b"), key().copy(relationship = ReplyRelationship.PARTNER),
            key().copy(draft = "新的草稿"), key().copy(date = "2026-09-27"), key().copy(limit = 50)).forEach {
            assertFalse(c.nextTopic(it))
        }
        assertEquals(1, c.result!!.topics!!.shownCount)
        assertFalse(c.acceptTopics(context.copy(talker = "other"), topics, key(), null))
        c.relationship = ReplyRelationship.PARTNER
        assertFalse(c.acceptTopics(context, topics, key(), null))
    }

    @Test fun `reply and topic results preserve separate semantics and failed refresh retains last topic`() {
        val c = composition()
        c.acceptTopics(context, topics, key(), null)
        val old = c.result
        assertFalse(c.acceptTopics(context, topics, key().copy(limit = 50), null))
        assertSame(old, c.result)
        assertTrue(c.accept(context, ReplySuggestion("好呀", "回复"), "", null, relationship))
        assertNull(c.result!!.topics)
        assertFalse(c.nextTopic(key()))
    }

    @Test fun `topic request includes actual clock calendar identity evidence notes and previous batch`() {
        val config = ReplySettings.fromInput("https://example.com", "key", "model")
        val payload = TopicProtocol.payload(config, context, "想约看展", "她10月3日生日，喜欢摄影", "参考资料", relationship, time, topics)
        val messages = payload.getJSONArray("messages")
        val evidence = JSONObject(messages.getJSONObject(1).getString("content"))
        assertEquals("find_topics", evidence.getString("task"))
        assertEquals(5, evidence.getInt("topic_count"))
        assertEquals("friend", evidence.getJSONObject("relationship").getString("id"))
        assertEquals("她10月3日生日，喜欢摄影", evidence.getString("direction"))
        assertEquals(30, evidence.getInt("requested_message_count"))
        assertEquals(1, evidence.getInt("actual_message_count"))
        val calendar = evidence.getJSONObject("calendar")
        assertEquals("2026-09-26", calendar.getString("date"))
        assertEquals("Asia/Shanghai", calendar.getString("timezone"))
        assertEquals("星期六", calendar.getString("weekday"))
        assertTrue(calendar.getBoolean("weekend"))
        assertEquals("八月十六", calendar.getString("lunar_date"))
        assertEquals("国庆节", calendar.getJSONArray("occasions").getJSONObject(0).getString("name"))
        assertEquals(5, evidence.getJSONArray("avoid_topics").length())
        assertFalse(messages.getJSONObject(0).getString("content").contains("数组是同一轮连续消息"))
    }

    @Test fun `fixed calendar occasions cross year without inventing vacation schedules`() {
        val now = ZonedDateTime.parse("2026-12-28T23:50:00+08:00[Asia/Shanghai]")
        val occasions = TopicTimeContext.fixedOccasions(now.toLocalDate())
        assertTrue(occasions.contains(TopicOccasion("元旦", "2027-01-01", 4)))
        assertTrue(occasions.all { it.daysAway in 0..14 })
    }

    @Test fun `strict topic parser rejects duplicates nonstrings partial batches and truncated responses`() {
        assertEquals(topics, TopicProtocol.parse(envelope(body())))
        listOf("{}", "{\"topics\":[]}", body().replace("摄影话题2", "摄影话题1"),
            body().replace("最近有想拍的地方2 吗？", "最近有想拍的地方1 吗？"),
            body().replace("\"摄影话题1\"", "12"), body().replace("\"摄影话题1\"", "\"\""),
            body().replace("摄影话题1", "字".repeat(101))).forEach {
            assertThrows(IllegalStateException::class.java) { TopicProtocol.parse(envelope(it)) }
        }
        assertThrows(IllegalStateException::class.java) { TopicProtocol.parse(envelope(body(), "length")) }
    }

    @Test fun `one http request supplies a full local cycle and another only after exhaustion`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(envelope(body()))) }
            val config = ReplySettings.fromInput(server.url("/v1").toString(), "secret", "m")
            val client = ReplyHttpClient()
            val c = composition()
            suspend fun next() {
                if (!c.nextTopic(key())) c.acceptTopics(context,
                    client.findTopics(config, context, "", key().notes, "资料", relationship, time, c.result?.topics?.items.orEmpty()), key(), null)
            }
            repeat(5) { next() }
            assertEquals(1, server.requestCount)
            next()
            assertEquals(2, server.requestCount)
            val request = server.takeRequest()
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertEquals("find_topics", JSONObject(JSONObject(request.body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")).getString("task"))
        }
    }
}
