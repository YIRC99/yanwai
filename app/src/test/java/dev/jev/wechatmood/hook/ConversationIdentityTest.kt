package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class ConversationIdentityTest {
    @Test fun `visible identity overrides stale activity intent`() {
        assertEquals("bob", ConversationIdentity.resolve(listOf("bob", "bob"), null, "alice"))
    }
    @Test fun `empty chat uses current footer or dedicated chat intent`() {
        assertEquals("alice", ConversationIdentity.resolve(emptyList(), "alice", null))
        assertEquals("group@chatroom", ConversationIdentity.resolve(emptyList(), null, "group@chatroom"))
        assertNull(ConversationIdentity.resolve(emptyList(), null, null))
    }
    @Test fun `mixed chats during transition cannot share a switch`() {
        assertNull(ConversationIdentity.resolve(listOf("alice", "bob"), null, "alice"))
        assertNull(ConversationIdentity.resolve(listOf("alice"), "bob", null))
    }
}
