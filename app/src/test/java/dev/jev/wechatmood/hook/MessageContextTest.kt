package dev.jev.wechatmood.hook

import org.junit.Assert.*
import org.junit.Test

class MessageContextTest {
    private fun message(text: String, sent: Int = 0, talker: String = "friend", type: Int = 1) =
        MessageMetadata(type, sent, text, talker)

    @Test fun `takes only ten predecessors in chronological order including both speakers`() {
        val rows = (0..14).map { message("message-$it", it % 2) }
        val visited = mutableListOf<Int>()
        val result = MessageContext.collect(rows[12], 12) { visited += it; rows[it] }!!
        assertEquals((2..11).toList(), visited)
        assertEquals((2..11).map { "message-$it" }, result.context.map { it.text })
        assertEquals(listOf("对方", "我"), result.context.take(2).map { it.speaker })
        assertEquals("message-12", result.text)
    }

    @Test fun `filters foreign conversations media long text and unknown directions`() {
        val rows = listOf(message("自己前文", 1), message("其他会话", talker = "other"),
            message("图片", type = 3), message("长".repeat(1001)), message("未知", 2),
            message("有效前文"), message("当前"))
        val result = MessageContext.collect(rows.last(), rows.lastIndex) { rows[it] }!!
        assertEquals(listOf("自己前文", "有效前文"), result.context.map { it.text })
    }

    @Test fun `missing or failed adapter items yield partial history without blocking target`() {
        val result = MessageContext.collect(message("当前"), 3) {
            when (it) { 0 -> null; 1 -> error("unavailable"); else -> message("已有前文") }
        }!!
        assertEquals(listOf("已有前文"), result.context.map { it.text })
        assertTrue(MessageContext.collect(message("第一条"), 0) { error("must not read") }!!.context.isEmpty())
    }

    @Test fun `outgoing and oversized targets never read history`() {
        assertNull(MessageContext.collect(message("我方消息", 1), 10) { error("must not read") })
        assertNull(MessageContext.collect(message("长".repeat(1001)), 10) { error("must not read") })
    }

    @Test fun `group participants stay distinguishable and outgoing colon is preserved`() {
        val rows = listOf(message("alice:\n你好", talker = "g@chatroom"),
            message("备注:\n保留", 1, "g@chatroom"))
        val result = MessageContext.collect(message("bob:\n好的", talker = "g@chatroom"), 2) { rows[it] }!!
        assertEquals(listOf("alice", "我"), result.context.map { it.speaker })
        assertEquals(listOf("你好", "备注:\n保留"), result.context.map { it.text })
        assertEquals("bob", result.speaker)
        assertEquals("好的", result.text)
    }
}
