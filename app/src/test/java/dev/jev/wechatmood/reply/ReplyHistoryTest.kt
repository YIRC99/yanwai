package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyHistoryTest {
    private fun reply(talker: String, text: String = "一起去吧", focus: Long? = null) = RememberedReply(
        ReplyContext(talker, listOf(ReplyMessage(7, "对方", 42, "周末要一起吗"))),
        ReplySuggestion(text, "回应邀请"), "自然一点", focus)

    @Test fun `closing a request session does not destroy the last successful reply`() {
        val history = ReplyHistory()
        val session = ReplySession()
        val result = reply("alice")
        val ticket = session.begin("alice", result.context.fingerprint)
        history.remember(result)
        session.cancel()
        assertFalse(session.accepts(ticket, "alice"))
        assertSame(result, history.recall("alice"))
        assertEquals("自然一点", history.recall("alice")!!.direction)
        assertEquals("回应邀请", history.recall("alice")!!.suggestion.reason)
    }

    @Test fun `switching chats and explicit focus cannot reuse someone elses reply`() {
        val history = ReplyHistory()
        history.remember(reply("alice", focus = 7))
        assertNull(history.recall("bob"))
        assertNull(history.recall("alice", 8))
        assertNotNull(history.recall("alice", 7))
        assertNotNull(history.recall("alice"))
        history.remember(reply("bob", "下次吧"))
        assertEquals("一起去吧", history.recall("alice")!!.suggestion.text)
    }

    @Test fun `failed or cancelled regeneration keeps previous content until successful replacement`() {
        val history = ReplyHistory()
        val session = ReplySession()
        history.remember(reply("alice"))
        val pending = session.begin("alice", "new-context")
        session.cancel()
        if (session.accepts(pending, "alice")) history.remember(reply("alice", "迟到结果"))
        assertEquals("一起去吧", history.recall("alice")!!.suggestion.text)
        history.remember(reply("alice", "好呀，几点见？"))
        assertEquals("好呀，几点见？", history.recall("alice")!!.suggestion.text)
    }

    @Test fun `history is bounded by recently viewed conversations and can be cleared`() {
        val history = ReplyHistory(2)
        history.remember(reply("alice")); history.remember(reply("bob"))
        history.recall("alice")
        history.remember(reply("charlie"))
        assertNull(history.recall("bob"))
        assertNotNull(history.recall("alice"))
        history.clear()
        assertNull(history.recall("alice"))
    }
}
