package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class CustomRelationshipTest {
    private val context = ReplyContext("a", listOf(ReplyMessage(1, "对方", 1, "周末有空吗")))
    private val settings = ReplySettings.fromInput("https://example.com", "key", "m")
    private val time = TopicTimeContext(ZonedDateTime.parse("2026-09-26T12:00:00+08:00[Asia/Shanghai]"))
    private val topics = (1..5).map { TopicSuggestion("话题$it", "聊聊$it", "理由") }
    private fun input(payload: JSONObject) = JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"))

    @Test fun `custom role goes into user evidence for reply and topic without becoming system instructions`() {
        val label = "前同事，只聊工作"
        val payloads = listOf(
            ReplyProtocol.payload(settings, context, "", "", "", relationship = ReplyRelationship.OTHER, customRelationship = "  $label  "),
            TopicProtocol.payload(settings, context, "", "", "", ReplyRelationship.OTHER, time, customRelationship = "  $label  "))
        payloads.forEach {
            val role = input(it).getJSONObject("relationship")
            assertEquals("other", role.getString("id"))
            assertEquals(label, role.getString("label"))
            assertFalse(it.getJSONArray("messages").getJSONObject(0).getString("content").contains(label))
        }
        val preset = ReplyProtocol.payload(settings, context, "", "", "", relationship = ReplyRelationship.FRIEND, customRelationship = label)
        assertEquals("朋友", input(preset).getJSONObject("relationship").getString("label"))
        assertFalse(preset.toString().contains(label))
    }

    @Test fun `custom identity requires nonblank bounded text and normalizes whitespace`() {
        assertEquals("前同事 李哥", ReplyRelationship.OTHER.customValue("  前同事\n李哥  "))
        listOf("", "   ", "字".repeat(41)).forEach { label ->
            assertThrows(IllegalArgumentException::class.java) {
                ReplyProtocol.payload(settings, context, "", "", "", relationship = ReplyRelationship.OTHER, customRelationship = label)
            }
            assertThrows(IllegalArgumentException::class.java) {
                TopicProtocol.payload(settings, context, "", "", "", ReplyRelationship.OTHER, time, customRelationship = label)
            }
        }
    }

    @Test fun `custom changes invalidate previous result without relabeling it and reopen restores identity`() {
        val c = ReplyComposition().apply { relationship = ReplyRelationship.OTHER; customRelationship = "前同事" }
        assertTrue(c.hasValidRelationship)
        assertTrue(c.accept(context, ReplySuggestion("好呀", ""), "", null, ReplyRelationship.OTHER, "前同事"))
        c.customRelationship = "相亲对象"
        assertFalse(c.canUse)
        assertEquals("前同事", c.result!!.customRelationship)
        assertFalse(c.accept(context, ReplySuggestion("旧请求", ""), "", null, ReplyRelationship.OTHER, "前同事"))
        val reopened = ReplyComposition(c.result)
        assertEquals("前同事", reopened.customRelationship)
        assertTrue(reopened.canUse)
        c.customRelationship = "   "
        assertFalse(c.hasValidRelationship)
        c.relationship = ReplyRelationship.FRIEND
        assertTrue(c.hasValidRelationship)
        assertTrue(c.accept(context, ReplySuggestion("朋友回复", ""), "", null, ReplyRelationship.FRIEND))
        assertEquals("", c.result!!.customRelationship)
    }

    @Test fun `topic pool is bound to custom identity and resets only after fresh generation`() {
        val c = ReplyComposition().apply { relationship = ReplyRelationship.OTHER; customRelationship = "前同事" }
        val key = TopicKey(context.fingerprint, 100, ReplyRelationship.OTHER, "", "", time.date, "前同事")
        assertTrue(c.acceptTopics(context, topics, key, null))
        assertTrue(c.nextTopic(key))
        c.customRelationship = "相亲对象"
        assertFalse(c.nextTopic(key))
        assertFalse(c.nextTopic(key.copy(customRelationship = "相亲对象")))
        assertFalse(c.acceptTopics(context, topics, key, null))
        assertTrue(c.acceptTopics(context, topics, key.copy(customRelationship = "相亲对象"), null))
        assertEquals("相亲对象", ReplyComposition(c.result).customRelationship)
        assertEquals(1, c.result!!.topics!!.shownCount)
    }

    @Test fun `http sends typed role through both endpoints`() = runBlocking {
        MockWebServer().use { server ->
            fun envelope(content: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", content.toString())))).toString()
            server.enqueue(MockResponse().setBody(envelope(JSONObject().put("replies", JSONArray(listOf("好的"))).put("reason", ""))))
            server.enqueue(MockResponse().setBody(envelope(JSONObject().put("topics", JSONArray(topics.map {
                JSONObject().put("title", it.title).put("replies", JSONArray(it.parts)).put("reason", it.reason)
            })))))
            val config = ReplySettings.fromInput(server.url("/v1").toString(), "fake", "m")
            ReplyHttpClient().generate(config, context, "", "", "", relationship = ReplyRelationship.OTHER, customRelationship = "相亲对象")
            ReplyHttpClient().findTopics(config, context, "", "", "", ReplyRelationship.OTHER, time, customRelationship = "前同事")
            listOf("相亲对象", "前同事").forEach {
                assertEquals(it, input(JSONObject(server.takeRequest().body.readUtf8())).getJSONObject("relationship").getString("label"))
            }
        }
    }
}
