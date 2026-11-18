package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftCheckTest {
    private fun body(content: String): String = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", content)))).toString()

    @Test fun `three labelled lines are parsed`() {
        val check = DraftCheckProtocol.parse(body(
            "判断：没接住对方问的电脑配置\n问题：没回应对方的问题；语气偏冷\n改写：星辰云电脑？没搞过"))
        assertEquals(listOf("没回应对方的问题", "语气偏冷"), check.issues)
        assertEquals("星辰云电脑？没搞过", check.rewrite)
        assertEquals("没接住对方问的电脑配置", check.verdict)
    }

    @Test fun `no problem still keeps a rewrite`() {
        val check = DraftCheckProtocol.parse(body("判断：回应到位\n问题：无\n改写：好呀，我看看"))
        assertTrue(check.issues.isEmpty())
        assertEquals("好呀，我看看", check.rewrite)
        assertTrue(!check.isEmpty)
    }

    @Test fun `half width colon and extra lines are tolerated`() {
        val check = DraftCheckProtocol.parse(body("判断: 图片是海边剪影\n问题: 无\n改写: 这张挺好看的\n补充说明"))
        assertEquals("图片是海边剪影", check.verdict)
        assertNull(check.issues.firstOrNull())
        assertEquals("这张挺好看的", check.rewrite)
    }

    @Test fun `missing rewrite becomes null`() {
        val check = DraftCheckProtocol.parse(body("判断：还行\n问题：无\n改写：无"))
        assertNull(check.rewrite)
    }

    @Test fun `prose without labels still yields a verdict`() {
        val check = DraftCheckProtocol.parse(body("这句话和当前话题无关"))
        assertTrue(check.issues.isEmpty())
        assertNull(check.verdict)
    }

    @Test fun `malformed body yields an empty result`() {
        assertTrue(DraftCheckProtocol.parse("not json").isEmpty)
        assertTrue(DraftCheckProtocol.parse(body("")).isEmpty)
    }

    @Test fun `cache hits only for the same draft within the window`() {
        val cache = DraftCheckCache(windowMs = 1000)
        val check = DraftCheck(listOf("太冷"), null)
        cache.put("t1", "行吧", check, now = 1000)
        assertEquals(check, cache.get("t1", "行吧", now = 1500))
        assertNull(cache.get("t1", "行吧", now = 2500))
        assertNull(cache.get("t1", "好的", now = 1500))
    }

    @Test fun `payload keeps focus and recent messages`() {
        val settings = ReplySettings.fromInput("https://example.com/v1/chat/completions", "key", "model")
        val context = ReplyContext("talker@chatroom", listOf(
            ReplyMessage(1, "对方", 1000, "这周末有空吗"),
            ReplyMessage(2, "我", 2000, "看情况"),
        ), focusMessageId = 1, focusText = "这周末有空吗")
        val payload = DraftCheckProtocol.payload(settings, context, "今天天气不错", ReplyRelationship.FRIEND)
        val evidence = JSONObject(payload.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("今天天气不错", evidence.getString("draft"))
        assertEquals("这周末有空吗", evidence.getJSONObject("focus").getString("text"))
        assertEquals(2, evidence.getJSONArray("messages").length())
        assertTrue(!payload.has("response_format"))
    }

    @Test fun `responded line is parsed for target check`() {
        val check = DraftCheckProtocol.parse(body(
            "回应：星辰云电脑你们有在搞么\n判断：没接住\n问题：无\n改写：没搞过"))
        assertEquals("星辰云电脑你们有在搞么", check.responded)
    }

    @Test fun `simple mode returns just a rewrite`() {
        val rewrite = DraftCheckProtocol.simpleParse(body("「这张挺好看的」"))
        assertEquals("这张挺好看的", rewrite)
    }

    @Test fun `simple mode drops blank output`() {
        assertNull(DraftCheckProtocol.simpleParse(body("   ")))
    }
}
