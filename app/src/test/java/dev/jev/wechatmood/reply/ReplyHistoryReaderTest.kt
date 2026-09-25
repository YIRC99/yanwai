package dev.jev.wechatmood.reply

import dev.jev.wechatmood.hook.MessageMetadata
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class ReplyHistoryReaderTest {
    private fun row(id: Int, talker: String = "friend", text: String = "消息$id") =
        MessageMetadata(1, id % 2, text, talker, id.toLong(), id * 1000L)
    private val loaded get() = ReplyContext.collect("friend", (184..200).map { row(it) })
    private fun source(rows: List<MessageMetadata> = (100..200).map { row(it) }.reversed()) =
        ReplyHistoryQuery { sql, args ->
            assertEquals("friend", args.first())
            if (sql.contains("msgId = ?")) listOf(row(200)) else rows
        }

    @Test fun firstPageOf17ExpandsToLatest100InChronologicalOrder() {
        val result = ReplyHistoryReader.read(loaded, listOf(source()))
        assertEquals(100, result.messages.size)
        assertEquals(101L, result.messages.first().id)
        assertEquals(200L, result.messages.last().id)
        assertEquals(ReplyContextSource.LOCAL_HISTORY, result.source)
        assertTrue(result.trimmed)
        assertNull(result.historyFailure)
    }

    @Test fun smallActualHistoryIsNotPaddedOrClaimedAsFailure() {
        val result = ReplyHistoryReader.read(loaded, listOf(source((184..200).map { row(it) }.reversed())))
        assertEquals(17, result.messages.size)
        assertEquals(ReplyContextSource.LOCAL_HISTORY, result.source)
        assertFalse(result.trimmed)
    }

    @Test fun wrongAccountAnchorCannotReadHistory() {
        var calls = 0
        val wrongAccount = ReplyHistoryQuery { _, _ -> calls++; listOf(row(200, text = "different")) }
        val result = ReplyHistoryReader.read(loaded, listOf(wrongAccount))
        assertEquals(1, calls)
        assertEquals(loaded.messages, result.messages)
        assertEquals(ReplyContextSource.LOADED_PAGE, result.source)
        assertNotNull(result.historyFailure)
    }

    @Test fun mixedConversationRowsAreRejected() {
        val result = ReplyHistoryReader.read(loaded, listOf(source(listOf(row(200), row(199, "other")))))
        assertEquals(loaded.messages, result.messages)
        assertNotNull(result.historyFailure)
    }

    @Test fun unavailableAndFailedDatabaseKeepHonestPageFallback() {
        for (sources in listOf(emptyList(), listOf(ReplyHistoryQuery { _, _ -> error("closed") }))) {
            val result = ReplyHistoryReader.read(loaded, sources)
            assertEquals(loaded.messages, result.messages)
            assertEquals(ReplyContextSource.LOADED_PAGE, result.source)
            assertNotNull(result.historyFailure)
        }
    }

    @Test fun staleHandleCanBeSkippedForVerifiedCurrentHandle() {
        val result = ReplyHistoryReader.read(loaded, listOf(ReplyHistoryQuery { _, _ -> emptyList() }, source()))
        assertEquals(100, result.messages.size)
    }

    @Test fun identifiersUseArgumentsAndHistoryQueryIsBounded() {
        val oddTalker = "friend' OR 1=1 --"
        val context = ReplyContext.collect(oddTalker, listOf(row(200, oddTalker)))
        var queriedHistory = false
        val db = ReplyHistoryQuery { sql, args ->
            assertFalse(sql.contains(oddTalker))
            assertEquals(oddTalker, args.first())
            if (!sql.contains("msgId = ?")) {
                queriedHistory = true
                assertTrue(sql.contains("type = 1"))
                assertTrue(sql.contains("LIMIT 101"))
                assertTrue(sql.contains("ORDER BY createTime DESC, msgId DESC"))
            }
            listOf(row(200, oddTalker))
        }
        ReplyHistoryReader.read(context, listOf(db))
        assertTrue(queriedHistory)
    }

    @Test fun cancellationIsNeverConvertedToFallback() {
        assertThrows(CancellationException::class.java) {
            ReplyHistoryReader.read(loaded, listOf(ReplyHistoryQuery { _, _ -> throw CancellationException() }))
        }
        assertThrows(CancellationException::class.java) {
            ReplyHistoryReader.read(loaded, listOf(source())) { throw CancellationException() }
        }
    }

    @Test fun expandedHistoryStillHonorsCharacterBudgetAndLatestMessage() {
        val rows = (100..199).map { row(it, text = "长".repeat(2000)) } + row(200)
        val result = ReplyHistoryReader.read(loaded, listOf(source(rows.reversed())))
        assertEquals(ReplyContext.MAX_CHARACTERS, result.messages.sumOf { it.text.length })
        assertEquals(loaded.messages.last(), result.messages.last())
        assertTrue(result.trimmed)
    }

    @Test fun groupAnchorAndEarlierMemberNamesArePreserved() {
        val recent = MessageMetadata(1, 0, "member-a:\n近况如何", "room@chatroom", 200, 200000)
        val earlier = MessageMetadata(1, 0, "member-b:\n早上好", "room@chatroom", 100, 100000)
        val context = ReplyContext.collect(recent.talker, listOf(recent))
        val db = ReplyHistoryQuery { sql, _ -> if (sql.contains("msgId = ?")) listOf(recent) else listOf(recent, earlier) }
        val result = ReplyHistoryReader.read(context, listOf(db))
        assertEquals(ReplyContextSource.LOCAL_HISTORY, result.source)
        assertEquals(listOf("member-b", "member-a"), result.messages.map { it.speaker })
        assertEquals(listOf("早上好", "近况如何"), result.messages.map { it.text })
    }

    @Test fun staleHistoryDoesNotEraseNewerPageEvidence() {
        val result = ReplyHistoryReader.read(loaded, listOf(source(listOf(row(100)))))
        assertEquals(loaded.messages, result.messages)
        assertNotNull(result.historyFailure)
    }
}
