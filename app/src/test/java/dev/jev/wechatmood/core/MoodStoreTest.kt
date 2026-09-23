package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class MoodStoreTest {
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
