package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class LlmAnalysisTransportTest {
    private val input = AnalysisInput("先休息了", "fictional", messageId = 1, background = ContactBackground("虚构朋友", "v1"))
    private val valid = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"emotion\":\"疲惫\",\"intent\":\"可能想休息\",\"concern\":\"无法判断\",\"tone\":\"文字表达疲惫\"}"}}]}"""
    private fun config(url: String, source: EmotionSource = EmotionSource.LLM) = RuntimeSettings(1, false,
        ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, ""), "install", intent = IntentSettings(IntentRoute.JEV,
            ReplySettings.fromInput(url, "fake", "test-model")), emotion = EmotionSettings(source))
    @Test fun `real HTTP pure LLM never calls JEV and errors retry with a fresh request`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}")); server.enqueue(MockResponse().setBody(valid))
            val settings = config(server.url("/v1").toString())
            val client = ReplyHttpClient()
            suspend fun run() = AnalysisRouter.analyze(input, settings, { error("Unexpected JEV request") },
                { cfg, payload -> client.request(cfg, payload) { it } })
            try { run(); fail("Malformed response must fail") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("不完整")) }
            assertTrue(run().detail.contains("LLM 定性"))
            assertEquals(2, server.requestCount)
            repeat(2) {
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/v1/chat/completions", request.path)
                val payload = org.json.JSONObject(request.body.readUtf8())
                assertFalse(payload.has("questions")); assertTrue(payload.toString().contains("虚构朋友"))
            }
        }
    }
    @Test fun `transport timeout is truthful and cancellation releases network`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val settings = config(server.url("/v1").toString())
            val http = OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).build()
            try {
                AnalysisRouter.analyze(input, settings, { error("JEV") }, { cfg, payload -> ReplyHttpClient(http).request(cfg, payload) { it } })
                fail("Timeout must fail")
            } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("超时")) }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = ReplyHttpClient()
            val job = launch {
                AnalysisRouter.analyze(input, settings, { error("JEV") }, { cfg, payload -> client.request(cfg, payload) { it } })
                fail("Canceled request cannot return a result")
            }
            withTimeout(2000) { while (server.requestCount < 2) delay(10) }
            job.cancelAndJoin(); assertTrue(job.isCancelled)
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
    @Test fun `background changes reject old asynchronous completions and use new cache key`() = runBlocking {
        MoodStore.clear()
        var current = input
        val finish = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val queue = AnalysisQueue(scope, { it.key == current.key }, { finish.await(); LlmEmotionProtocol.parse(valid) })
        try {
            queue.submit(input)
            current = input.copy(background = ContactBackground("新背景", "v2"))
            finish.complete(Unit); yield()
            assertNull(MoodStore.get(input.key))
            queue.submit(current); yield()
            assertNotNull(MoodStore.get(current.key))
        } finally { scope.cancel(); MoodStore.clear() }
    }
}
