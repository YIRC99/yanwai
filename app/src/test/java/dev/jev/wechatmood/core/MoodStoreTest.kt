package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class MoodStoreTest {
    @Test fun `stale owner cannot complete or release a new request for the same key`() {
        MoodStore.clear()
        val old = requireNotNull(MoodStore.acquire("owned"))
        assertTrue(MoodStore.release(old))
        val current = requireNotNull(MoodStore.acquire("owned"))
        val mood = Mood("平静", 0.0, 0, "")
        assertFalse(MoodStore.complete(old, mood))
        assertFalse(MoodStore.release(old))
        assertNull(MoodStore.acquire("owned"))
        assertTrue(MoodStore.complete(current, mood))
        assertEquals(mood, MoodStore.get("owned"))
        assertNull(MoodStore.acquire("owned"))
        MoodStore.clear()
    }

    @Test fun `clearing invalidates in flight owners rather than allowing old results to repopulate cache`() {
        MoodStore.clear()
        val old = requireNotNull(MoodStore.acquire("clear"))
        MoodStore.clear()
        assertFalse(MoodStore.complete(old, Mood("平静", 0.0, 0, "")))
        assertNull(MoodStore.get("clear"))
    }
    @Test fun `same wording in different contexts or message positions cannot reuse result`() {
        val first = AnalysisInput("好的", "friend", listOf(ContextMessage("我", "明天可以吗")), 1)
        assertNotEquals(first.key, first.copy(context = listOf(ContextMessage("我", "现在就做"))).key)
        assertNotEquals(first.key, first.copy(messageId = 2).key)
        assertNotEquals(first.key, first.copy(context = listOf(ContextMessage("对方", "明天可以吗"))).key)
        assertEquals(first.key, first.copy().key)
    }
    @Test fun `different messages with Java hash collision must not share a card result`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(MoodStore.keyOf("Aa", "friend"), MoodStore.keyOf("BB", "friend"))
    }
    @Test fun `same message in different conversations stays separate`() {
        assertNotEquals(MoodStore.keyOf("你好", "one"), MoodStore.keyOf("你好", "two"))
        assertEquals(MoodStore.keyOf("你好", "one"), MoodStore.keyOf("你好", "one"))
    }
}
