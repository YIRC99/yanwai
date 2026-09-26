package dev.jev.wechatmood.core

import org.junit.Assert.*
import org.junit.Test

class AnalysisSnapshotsTest {
    private val original = AnalysisInput("好的", "alice", messageId = 42,
        context = listOf(ContextMessage("我", "明天见？")))
    private val expanded = original.copy(context = listOf(ContextMessage("我", "更多前文")) + original.context,
        coverage = ContextCoverage(scanned = 10))

    @Test fun `loading history preserves the analysis already chosen for this message`() {
        val snapshots = AnalysisSnapshots()
        snapshots.resolve(original)
        assertEquals(original.key, snapshots.resolve(expanded).key)
        val edited = expanded.copy(text = "改了")
        assertEquals(edited, snapshots.resolve(edited))
        val otherChat = expanded.copy(talker = "bob")
        assertEquals(otherChat, snapshots.resolve(otherChat))
        val otherTime = expanded.copy(createdAt = 100)
        assertEquals(otherTime, snapshots.resolve(otherTime))
    }

    @Test fun `settings reset and explicit switch off permit fresh evidence`() {
        val snapshots = AnalysisSnapshots()
        snapshots.resolve(original)
        snapshots.clearConversation("alice")
        assertEquals(expanded, snapshots.resolve(expanded))
        snapshots.clear()
        assertEquals(original, snapshots.resolve(original))
    }

    @Test fun `snapshot retention is bounded and unknown message ids are not frozen`() {
        val snapshots = AnalysisSnapshots(2)
        snapshots.resolve(original)
        snapshots.resolve(original.copy(messageId = 43))
        snapshots.resolve(original.copy(messageId = 44))
        assertEquals(expanded, snapshots.resolve(expanded))
        snapshots.resolve(original.copy(messageId = 0))
        assertEquals(expanded.copy(messageId = 0), snapshots.resolve(expanded.copy(messageId = 0)))
    }
}
