package dev.jev.wechatmood.core

import java.util.UUID

/** Resolve visible message identity off the UI thread; background DB traffic cannot change it. */
class AnalysisAccountScopes(private val dispatch: (() -> Unit) -> Unit,
    private val lookup: (AnalysisInput) -> String?) {
    private var session = UUID.randomUUID().toString()
    private var databaseRevision = 0L
    private val scopes = LinkedHashMap<String, String>(16, 0.75f, true)
    private val pending = mutableSetOf<String>()

    @Synchronized fun pendingScope() = "pending:$session"
    @Synchronized fun reset() { session = UUID.randomUUID().toString(); scopes.clear(); pending.clear() }
    @Synchronized fun retryUnverified() {
        databaseRevision++
        scopes.entries.removeAll { it.value.startsWith("memory:") }
    }

    fun scope(input: AnalysisInput): String {
        val identity = input.copy(context = emptyList(), coverage = ContextCoverage(), accountScope = "").key
        val generation: String
        val revision: Long
        synchronized(this) {
            scopes[identity]?.let { return it }
            if (!pending.add(identity)) return pendingScope()
            generation = session
            revision = databaseRevision
        }
        dispatch {
            val found = runCatching { lookup(input) }.getOrNull()
                ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: "memory:$generation"
            synchronized(this) {
                if (session == generation) {
                    pending.remove(identity)
                    // A newly observed DB may turn a miss into a hit, or a match into ambiguity.
                    // Leave it unresolved so the next scan retries using all current handles.
                    if (revision == databaseRevision) {
                        scopes[identity] = found
                        while (scopes.size > 512) scopes.remove(scopes.keys.first())
                    }
                }
            }
        }
        return synchronized(this) { scopes[identity] ?: pendingScope() }
    }
}
