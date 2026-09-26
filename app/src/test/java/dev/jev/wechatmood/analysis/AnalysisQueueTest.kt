package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Mood
import dev.jev.wechatmood.core.MoodStore
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AnalysisQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val message = AnalysisInput("你好", "alice", messageId = 1)
    private val result = Mood("平静", 0.0, 0, "", "说明")

    @Before fun clear() = MoodStore.clear()
    @After fun cleanup() { scope.cancel(); MoodStore.clear() }

    @Test fun `settings session change cancels occupied slots and clears previous errors`() {
        var canceled = 0
        var old = true
        val queue = AnalysisQueue(scope, { true }, { input ->
            if (!old) result
            else if (input.messageId == 3L) error("old failure")
            else try { awaitCancellation() } finally { canceled++ }
        })
        val session = dev.jev.wechatmood.core.SettingsSession(queue::resetSettings)
        val jev = dev.jev.wechatmood.core.ApiSettings.fromInput(dev.jev.wechatmood.core.ApiSettings.DEFAULT_ENDPOINT, "key")
        session.accept(dev.jev.wechatmood.core.RuntimeSettings(1, false, jev, "install"))
        val failed = message.copy(messageId = 3)
        queue.submit(failed)
        assertNotNull(queue.failure(failed.key))
        queue.submit(message); queue.submit(message.copy(messageId = 2))
        session.accept(dev.jev.wechatmood.core.RuntimeSettings(2, false, jev, "install",
            intent = dev.jev.wechatmood.core.IntentSettings(dev.jev.wechatmood.core.IntentRoute.LLM)))
        assertEquals(2, canceled)
        assertNull(queue.failure(failed.key))
        old = false
        queue.submit(failed)
        assertEquals(result, MoodStore.get(failed.key))
    }

    @Test fun `replacing a revoked claim cancels old work instead of orphaning its slot`() {
        var started = 0
        var canceled = 0
        val queue = AnalysisQueue(scope, { true }, {
            started++
            try { awaitCancellation() } finally { canceled++ }
        })
        queue.submit(message)
        MoodStore.clear()
        queue.submit(message)
        assertEquals(2, started)
        assertEquals(1, canceled)
        queue.cancelAll()
        assertEquals(2, canceled)
    }

    @Test fun `leaving the screen cancels two running requests and starts new visible work`() = runBlocking {
        val started = mutableListOf<Long>()
        val canceled = mutableListOf<Long>()
        val queue = AnalysisQueue(scope, { true }, { input ->
            started += input.messageId
            try { awaitCancellation() } finally { canceled += input.messageId }
        })
        queue.submit(message)
        queue.submit(message.copy(messageId = 2))
        assertEquals(listOf(1L, 2L), started)
        val next = message.copy(talker = "bob", messageId = 3)
        queue.reconcile(setOf(next.key))
        queue.submit(next)
        assertEquals(listOf(1L, 2L, 3L), started)
        assertEquals(setOf(1L, 2L), canceled.toSet())
        assertNull(queue.failure(message.key))
        queue.cancelAll()
    }

    @Test fun `canceling an old request cannot release or overwrite its same key replacement`() = runBlocking {
        val finishOldCleanup = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val finishReplacement = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val queue = AnalysisQueue(scope, { true }, {
            if (count.incrementAndGet() == 1) {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { finishOldCleanup.await() }
                    oldFinished.complete(Unit)
                }
            } else { finishReplacement.await(); result }
        })
        queue.submit(message)
        assertEquals(1, count.get())
        queue.cancelConversation(message.talker)
        queue.submit(message)
        assertEquals(2, count.get())
        finishOldCleanup.complete(Unit)
        withTimeout(2_000) { oldFinished.await() }
        queue.submit(message)
        assertEquals(2, count.get())
        assertNull(queue.failure(message.key))
        finishReplacement.complete(Unit)
        assertEquals(result, MoodStore.get(message.key))
        queue.submit(message)
        assertEquals(2, count.get())
    }

    @Test fun `at most two analyses run and canceled queued work never starts`() {
        val started = mutableListOf<Long>()
        val queue = AnalysisQueue(scope, { true }, { input -> started += input.messageId; awaitCancellation() })
        queue.submit(message)
        queue.submit(message.copy(messageId = 2))
        queue.submit(message.copy(talker = "bob", messageId = 3))
        assertEquals(listOf(1L, 2L), started)
        queue.cancelConversation("bob")
        queue.cancelConversation("alice")
        assertEquals(listOf(1L, 2L), started)
        queue.submit(message.copy(talker = "carol", messageId = 4))
        assertEquals(listOf(1L, 2L, 4L), started)
        queue.cancelAll()
    }

    @Test fun `cancellation has no cooldown while ordinary failure can be retried explicitly`() {
        var calls = 0
        val queue = AnalysisQueue(scope, { true }, {
            calls++
            if (calls == 1) throw CancellationException("gone")
            if (calls == 2) error("model unavailable")
            result
        })
        queue.submit(message)
        assertNull(queue.failure(message.key))
        queue.submit(message)
        assertEquals(2, calls)
        assertEquals("model unavailable", queue.failure(message.key))
        queue.submit(message)
        assertEquals(2, calls)
        queue.retryFailure(message.key)
        queue.submit(message)
        assertEquals(3, calls)
        assertEquals(result, MoodStore.get(message.key))
        assertNull(queue.failure(message.key))
    }
}
