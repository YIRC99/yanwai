package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyPlusRebuildGuardTest {
    @Test fun `successful rollback allows a later host rebuild on the same panel`() {
        val guard = ReplyPlusRebuildGuard()
        val panel = Any()
        assertTrue(guard.begin(panel))
        guard.finish(panel)
        // A ready native refresh may follow immediately; do not lose it to a timer.
        assertTrue(guard.begin(panel))
        guard.finish(panel)
        assertTrue(guard.begin(panel))
    }

    @Test fun `nested rebuild and rollback cannot inject twice`() {
        val guard = ReplyPlusRebuildGuard()
        val panel = Any()
        assertTrue(guard.begin(panel))
        assertFalse(guard.begin(panel))
        guard.finish(panel)
        assertTrue(guard.begin(panel))
    }

    @Test fun `unrestored panel stays disabled but does not disable other chats`() {
        val guard = ReplyPlusRebuildGuard()
        val panel = Any()
        assertTrue(guard.begin(panel))
        guard.finish(panel, unsafe = true)
        repeat(30) { assertFalse(guard.begin(panel)) }
        assertTrue(guard.begin(Any()))
    }
}
