package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyHistoryLimitTest {
    @Test fun `input accepts integers only and cancel has no selected value`() {
        for (limit in listOf(1, 10, 17, 100)) assertEquals(limit, ReplyHistoryLimit.parse(" $limit "))
        for (input in listOf("", " ", "1.5", "abc", "1e1", "+10", "0", "101", "-1", "99999999999999999"))
            assertThrows(IllegalArgumentException::class.java) { ReplyHistoryLimit.parse(input) }
        assertNull(ReplyHistoryLimit.parse(null)) // dialog cancel never supplies a new selection
        assertEquals(listOf(10, 30, 50, 100), ReplyHistorySelection.OPTIONS)
    }

    @Test fun `saved account selection survives reopen without taking a count from old replies`() {
        val disk = mutableMapOf<String, Int>()
        fun store() = ReplyLimitPreferences(123, disk::get) { k, v -> disk[k] = v }
        val alice = "a".repeat(64); val bob = "b".repeat(64)
        assertEquals(100, store().load(alice))
        store().save(alice, 17)
        assertEquals(17, store().load(alice))
        assertEquals(100, store().load(bob))
        assertEquals(100, ReplyLimitPreferences(456, disk::get) { _, _ -> }.load(alice))
        val old = RememberedReply(ReplyContext("chat", listOf(ReplyMessage(1, "对方", 1, "好")), requestedMessages = 30),
            ReplySuggestion(listOf("好"), ""))
        val composition = ReplyComposition(old, preferredLimit = store().load(alice))
        assertEquals(17, composition.historyLimit)
        assertEquals(30, composition.result!!.context.requestedMessages)
        assertFalse(composition.canUse)
        assertFalse(composition.accept(old.context, old.suggestion, "", null, ReplyRelationship.UNSPECIFIED))
    }

    @Test fun `FIFO writes and reopen preserve last choice even after drawer closes`() {
        val jobs = ArrayDeque<() -> Unit>()
        val values = mutableMapOf<String, Int>()
        val account = "a".repeat(64)
        val queue = ReplyLimitQueue({ jobs.addLast(it) }, { values[it] ?: 100 }, { k, v -> values[k] = v })
        val owner = ReplyIdentityOwner("epoch", account, "chat")
        listOf(1, 10, 17, 100, 17).forEach { queue.save(owner, it, { account }) { assertTrue(it) } }
        var loaded = 0
        queue.load(account) { loaded = it.getOrThrow() }
        while (jobs.isNotEmpty()) jobs.removeFirst().invoke()
        assertEquals(17, loaded)
        queue.save(owner, 30, { "b".repeat(64) }) { assertFalse(it) }
        jobs.removeFirst().invoke()
        assertEquals(17, values[account])
        queue.load(null) { assertEquals(100, it.getOrThrow()) }
        jobs.removeFirst().invoke()
    }
}
