package dev.jev.wechatmood.reply

import dev.jev.wechatmood.hook.MessageMetadata
import org.junit.Assert.*
import org.junit.Test

class ReplyCoreTest {
    @Test fun `base paths normalize without doubling chat completions`() {
        assertEquals("https://example.com/v1/chat/completions", ReplySettings.fromInput("https://example.com", "k", "m").endpoint)
        assertEquals("https://example.com/custom/v1/chat/completions", ReplySettings.fromInput("https://example.com/custom/v1/", "k", "m").endpoint)
        assertEquals("https://example.com/v1/chat/completions", ReplySettings.fromInput("https://example.com/v1/chat/completions", "k", "m").endpoint)
        assertFalse(ReplySettings.empty().isConfigured)
        assertFalse(ReplySettings.fromInput("https://example.com", "secret", "m").toString().contains("secret"))
    }

    @Test fun `invalid configuration does not accept credentials in URL or headers`() {
        listOf("https://u:p@example.com", "https://example.com?key=secret", "file:///tmp/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReplySettings.fromInput(it, "key", "m") }
        }
        assertThrows(IllegalArgumentException::class.java) { ReplySettings.fromInput("https://example.com", "a\nb", "m") }
    }

    @Test fun `reply context keeps long text timestamp and both speakers but isolates chats`() {
        val records = listOf(
            MessageMetadata(1, 1, "我的草稿背景", "a", 1, 1000),
            MessageMetadata(1, 0, "长".repeat(1500), "a", 2, 2000),
            MessageMetadata(1, 0, "别的聊天", "b", 3, 3000),
            MessageMetadata(3, 0, "图片地址", "a", 4, 4000))
        val context = ReplyContext.collect("a", records)
        assertEquals(2, context.messages.size)
        assertEquals("我", context.messages.first().speaker)
        assertEquals(2000L, context.messages.last().time)
        assertEquals(1500, context.messages.last().text.length)
        assertEquals(1, context.omittedMedia)
    }

    @Test fun `context retains latest hundred and group sender with bounded total length`() {
        val records = (1L..120L).map { MessageMetadata(1, 0, "wxid_a:\n消息$it", "g@chatroom", it, it * 1000) }
        val context = ReplyContext.collect("g@chatroom", records)
        assertEquals(100, context.messages.size)
        assertEquals("wxid_a", context.messages.first().speaker)
        assertEquals("消息21", context.messages.first().text)
        assertTrue(context.trimmed)
        val huge = ReplyContext.collect("a", listOf(MessageMetadata(1, 0, "字".repeat(90000), "a", 1)))
        assertTrue(huge.trimmed)
        assertTrue(huge.messages.sumOf { it.text.length } <= ReplyContext.MAX_CHARACTERS)
    }

    @Test fun `cancelled or replaced request cannot publish into another chat`() {
        val session = ReplySession()
        val first = session.begin("a", "old")
        val second = session.begin("a", "new")
        assertFalse(session.accepts(first, "a"))
        assertTrue(session.accepts(second, "a"))
        assertFalse(session.accepts(second, "b"))
        session.cancel()
        assertFalse(session.accepts(second, "a"))
    }

    @Test fun `draft undo cannot overwrite user edits`() {
        val draft = DraftReplacement("原稿", "建议")
        assertEquals("原稿", draft.undo("建议"))
        assertNull(draft.undo("建议加上我自己的话"))
    }

    @Test fun `next short message cannot overwrite an unsent previous part`() {
        val draft = DraftReplacement("原稿", "好呀")
        assertTrue(draft.blocksReplacement("好呀", "明天见"))
        assertFalse(draft.blocksReplacement("好呀", "好呀"))
        assertFalse(draft.blocksReplacement("", "明天见"))
        assertFalse(draft.blocksReplacement("原稿", "明天见"))
        assertEquals("原稿", draft.undo("好呀"))
    }

    @Test fun `unicode truncation never splits a surrogate pair`() {
        val context = ReplyContext.collect("a", listOf(MessageMetadata(1, 0,
            "a" + "😀".repeat(30000), "a", 1)))
        assertFalse(context.messages.single().text.last().isHighSurrogate())
    }

    @Test fun `boundary includes newest media while model only gets text`() {
        val context = ReplyContext.collect("a", listOf(MessageMetadata(1, 0, "看这个", "a", 1),
            MessageMetadata(3, 0, "private-media-path", "a", 2)))
        assertEquals(2L, context.latestLoadedId)
        assertEquals(1, context.messages.size)
        assertFalse(context.messages.toString().contains("private-media-path"))
    }
}
