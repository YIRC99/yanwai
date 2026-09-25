package dev.jev.wechatmood.reply

import kotlinx.coroutines.*
import okhttp3.*
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class ReplyHttpCancellationTest {
    @Test fun `leaving settings cancels pending model discovery and ignores late failure`() = runBlocking {
        lateinit var pending: PendingCall
        val client = ReplyModelsClient(Call.Factory { PendingCall(it).also { call -> pending = call } })
        val job = launch(Dispatchers.Unconfined) {
            client.list(ReplySettings.fromInput("https://example.invalid/v1", "fake-key", ""))
            fail("Cancelled model discovery must not finish")
        }
        assertTrue(pending.isExecuted())
        job.cancelAndJoin()
        assertTrue(pending.isCanceled())
        pending.callback.onFailure(pending, java.io.IOException("late failure"))
        assertTrue(job.isCancelled)
    }

    @Test fun `closing reply cancels network and ignores late failure`() = runBlocking {
        lateinit var pending: PendingCall
        val client = ReplyHttpClient(Call.Factory { PendingCall(it).also { call -> pending = call } })
        val job = launch(Dispatchers.Unconfined) {
            client.generate(ReplySettings.fromInput("https://example.invalid", "fake-key", "m"),
                ReplyContext("a", listOf(ReplyMessage(1, "对方", 0, "你好"))), "", "", "")
            fail("Cancelled reply must not finish")
        }
        assertTrue(pending.isExecuted())
        job.cancelAndJoin()
        assertTrue(pending.isCanceled())
        pending.callback.onFailure(pending, java.io.IOException("late failure"))
        assertTrue(job.isCancelled)
    }
    private class PendingCall(private val request: Request) : Call {
        lateinit var callback: Callback
        private var cancelled = false
        private var executed = false
        override fun request() = request
        override fun execute(): Response = error("Use enqueue")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; executed = true }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = PendingCall(request)
    }
}
