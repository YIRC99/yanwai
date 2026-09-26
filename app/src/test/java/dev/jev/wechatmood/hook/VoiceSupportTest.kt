package dev.jev.wechatmood.hook

import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHistoryQuery
import dev.jev.wechatmood.reply.ReplyHistoryReader
import org.junit.Assert.*
import org.junit.Test

class VoiceSupportTest {
    private fun voice(id: Long = 2, sent: Int = 0) = MessageMetadata(34, sent, "sender:1:3000", "friend", id, id * 1000)

    @Test fun incomingVoiceHasManualAnalysisAndReplyEntryWithoutTreatingPayloadAsText() {
        val message = voice()
        assertTrue(message.isReplyTarget())
        val input = MessageContext.collect(message, 0) { error("no history") }
        assertNotNull(input)
        assertFalse(input!!.text.contains("sender:1:3000"))
        assertFalse(voice(sent = 1).isReplyTarget())
    }

    @Test fun analysisKeepsBothSidesVoiceInChronologicalContext() {
        val rows = listOf(voice(1, 1), voice(2), MessageMetadata(1, 0, "那明天见", "friend", 3, 3000))
        val input = MessageContext.collect(rows.last(), 2) { rows[it] }!!
        assertEquals(listOf(1L, 2L), input.context.map { it.messageId })
        assertEquals(listOf("我", "对方"), input.context.map { it.speaker })
    }

    @Test fun selectedReplyWindowCountsVoiceAsMessagesInsteadOfReachingIntoOlderText() {
        val rows = listOf(MessageMetadata(1, 0, "旧话题", "friend", 1, 1000), voice(2), voice(3, 1))
        val result = ReplyContext.collect("friend", rows, 2)
        assertEquals(listOf(2L, 3L), result.messages.map { it.id })
        assertEquals(0, result.omittedMedia)
        assertTrue(result.trimmed)
        assertFalse(result.messages.any { it.text.contains("sender:1:3000") })
    }

    @Test fun historyQueryIncludesVoiceAndDoesNotFallbackWhenLatestMessageIsVoice() {
        val latest = voice(3)
        val loaded = ReplyContext.collect("friend", listOf(latest))
        val db = ReplyHistoryQuery { sql, _ ->
            if (sql.contains("msgId = ?")) listOf(latest) else {
                assertTrue(sql.contains("34"))
                listOf(latest, voice(2, 1))
            }
        }
        val result = ReplyHistoryReader.read(loaded, listOf(db), 30)
        assertNull(result.historyFailure)
        assertEquals(listOf(2L, 3L), result.messages.map { it.id })
    }
}
