package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class MoodStoreTest {
    @Test fun `different messages with Java hash collision must not share a card result`() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(MoodStore.keyOf("Aa", "friend"), MoodStore.keyOf("BB", "friend"))
    }
    @Test fun `same message in different conversations stays separate`() {
        assertNotEquals(MoodStore.keyOf("你好", "one"), MoodStore.keyOf("你好", "two"))
        assertEquals(MoodStore.keyOf("你好", "one"), MoodStore.keyOf("你好", "one"))
    }
}
