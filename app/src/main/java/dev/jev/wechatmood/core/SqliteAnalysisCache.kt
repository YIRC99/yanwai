package dev.jev.wechatmood.core

import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest

interface AnalysisCacheDatabase : Closeable {
    fun execute(sql: String, args: List<String> = emptyList())
    fun query(sql: String, args: List<String> = emptyList()): String?
}

/** No credentials or account paths are stored: namespace and message identity are hashes. */
data class AnalysisCacheKey(val value: String) {
    companion object {
        // Bump when the meaning of analysis prompts/results changes, not for unrelated UI releases.
        private const val FORMAT = "analysis-result-v1"
        fun digest(vararg values: String): String {
            val source = values.joinToString("") { "${it.length}:$it" }
            return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        fun of(input: AnalysisInput, settings: RuntimeSettings): AnalysisCacheKey? {
            if (!input.accountScope.matches(Regex("[0-9a-f]{64}")) || input.messageId <= 0 || input.createdAt <= 0 || input.talker.isBlank()) return null
            val identity = input.copy(context = emptyList(), coverage = ContextCoverage()).key
            return AnalysisCacheKey(digest(FORMAT, identity, settings.generation,
                settings.api.endpoint, settings.api.model, settings.api.apiKey,
                settings.intent.route.id, settings.intent.llm.endpoint, settings.intent.llm.model, settings.intent.llm.apiKey))
        }
    }
}

data class CachedAnalysisResult(val mood: Mood, val evidence: String) {
    private val hasHeader get() = mood.detail.lineSequence().firstOrNull()?.matches(Regex("yanwai \\S+")) == true
    fun encode(): String = JSONObject().put("format", 1).put("label", mood.label).put("score", mood.score)
        .put("risk", mood.risk).put("detail", if (hasHeader) mood.detail.substringAfter('\n', "") else mood.detail)
        .put("version_header", hasHeader).put("emotions", JSONObject(mood.emotions))
        .put("evidence", evidence).toString()
    companion object {
        fun decode(payload: String): CachedAnalysisResult {
            require(payload.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
            val json = JSONObject(payload)
            require(json.getInt("format") == 1)
            val emotions = json.getJSONObject("emotions")
            val detail = (if (json.optBoolean("version_header")) dev.jev.wechatmood.analysis.JevProtocol.header + "\n" else "") + json.getString("detail")
            val mood = Mood(json.getString("label"), json.getDouble("score"), json.getInt("risk"), "",
                detail, emotions = emotions.keys().asSequence().associateWith(emotions::getDouble))
            require(mood.score.isFinite() && mood.emotions.values.all { it.isFinite() && it in 0.0..1.0 })
            return CachedAnalysisResult(mood, json.getString("evidence"))
        }
    }
}

/** Disk access is restricted to the analysis IO worker. Reads and pruning are serialized. */
class SqliteAnalysisCache(private val db: AnalysisCacheDatabase,
    private val capacity: Int = 5000, private val maxBytes: Long = 32L * 1024 * 1024,
    private val now: () -> Long = System::currentTimeMillis) : Closeable {
    init {
        require(capacity > 0 && maxBytes > 0)
        db.execute("CREATE TABLE IF NOT EXISTS analysis_results (cache_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, accessed INTEGER NOT NULL)")
        db.execute("CREATE INDEX IF NOT EXISTS analysis_accessed ON analysis_results(accessed)")
    }

    @Synchronized fun find(key: AnalysisCacheKey): CachedAnalysisResult? {
        val payload = db.query("SELECT payload FROM analysis_results WHERE cache_key = ?", listOf(key.value)) ?: return null
        val result = runCatching { CachedAnalysisResult.decode(payload) }.getOrNull()
        if (result == null) db.execute("DELETE FROM analysis_results WHERE cache_key = ?", listOf(key.value))
        else db.execute("UPDATE analysis_results SET accessed = ? WHERE cache_key = ?", listOf(now().toString(), key.value))
        return result
    }

    @Synchronized fun save(key: AnalysisCacheKey, mood: Mood, evidence: String) {
        // Incomplete intelligent analysis must remain retryable, never a durable success.
        if (mood.intentFailed) return
        val payload = CachedAnalysisResult(mood, evidence).encode()
        if (payload.toByteArray(Charsets.UTF_8).size > minOf(256L * 1024, maxBytes)) return
        db.execute("BEGIN IMMEDIATE")
        try {
            db.execute("INSERT OR REPLACE INTO analysis_results(cache_key, payload, accessed) VALUES(?, ?, ?)",
                listOf(key.value, payload, now().toString()))
            db.execute("DELETE FROM analysis_results WHERE cache_key IN (SELECT cache_key FROM analysis_results ORDER BY accessed DESC, rowid DESC LIMIT -1 OFFSET $capacity)")
            while ((db.query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))), 0) FROM analysis_results")?.toLong() ?: 0) > maxBytes) {
                db.execute("DELETE FROM analysis_results WHERE cache_key IN (SELECT cache_key FROM analysis_results ORDER BY accessed ASC, rowid ASC LIMIT 1)")
            }
            db.execute("COMMIT")
        } catch (error: Exception) {
            runCatching { db.execute("ROLLBACK") }
            throw error
        }
    }

    @Synchronized override fun close() = db.close()
}
