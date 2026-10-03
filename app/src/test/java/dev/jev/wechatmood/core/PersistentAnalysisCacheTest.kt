package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.DriverManager

class PersistentAnalysisCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val input = AnalysisInput("好的", "alice", messageId = 42, createdAt = 1000,
        context = listOf(ContextMessage("我", "明天见？")), accountScope = AnalysisCacheKey.digest("account-a"),
        zoneId = "Asia/Shanghai")
    private val mood = Mood("智能分析", 0.2, 1, "raw", "愿意明天见面", emotions = mapOf("开心" to 0.7))
    private fun settings(key: String = "secret", model: String = "intent-model", revision: Long = 1) =
        RuntimeSettings(revision, false, ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, key), "install",
            intent = IntentSettings(IntentRoute.LLM, dev.jev.wechatmood.reply.ReplySettings.fromInput(
                "https://api.deepseek.com/v1", "llm-secret", model)))

    internal class Jdbc(file: java.io.File) : AnalysisCacheDatabase {
        private val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        override fun execute(sql: String, args: List<String>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeUpdate()
            }
        }
        override fun query(sql: String, args: List<String>): String? = connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
            statement.executeQuery().use { if (it.next()) it.getString(1) else null }
        }
        override fun close() = connection.close()
    }

    @Test fun `closing and reopening sqlite reuses original analysis despite changed page history`() {
        val file = folder.newFile("cache.db")
        val key = requireNotNull(AnalysisCacheKey.of(input, settings()))
        SqliteAnalysisCache(Jdbc(file)).use { it.save(key, mood, "{\"context\":\"original evidence\"}") }
        MoodStore.clear()
        val reopened = input.copy(context = emptyList(), coverage = ContextCoverage(scanned = 99, truncated = true))
        SqliteAnalysisCache(Jdbc(file)).use {
            val restored = requireNotNull(it.find(requireNotNull(AnalysisCacheKey.of(reopened, settings(revision = 20)))))
            assertEquals(mood.copy(raw = ""), restored.mood)
            assertTrue(restored.evidence.contains("original evidence"))
        }
    }

    @Test fun `account contact message content time quote voice and model changes cannot share results`() {
        val key = requireNotNull(AnalysisCacheKey.of(input, settings()))
        val variants = listOf(input.copy(accountScope = AnalysisCacheKey.digest("account-b")), input.copy(talker = "bob"),
            input.copy(messageId = 43), input.copy(text = "不行"), input.copy(createdAt = 2000),
            input.copy(quoted = QuotedMessage("引用", "张三", 1, "100")),
            input.copy(voice = dev.jev.wechatmood.voice.VoiceSource("alice", 42, 1000, 0, "voice")),
            input.copy(speaker = "其他群成员"), input.copy(zoneId = "UTC"),
            input.copy(background = dev.jev.wechatmood.reply.ContactBackground("新背景", "r2")))
        SqliteAnalysisCache(Jdbc(folder.newFile())).use { store ->
            store.save(key, mood, "{}")
            variants.forEach { assertNull(store.find(requireNotNull(AnalysisCacheKey.of(it, settings())))) }
            assertNull(store.find(requireNotNull(AnalysisCacheKey.of(input, settings(model = "new-model")))))
            assertNull(store.find(requireNotNull(AnalysisCacheKey.of(input, settings(key = "new-key")))))
        }
        assertNotEquals(input.key, input.copy(accountScope = "account-b").key)
        val manual = ManualAnalysis()
        manual.select(input)
        assertNull(manual.selectedInput(input.copy(accountScope = "account-b")))
    }

    @Test fun `unknown account and unstable message identities are not persisted`() {
        for (invalid in listOf(input.copy(accountScope = ""), input.copy(accountScope = "memory:screen"),
            input.copy(accountScope = "pending:screen"), input.copy(messageId = 0), input.copy(createdAt = 0)))
            assertNull(AnalysisCacheKey.of(invalid, settings()))
    }

    @Test fun `existing context keyed cache is promoted with original evidence and survives later history changes`() {
        val config = settings()
        val legacy = AnalysisCacheKey(AnalysisCacheKey.digest("analysis-result-v2", input.key, config.analysisFingerprint()))
        val stable = requireNotNull(AnalysisCacheKey.of(input, config))
        assertNotEquals(stable, legacy)
        val file = folder.newFile()
        SqliteAnalysisCache(Jdbc(file)).use { store ->
            store.save(legacy, mood, "original evidence")
            val restored = AnalysisCacheLookup.find(input, config, store::find) { key, result ->
                store.save(key, result.mood, result.evidence)
            }
            assertEquals("original evidence", restored?.evidence)
            assertEquals(restored, store.find(stable))
        }
        SqliteAnalysisCache(Jdbc(file)).use { store ->
            val reads = mutableListOf<AnalysisCacheKey>()
            var promotions = 0
            val restored = AnalysisCacheLookup.find(input.copy(context = emptyList()), config, {
                reads += it; store.find(it)
            }) { _, _ -> promotions++ }
            assertEquals(mood.copy(raw = ""), restored?.mood)
            assertEquals(listOf(stable), reads)
            assertEquals(0, promotions)
        }
    }

    @Test fun `legacy promotion failure still reuses completed result and unknown accounts never read storage`() {
        val config = settings()
        val legacy = AnalysisCacheKey(AnalysisCacheKey.digest("analysis-result-v2", input.key, config.analysisFingerprint()))
        SqliteAnalysisCache(Jdbc(folder.newFile())).use { store ->
            store.save(legacy, mood, "original evidence")
            val restored = AnalysisCacheLookup.find(input, config, store::find) { _, _ -> error("disk unavailable") }
            assertEquals(mood.copy(raw = ""), restored?.mood)
            assertNull(AnalysisCacheLookup.find(input.copy(accountScope = "memory:screen"), config,
                { fail("Unknown account must not read persistent results"); null },
                { _, _ -> fail("Unknown account must not save persistent results") }))
        }
    }

    @Test fun `evicted snapshots and returning through another chat reuse completed analysis`() {
        MoodStore.clear()
        var modelCalls = 0
        val snapshots = AnalysisSnapshots(1)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        try {
            SqliteAnalysisCache(Jdbc(folder.newFile())).use { store ->
                val queue = dev.jev.wechatmood.analysis.AnalysisQueue(scope, { true }, { request ->
                    AnalysisCacheLookup.find(request, settings(), store::find) { key, result ->
                        store.save(key, result.mood, result.evidence)
                    }?.mood ?: mood.also {
                        modelCalls++
                        store.save(requireNotNull(AnalysisCacheKey.of(request, settings())), it, "original evidence")
                    }
                })
                queue.submit(snapshots.resolve(input))
                queue.cancelAll()
                snapshots.resolve(input.copy(talker = "bob"))
                val reopened = snapshots.resolve(input.copy(context = emptyList(), coverage = ContextCoverage(scanned = 80)))
                assertNotEquals(input.key, reopened.key)
                queue.submit(reopened)
                assertEquals(1, modelCalls)
                assertEquals(mood.detail, MoodStore.get(reopened.key)?.detail)
            }
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            MoodStore.clear()
        }
    }

    @Test fun `partial failure is not persisted and corrupt row becomes a miss`() {
        val db = Jdbc(folder.newFile())
        val key = requireNotNull(AnalysisCacheKey.of(input, settings()))
        SqliteAnalysisCache(db).use { store ->
            store.save(key, mood.copy(intentFailed = true), "{}")
            assertNull(store.find(key))
            store.save(key, mood, "{}")
            db.execute("UPDATE analysis_results SET payload = ?", listOf("broken-json"))
            assertNull(store.find(key))
            store.save(key, mood, "{}")
            assertNotNull(store.find(key))
        }
    }

    @Test fun `bounded retention removes oldest result while a refreshed hit survives`() {
        var clock = 1L
        SqliteAnalysisCache(Jdbc(folder.newFile()), capacity = 2, now = { clock++ }).use { store ->
            val keys = (1L..3L).map { requireNotNull(AnalysisCacheKey.of(input.copy(messageId = it), settings())) }
            store.save(keys[0], mood, "{}")
            store.save(keys[1], mood, "{}")
            assertNotNull(store.find(keys[0]))
            store.save(keys[2], mood, "{}")
            assertNotNull(store.find(keys[0]))
            assertNull(store.find(keys[1]))
            assertNotNull(store.find(keys[2]))
        }
    }

    @Test fun `stored data contains neither API keys nor raw model response`() {
        val file = folder.newFile()
        SqliteAnalysisCache(Jdbc(file)).use {
            it.save(requireNotNull(AnalysisCacheKey.of(input, settings())), mood.copy(raw = "raw-secret-response"), "{}")
        }
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("llm-secret"))
        assertFalse(bytes.contains("raw-secret-response"))
    }

    @Test fun `byte limit is enforced with large unicode results`() {
        SqliteAnalysisCache(Jdbc(folder.newFile()), maxBytes = 3000).use { store ->
            val first = requireNotNull(AnalysisCacheKey.of(input, settings()))
            val second = requireNotNull(AnalysisCacheKey.of(input.copy(messageId = 43), settings()))
            store.save(first, mood.copy(detail = "字".repeat(500)), "{}")
            store.save(second, mood.copy(detail = "字".repeat(500)), "{}")
            assertNull(store.find(first))
            assertNotNull(store.find(second))
        }
    }

    @Test fun `restored card uses current build version without rerunning analysis`() {
        val record = CachedAnalysisResult(mood.copy(detail = "yanwai 0.0.1\n情绪：开心\n以前的分析结果"), "{}")
        val encoded = record.encode()
        assertFalse(encoded.contains("0.0.1"))
        val restored = CachedAnalysisResult.decode(encoded)
        assertEquals(dev.jev.wechatmood.analysis.JevProtocol.header + "\n情绪：开心\n以前的分析结果", restored.mood.detail)
    }

    @Test fun `separate queues across restart do not call model for already completed message`() {
        val file = folder.newFile()
        var modelCalls = 0
        fun visit(page: AnalysisInput) {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
            SqliteAnalysisCache(Jdbc(file)).use { store ->
                val queue = dev.jev.wechatmood.analysis.AnalysisQueue(scope, { true }, { request ->
                    val key = requireNotNull(AnalysisCacheKey.of(request, settings()))
                    store.find(key)?.mood ?: mood.also { modelCalls++; store.save(key, it, "{\"original\":true}") }
                })
                queue.submit(page)
                assertEquals(mood.detail, MoodStore.get(page.key)?.detail)
                queue.cancelConversation(page.talker)
                queue.reconcile(emptySet(), "bob")
            }
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            MoodStore.clear()
        }
        visit(input)
        visit(input.copy(context = emptyList(), coverage = ContextCoverage("loaded_page", scanned = 50, truncated = true)))
        assertEquals(1, modelCalls)
        visit(input.copy(accountScope = AnalysisCacheKey.digest("other-account")))
        assertEquals(2, modelCalls)
    }
}
