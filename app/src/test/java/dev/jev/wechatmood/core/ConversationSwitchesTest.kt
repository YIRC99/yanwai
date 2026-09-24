package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class ConversationSwitchesTest {
    @Test fun `contacts and groups default off and remember only their own opt in`() {
        var disk = emptySet<String>()
        fun open() = ConversationSwitches(disk) { disk = it; true }
        val first = open()
        assertFalse(first.isEnabled("alice"))
        assertFalse(first.isEnabled("group@chatroom"))
        assertTrue(first.setEnabled("alice", true))
        assertTrue(first.isEnabled("alice"))
        assertFalse(first.isEnabled("bob"))
        assertFalse(first.isEnabled("group@chatroom"))
        assertTrue(first.setEnabled("group@chatroom", true))
        val restarted = open()
        assertTrue(restarted.isEnabled("alice"))
        assertTrue(restarted.isEnabled("group@chatroom"))
        assertTrue(restarted.setEnabled("alice", false))
        assertFalse(open().isEnabled("alice"))
        assertTrue(open().isEnabled("group@chatroom"))
        assertFalse(disk.any { it.contains("alice") || it.contains("chatroom") })
    }

    @Test fun `unidentified chat cannot be enabled`() {
        val settings = ConversationSwitches(emptySet()) { error("Must not save an unknown chat") }
        for (talker in listOf(null, "", " ")) {
            assertFalse(settings.isEnabled(talker))
            assertFalse(settings.setEnabled(talker, true))
        }
    }

    @Test fun `failed writes preserve last confirmed state`() {
        var writable = true
        val settings = ConversationSwitches(emptySet()) { writable }
        settings.setEnabled("alice", true)
        writable = false
        assertFalse(settings.setEnabled("alice", false))
        assertTrue(settings.isEnabled("alice"))
        assertFalse(settings.setEnabled("bob", true))
        assertFalse(settings.isEnabled("bob"))
    }
}
