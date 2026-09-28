package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyPlusItemsTest {
    private class Item(val name: String, val reply: Boolean = false)
    private fun append(items: List<Item>) = ReplyPlusItems.appendOnce(items, { it.reply }) { Item("reply", true) }

    @Test fun `only the inspected host build is supported`() {
        assertTrue(ReplyPlusItems.supports("8.0.71", 3080))
        assertFalse(ReplyPlusItems.supports("8.0.71", 3081))
        assertFalse(ReplyPlusItems.supports("8.0.70", 3080))
        assertFalse(ReplyPlusItems.supports(null, 3080))
    }

    @Test fun `original list and native object identities are preserved`() {
        val native = listOf(Item("native-a"), Item("native-b"))
        val result = append(native)
        assertEquals(2, native.size)
        assertEquals(3, result.size)
        native.forEachIndexed { i, item -> assertSame(item, result[i]) }
        assertTrue(result.last().reply)
    }

    @Test fun `reopening refreshing and switching panels never duplicates or shares the entry`() {
        var first = append(emptyList())
        val entry = first.last()
        repeat(30) { first = append(first) }
        assertEquals(1, first.size)
        assertSame(entry, first.last())
        val second = append(listOf(Item("new-chat")))
        assertNotSame(entry, second.last())
        assertEquals(1, second.count { it.reply })
        val refreshed = append(listOf(Item("host-rebuilt")))
        assertEquals(listOf("host-rebuilt", "reply"), refreshed.map { it.name })
    }

    @Test fun `duplicate owned entries are removed without removing any host entry`() {
        val a = Item("a"); val b = Item("b")
        val result = append(listOf(Item("reply", true), a, Item("reply", true), b))
        assertEquals(3, result.size)
        assertSame(a, result[0]); assertSame(b, result[1]); assertTrue(result[2].reply)
    }

    @Test fun `native page counts and local to global lookup preserve every existing click`() {
        // Reproduces the inspected n/getCount/c formulas, without modifying native indices.
        for (capacity in listOf(4, 8, 12)) for (local in 0..24) for (extra in 0..20) {
            val dynamic = (0 until extra).map { Item("extension-$it") }
            val withReply = append(dynamic)
            val total = local + withReply.size
            val pages = (total + capacity - 1) / capacity
            val actual = (0 until pages).flatMap { page ->
                val count = if (page == pages - 1) total - page * capacity else capacity
                (0 until count).map { position ->
                    val global = page * capacity + position
                    if (global < local) "native-$global" else withReply[global - local].name
                }
            }
            val expected = (0 until local).map { "native-$it" } + dynamic.map { it.name } + "reply"
            assertEquals("capacity=$capacity local=$local extra=$extra", expected, actual)
            assertEquals(1, actual.count { it == "reply" })
        }
    }

    @Test fun `entry creation failure leaves host data untouched`() {
        val native = listOf(Item("native"))
        runCatching { ReplyPlusItems.appendOnce(native, { it.reply }) { error("unsupported") } }
            .onSuccess { fail("Creation failure must reach the guard before a setter is called") }
        assertEquals(1, native.size)
        assertFalse(native[0].reply)
    }

    @Test fun `equal host models cannot take over the module click handler`() {
        data class HostModel(val appId: String)
        val owned = HostModel("same-id")
        val native = HostModel("same-id")
        val ownership = ReplyPlusOwnership()
        ownership.remember(owned)
        ownership.remember(owned)
        assertTrue(ownership.owns(owned))
        assertFalse(ownership.owns(native))
        assertFalse(ownership.owns(null))
    }

    @Test fun `inconsistent hidden provider list is skipped without permanently disabling later builds`() {
        assertFalse(ReplyPlusItems.canAppend(false, 1))
        assertTrue(ReplyPlusItems.canAppend(true, 1))
        assertTrue(ReplyPlusItems.canAppend(false, 0))
    }
}
