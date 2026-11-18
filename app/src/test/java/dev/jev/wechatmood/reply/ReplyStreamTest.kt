package dev.jev.wechatmood.reply

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 流式回复：第一个候选在整段 JSON 写完之前就要能被取出来显示。 */
class ReplyStreamTest {
    @Test fun `image rejection retries the same text evidence and streams the result`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400))
            val chunk = """{"choices":[{"delta":{"content":"{\"replies\":[\"好的\"],\"reason\":\"\"}"},"finish_reason":"stop"}]}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $chunk\n\ndata: [DONE]\n\n"))
            val settings = ReplySettings.fromInput(server.url("/v1").toString(), "fake", "model")
            val image = "data:image/jpeg;base64,AA=="
            val context = ReplyContext("chat", listOf(ReplyMessage(1, "对方", 1, "看看这个")),
                targetMedia = "图片", targetImageDataUri = image)
            val partials = mutableListOf<String>()
            val reply = ReplyHttpClient().generate(settings, context, "", "", "", onPartial = { partials.add(it) })

            assertEquals(listOf("好的"), partials)
            assertEquals("好的", reply.text)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            val retry = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(true, first.getBoolean("stream"))
            assertEquals(true, retry.getBoolean("stream"))
            val parts = first.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertEquals(image, parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
            assertEquals(parts.getJSONObject(0).getString("text"),
                retry.getJSONArray("messages").getJSONObject(1).getString("content"))
        }
    }

    @Test fun `complete first candidate is extracted from a partial payload`() {
        assertEquals("最推荐的候选", ReplyHttpClient.firstReply("{\"replies\":[\"最推荐的候选\""))
    }

    @Test fun `incomplete string yields nothing`() {
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[\"还没写完"))
        assertNull(ReplyHttpClient.firstReply("{\"reason\":\"没有候选\""))
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[]"))
    }

    @Test fun `escaped quotes and unicode survive`() {
        assertEquals("他说\"好\"", ReplyHttpClient.firstReply("{\"replies\":[\"他说\\\"好\\\"\",\"第二\"]}"))
        assertEquals("好", ReplyHttpClient.firstReply("{\"replies\":[\"\\u597d\"],\"reason\":\"x\"}"))
    }

    @Test fun `blank candidate is ignored`() {
        assertNull(ReplyHttpClient.firstReply("{\"replies\":[\"   \""))
    }
}
