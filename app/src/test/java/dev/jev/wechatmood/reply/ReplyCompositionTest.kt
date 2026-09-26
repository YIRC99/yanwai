package dev.jev.wechatmood.reply

import org.junit.Assert.*
import org.junit.Test

class ReplyCompositionTest {
    private val context = ReplyContext("alice", listOf(ReplyMessage(1, "对方", 1, "明天见")))
    private val suggestion = ReplySuggestion(listOf("好呀", "明天见"), "回应约定")

    @Test fun `opening and selecting a role leave the composer waiting for explicit generation`() {
        val composer = ReplyComposition()
        assertEquals(ReplyRelationship.UNSPECIFIED, composer.relationship)
        assertNull(composer.result)
        composer.relationship = ReplyRelationship.CRUSH
        assertNull(composer.result)
        assertNull(composer.selectedText)
        assertFalse(composer.canUse)
    }

    @Test fun `role changes cannot reuse or relabel the previous result`() {
        val composer = ReplyComposition()
        composer.relationship = ReplyRelationship.FRIEND
        assertTrue(composer.accept(context, suggestion, "自然一点", null, ReplyRelationship.FRIEND))
        composer.relationship = ReplyRelationship.ELDER
        assertFalse(composer.canUse)
        assertEquals("", composer.previousText)
        assertEquals(ReplyRelationship.FRIEND, composer.result!!.relationship)
        assertFalse(composer.accept(context, suggestion, "迟到结果", null, ReplyRelationship.FRIEND))
        composer.relationship = ReplyRelationship.FRIEND
        assertTrue(composer.canUse)
        assertEquals(suggestion.text, composer.previousText)
        assertEquals("自然一点", composer.result!!.direction)
    }

    @Test fun `copy reads only selected part and reopening preserves manual selection`() {
        val composer = ReplyComposition()
        composer.accept(context, suggestion, "", null, ReplyRelationship.UNSPECIFIED)
        assertEquals("好呀", composer.selectedText)
        assertEquals("好呀", composer.selectedText)
        assertEquals(0, composer.selectedPart)
        composer.select(1)
        val reopened = ReplyComposition(composer.result)
        assertEquals("明天见", reopened.selectedText)
        assertEquals(1, reopened.selectedPart)
        assertEquals("明天见", reopened.selectedText)
        reopened.select(0)
        assertEquals("好呀", reopened.selectedText)
        assertThrows(IllegalArgumentException::class.java) { reopened.select(2) }
    }

    @Test fun `new successful generation resets selection and failures preserve old evidence`() {
        val composer = ReplyComposition()
        composer.accept(context, suggestion, "旧要求", 1, ReplyRelationship.UNSPECIFIED)
        composer.select(1)
        val history = ReplyHistory()
        history.remember(composer.result!!)
        assertNull(history.recall("bob"))
        assertEquals(1, history.recall("alice")!!.selectedPart)
        // A cancelled/failed request does not call accept; editing settings does not change old evidence.
        composer.relationship = ReplyRelationship.PARTNER
        assertEquals("旧要求", composer.result!!.direction)
        val newer = context.copy(messages = listOf(ReplyMessage(2, "对方", 2, "几点？")))
        composer.accept(newer, ReplySuggestion(listOf("下午吧", "你方便吗"), "确认时间"), "新要求", 2,
            ReplyRelationship.PARTNER)
        assertEquals(0, composer.selectedPart)
        assertEquals(newer, composer.result!!.context)
        assertEquals("新要求", composer.result!!.direction)
        assertEquals(ReplyRelationship.PARTNER, composer.result!!.relationship)
    }

    @Test fun `changing history count does not relabel or reuse the old suggestion`() {
        val composer = ReplyComposition()
        composer.accept(context, suggestion, "", null, ReplyRelationship.UNSPECIFIED)
        composer.historyLimit = 30
        assertFalse(composer.canUse)
        assertEquals(100, composer.result!!.context.requestedMessages)
        assertFalse(composer.accept(context, suggestion, "", null, ReplyRelationship.UNSPECIFIED))
        assertTrue(composer.accept(context.copy(requestedMessages = 30), suggestion, "", null, ReplyRelationship.UNSPECIFIED))
        assertTrue(composer.canUse)
        assertEquals(30, ReplyComposition(composer.result).historyLimit)
    }
}
