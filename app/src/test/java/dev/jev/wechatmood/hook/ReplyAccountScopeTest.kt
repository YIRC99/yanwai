package dev.jev.wechatmood.hook

import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.reply.ReplyAccountIdentity
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHistoryAnchor
import dev.jev.wechatmood.reply.ReplyHistoryQuery
import dev.jev.wechatmood.reply.ReplyMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 群统计的账号边界：账号确认不了就不查历史库，绝不把另一个账号同群的消息算进来。 */
class ReplyAccountScopeTest {
    private class FakeDatabase(private val path: String) {
        @Suppress("unused")
        fun getPath() = path
    }

    private val accountA = "a".repeat(64)
    private val accountB = "b".repeat(64)
    private fun dbPath(account: String) = "/data/data/com.tencent.mm/MicroMsg/$account/EnMicroMsg.db"
    private fun scopeOf(account: String) = AnalysisCacheKey.digest(dbPath(account))

    @Test fun `账号未知时不查询任何库`() {
        val databases = listOf(FakeDatabase(dbPath(accountA)))
        assertTrue(ReplyDatabaseHistory.databasesForScope(databases, null).isEmpty())
    }

    @Test fun `账号不是合法 scope 时同样不查询`() {
        val databases = listOf(FakeDatabase(dbPath(accountA)))
        assertTrue(ReplyDatabaseHistory.databasesForScope(databases, "not-a-scope").isEmpty())
    }

    @Test fun `只保留当前账号的库`() {
        val current = FakeDatabase(dbPath(accountA))
        val other = FakeDatabase(dbPath(accountB))
        val kept = ReplyDatabaseHistory.databasesForScope(listOf(current, other), scopeOf(accountA))
        assertEquals(listOf(current), kept)
    }

    @Test fun `两个库都匹配时无法确认账号`() {
        val record = MessageMetadata(1, 0, "早", "chat", 7, 1000)
        val page = ReplyContext("chat", listOf(ReplyMessage(7, "对方", 1000, "早")),
            historyAnchor = ReplyHistoryAnchor.from(record))
        val query = ReplyHistoryQuery { _, _ -> listOf(record) }
        assertNull(ReplyAccountIdentity.resolve(page, listOf(accountA to query, accountB to query)))
    }

    @Test fun `单个库匹配时能确认账号`() {
        val record = MessageMetadata(1, 0, "早", "chat", 7, 1000)
        val page = ReplyContext("chat", listOf(ReplyMessage(7, "对方", 1000, "早")),
            historyAnchor = ReplyHistoryAnchor.from(record))
        val query = ReplyHistoryQuery { _, _ -> listOf(record) }
        assertEquals(accountA, ReplyAccountIdentity.resolve(page, listOf(accountA to query)))
        assertNull(ReplyAccountIdentity.resolve(page,
            listOf(accountA to ReplyHistoryQuery { _, _ -> emptyList() })))
    }

    @Test fun `超过分析字数上限的普通消息仍进入回复上下文`() {
        val long = "字".repeat(1500)
        val context = ReplyContext.collect("chat", listOf(MessageMetadata(1, 0, long, "chat", 1, 1000)))
        assertEquals(1, context.messages.size)
        assertEquals(1500, context.messages[0].text.length)
    }
}
