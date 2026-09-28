package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyHistorySelectionTest {
    @Test fun `all supported limits and rapidly changing custom counts reject stale completions`() {
        val selection = ReplyHistorySelection("alice")
        val tickets = listOf(1, 10, 17, 100, 17).map(selection::begin)
        tickets.dropLast(1).forEach {
            assertFalse(selection.complete(it, context(it.limit), "alice"))
            selection.fail(it)
            assertTrue(selection.loading)
        }
        assertTrue(selection.complete(tickets.last(), context(17), "alice"))
        assertEquals(17, selection.context!!.requestedMessages)
        for (invalid in listOf(0, -1, 101)) {
            assertThrows(IllegalArgumentException::class.java) { selection.begin(invalid) }
        }
    }

    private fun context(limit: Int = 100) = ReplyContext("alice", listOf(ReplyMessage(9, "对方", 10, "好呀")),
        source = ReplyContextSource.LOCAL_HISTORY, requestedMessages = limit)

    @Test fun `selection waits for loaded evidence before generation and ignores late old window`() {
        val selection = ReplyHistorySelection("alice")
        assertNull(selection.context)
        val first = selection.begin(30)
        assertTrue(selection.loading)
        val second = selection.begin(100)
        assertFalse(selection.complete(first, context(30), "alice"))
        assertNull(selection.context)
        assertTrue(selection.complete(second, context(100), "alice"))
        assertFalse(selection.loading)
        assertEquals(100, selection.context!!.requestedMessages)
    }

    @Test fun `cancel and chat changes reject history without replacing old suggestion evidence`() {
        val original = context()
        val selection = ReplyHistorySelection("alice", original)
        assertSame(original, selection.context)
        val changed = selection.begin(50)
        assertFalse(selection.complete(changed, context(50), "bob"))
        selection.cancel()
        assertFalse(selection.complete(changed, context(50), "alice"))
        assertNull(selection.context)
        assertFalse(selection.loading)
    }

    @Test fun `failed selection remains retryable and wrong size cannot be published`() {
        val selection = ReplyHistorySelection("alice")
        val token = selection.begin(30)
        assertFalse(selection.complete(token, context(100), "alice"))
        selection.fail(token)
        assertFalse(selection.loading)
        assertNull(selection.context)
        val retry = selection.begin(30)
        assertTrue(selection.complete(retry, context(30), "alice"))
    }
}
