package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class AnalysisAccountScopesTest {
    private val input = AnalysisInput("ok", "alice", messageId = 1, createdAt = 1000)
    private val a = AnalysisCacheKey.digest("account-a")
    private val b = AnalysisCacheKey.digest("account-b")

    @Test fun `new database arriving during lookup must not be hidden by late unknown result`() {
        val tasks = mutableListOf<() -> Unit>()
        lateinit var scopes: AnalysisAccountScopes
        var first = true
        scopes = AnalysisAccountScopes({ tasks += it }, {
            if (first) { first = false; scopes.retryUnverified(); null } else a
        })
        scopes.scope(input)
        tasks.removeAt(0)()
        assertTrue(scopes.scope(input).startsWith("pending:"))
        tasks.removeAt(0)()
        assertEquals(a, scopes.scope(input))
    }

    @Test fun `unrelated account background queries cannot relabel a verified visible message`() {
        var account = a
        var queries = 0
        val tasks = mutableListOf<() -> Unit>()
        val scopes = AnalysisAccountScopes({ tasks += it }, { queries++; account })
        assertTrue(scopes.scope(input).startsWith("pending:"))
        tasks.removeAt(0)()
        assertEquals(a, scopes.scope(input))
        account = b
        scopes.retryUnverified()
        repeat(3) { assertEquals(a, scopes.scope(input.copy(coverage = ContextCoverage(scanned = it)))) }
        assertEquals(1, queries)
        scopes.reset()
        assertTrue(scopes.scope(input).startsWith("pending:"))
        tasks.removeAt(0)()
        assertEquals(b, scopes.scope(input))
    }

    @Test fun `late old screen lookup cannot populate a new screen and unknown accounts remain memory only`() {
        val tasks = mutableListOf<() -> Unit>()
        var account: String? = a
        val scopes = AnalysisAccountScopes({ tasks += it }, { account })
        val old = scopes.scope(input)
        scopes.reset()
        val current = scopes.scope(input)
        assertNotEquals(old, current)
        tasks.removeAt(0)()
        assertEquals(current, scopes.scope(input))
        account = null
        tasks.removeAt(0)()
        assertTrue(scopes.scope(input).startsWith("memory:"))
        account = b
        scopes.retryUnverified()
        scopes.scope(input)
        tasks.removeAt(0)()
        assertEquals(b, scopes.scope(input))
    }
}
