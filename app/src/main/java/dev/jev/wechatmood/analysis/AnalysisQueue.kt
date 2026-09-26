package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Mood
import dev.jev.wechatmood.core.MoodStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owns only request scheduling; host policy and transport are supplied by the caller. */
class AnalysisQueue(
    private val scope: CoroutineScope,
    private val canAnalyze: (AnalysisInput) -> Boolean,
    private val analyze: suspend (AnalysisInput) -> Mood,
    private val onComplete: (Mood) -> Unit = {},
    private val onFailure: (Exception) -> Unit = {},
) {
    private class Entry(val input: AnalysisInput, val claim: MoodStore.Claim, val visible: () -> Boolean) {
        lateinit var job: Job
    }
    private data class Failure(val atNanos: Long, val message: String)
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val failures = mutableMapOf<String, Failure>()
    private val slots = Semaphore(2)

    fun submit(input: AnalysisInput, stillVisible: () -> Boolean = { true }) {
        val key = input.key
        val job = synchronized(lock) {
            if (!canAnalyze(input) || !stillVisible()) return
            if (failures[key]?.let { System.nanoTime() - it.atNanos < 30_000_000_000L } == true) return
            val claim = MoodStore.acquire(key) ?: return
            val entry = Entry(input, claim, stillVisible)
            entry.job = scope.launch(start = CoroutineStart.LAZY) { run(entry) }
            entries.put(key, entry)?.job?.cancel()
            failures.remove(key)
            // Covers a lazy job canceled before its body (and its finally block) starts.
            entry.job.invokeOnCompletion { cleanup(entry) }
            entry.job
        }
        job.start()
    }

    private suspend fun run(entry: Entry) {
        try {
            slots.withPermit {
                currentCoroutineContext().ensureActive()
                if (!canAnalyze(entry.input) || !entry.visible()) throw CancellationException("消息不再可分析")
                val mood = analyze(entry.input)
                currentCoroutineContext().ensureActive()
                val accepted = synchronized(lock) {
                    if (entries[entry.claim.key] !== entry || !canAnalyze(entry.input) || !entry.visible()) false
                    else MoodStore.complete(entry.claim, mood).also { if (it) failures.remove(entry.claim.key) }
                }
                if (accepted) runCatching { onComplete(mood) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val accepted = synchronized(lock) {
                if (entries[entry.claim.key] !== entry || !entry.job.isActive ||
                    !canAnalyze(entry.input) || !entry.visible()) false
                else {
                    failures[entry.claim.key] = Failure(System.nanoTime(), e.message ?: "分析失败，请稍后重试")
                    true
                }
            }
            if (accepted) runCatching { onFailure(e) }
        } finally {
            cleanup(entry)
        }
    }

    private fun cleanup(entry: Entry) = synchronized(lock) {
        if (entries[entry.claim.key] === entry) entries.remove(entry.claim.key)
        MoodStore.release(entry.claim)
    }

    private fun cancelWhere(predicate: (Entry) -> Boolean) {
        val canceled = synchronized(lock) {
            entries.values.filter(predicate).also { matches ->
                matches.forEach { entry ->
                    entries.remove(entry.claim.key)
                    MoodStore.release(entry.claim)
                }
            }
        }
        // Revoke ownership first: a new same-key job may start before the old one unwinds.
        canceled.forEach { it.job.cancel() }
    }

    fun reconcile(visibleKeys: Set<String>) = cancelWhere { it.claim.key !in visibleKeys }
    fun cancelConversation(talker: String) = cancelWhere { it.input.talker == talker }
    fun cancelAll() = cancelWhere { true }
    fun resetSettings() {
        cancelAll()
        synchronized(lock) { failures.clear() }
    }
    fun failure(key: String): String? = synchronized(lock) { failures[key]?.message }
    fun retryFailure(key: String) { synchronized(lock) { failures.remove(key) } }
}
