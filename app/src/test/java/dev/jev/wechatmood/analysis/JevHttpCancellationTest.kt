package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.ApiSettings
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JevHttpCancellationTest {
    @Test fun `canceling suspended HTTP immediately cancels the underlying call`() = runBlocking {
        lateinit var pending: PendingCall
        val client = JevHttpClient(Call.Factory { request -> PendingCall(request).also { pending = it } })
        val job = launch(Dispatchers.Unconfined) {
            client.exchangeSuspending(JSONObject(), ApiSettings.fromInput("https://example.invalid/jev", "fake-key"))
            fail("Canceled HTTP must never complete successfully")
        }
        assertTrue(pending.isExecuted())
        job.cancelAndJoin()
        assertTrue("Cancellation must reach OkHttp, not only its coroutine", pending.isCanceled())
        // OkHttp can still invoke a callback after cancel; it must not revive the coroutine.
        pending.callback.onFailure(pending, java.io.IOException("Canceled"))
        assertTrue(job.isCancelled)
    }

    private class PendingCall(private val request: Request) : Call {
        lateinit var callback: Callback
        private var canceled = false
        private var executed = false
        override fun request() = request
        override fun execute(): Response = error("Coroutine transport must use enqueue")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback; executed = true }
        override fun cancel() { canceled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = canceled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = PendingCall(request)
    }
}
