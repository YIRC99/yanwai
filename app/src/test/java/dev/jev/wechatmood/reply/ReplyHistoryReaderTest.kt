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

    @Test fun selectedWindowLoadsLatestHistoryWithoutExpandingThePage() {
        for (limit in listOf(30, 50, 100)) {
            val source = ReplyHistoryQuery { sql, _ ->
                if (sql.contains("msgId = ?")) listOf(row(200)) else {
                    assertTrue(sql.contains("LIMIT ${limit + 1}"))
                    (200 - limit..200).map { row(it) }.reversed()
                }
            }
            val result = ReplyHistoryReader.read(loaded, listOf(source), limit)
            assertEquals(limit, result.messages.size)
            assertEquals((201 - limit).toLong(), result.messages.first().id)
            assertEquals(200L, result.messages.last().id)
            assertEquals(limit, result.requestedMessages)
            assertEquals(ReplyContextSource.LOCAL_HISTORY, result.source)
        }
    }

    @Test fun pageFallbackAndShortHistoryNeverInventTheSelectedCount() {
        val largerPage = ReplyContext.collect("friend", (151..200).map { row(it) })
        val fallback = ReplyHistoryReader.read(largerPage, emptyList(), 30)
        assertEquals(30, fallback.messages.size)
        assertEquals(171L, fallback.messages.first().id)
        assertEquals(30, fallback.requestedMessages)
        assertNotNull(fallback.historyFailure)
        val small = ReplyHistoryReader.read(loaded, listOf(source((184..200).map { row(it) })), 50)
        assertEquals(17, small.messages.size)
        assertEquals(50, small.requestedMessages)
        assertEquals(ReplyContextSource.LOCAL_HISTORY, small.source)
        assertNull(small.historyFailure)
    }

    @Test fun invalidHistoryWindowIsRejectedBeforeQuery() {
        for (limit in listOf(0, -1, 101, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                ReplyHistoryReader.read(loaded, listOf(ReplyHistoryQuery { _, _ -> error("must not query") }), limit)
            }
        }
    }

    @Test fun mediaOnlyPageCanVerifyCurrentDatabaseAndReadEarlierTextWithoutScrolling() {
        val photo = MessageMetadata(3, 0, "private-image-location", "friend", 201, 201000)
        val mediaPage = ReplyContext.collect("friend", listOf(photo))
        val db = ReplyHistoryQuery { sql, _ ->
            if (sql.contains("msgId = ?")) listOf(photo) else (150..200).map { row(it) }.reversed()
        }
        val result = ReplyHistoryReader.read(mediaPage, listOf(db), 50)
        assertEquals(ReplyContextSource.LOCAL_HISTORY, result.source)
        assertEquals(50, result.messages.size)
        assertEquals(200L, result.messages.last().id)
        assertEquals(201L, result.latestLoadedId)
        assertFalse(mediaPage.toString().contains("private-image-location"))
        assertFalse(result.toString().contains("private-image-location"))
    }

    @Test fun changedMediaAnchorCannotReadAnotherAccountHistory() {
        val photo = MessageMetadata(3, 0, "private-image-location", "friend", 201, 201000)
        var calls = 0
        val db = ReplyHistoryQuery { _, _ -> calls++; listOf(photo.copy(content = "other-account-image")) }
        val result = ReplyHistoryReader.read(ReplyContext.collect("friend", listOf(photo)), listOf(db), 30)
        assertEquals(1, calls)
        assertEquals(ReplyContextSource.LOADED_PAGE, result.source)
        assertTrue(result.messages.isEmpty())
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
