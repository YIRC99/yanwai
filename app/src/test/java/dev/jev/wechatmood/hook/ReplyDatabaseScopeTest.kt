package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.AnalysisCacheKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 切账号后旧库仍打开时，统计与依据查询不能读到另一个账号的数据。 */
class ReplyDatabaseScopeTest {
    private class FakeDatabase(private val path: String) {
        fun getPath(): String = path
    }

    private val accountA = "/data/user/0/com.tencent.mm/MicroMsg/aaa/EnMicroMsg.db"
    private val accountB = "/data/user/0/com.tencent.mm/MicroMsg/bbb/EnMicroMsg.db"

    @Test fun `only databases of the confirmed account are read`() {
        val a = FakeDatabase(accountA)
        val b = FakeDatabase(accountB)
        assertEquals(listOf(a), ReplyDatabaseHistory.databasesForScope(listOf(a, b), AnalysisCacheKey.digest(accountA)))
        assertEquals(listOf(b), ReplyDatabaseHistory.databasesForScope(listOf(a, b), AnalysisCacheKey.digest(accountB)))
    }

    @Test fun `unconfirmed account reads nothing instead of scanning every database`() {
        val a = FakeDatabase(accountA)
        assertTrue(ReplyDatabaseHistory.databasesForScope(listOf(a), null).isEmpty())
        assertTrue(ReplyDatabaseHistory.databasesForScope(listOf(a), "not-a-scope").isEmpty())
        assertTrue(ReplyDatabaseHistory.databasesForScope(listOf(a), "").isEmpty())
    }

    @Test fun `non main database files never match a scope`() {
        val cache = FakeDatabase("/data/user/0/com.tencent.mm/MicroMsg/aaa/cache.db")
        val main = FakeDatabase(accountA)
        assertEquals(listOf<Any>(main),
            ReplyDatabaseHistory.databasesForScope(listOf(cache, main), AnalysisCacheKey.digest(accountA)))
    }
}
