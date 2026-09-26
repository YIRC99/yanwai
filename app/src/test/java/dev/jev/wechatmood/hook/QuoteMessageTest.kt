package dev.jev.wechatmood.hook

import dev.jev.wechatmood.analysis.AnalysisState
import org.junit.Assert.*
import org.junit.Test

class QuoteMessageTest {
    private fun xml(reply: String = "好的，明天见", quoted: String = "明天一起吃饭？", subtype: Int = 57,
        quotedType: Int = 1) = """<msg><appmsg><title><![CDATA[$reply]]></title><type>$subtype</type>
        <refermsg><type>$quotedType</type><svrid>1234</svrid><displayname>小明</displayname>
        <content><![CDATA[$quoted]]></content></refermsg></appmsg></msg>"""

    @Test fun `quoted text reply is a target in both host encodings`() {
        for (type in listOf(49, 822083633)) {
            val message = MessageMetadata(type, 0, xml(), "friend", 42, 1000)
            assertEquals("好的，明天见", message.incomingText())
            val input = MessageContext.collect(message, 0) { error("no history") }
            assertNotNull(input)
            val state = AnalysisState.build(input!!)
            assertEquals("好的，明天见", state.getString("message"))
            assertEquals("明天一起吃饭？", state.getJSONObject("quoted_message").getString("message"))
            assertEquals("小明", state.getJSONObject("quoted_message").getString("display_name"))
            assertEquals(0, state.getJSONArray("context").length())
        }
    }

    @Test fun `group prefix and quoted author never replace the actual sender`() {
        val message = MessageMetadata(822083633, 0, "alice:\n" + xml(), "group@chatroom", 42)
        val state = AnalysisState.build(MessageContext.collect(message, 0) { null }!!)
        assertEquals("alice", state.getString("speaker"))
        assertEquals("小明", state.getJSONObject("quoted_message").getString("display_name"))
        assertEquals("备注:\n明天见", message.copy(content = xml("备注:\n明天见")).incomingText())
        assertNull(message.copy(isSend = 1).incomingText())
    }

    @Test fun `quoted replies in preceding history preserve their separate quotation`() {
        val previous = MessageMetadata(49, 1, xml(), "friend", 41, 900)
        val target = MessageMetadata(1, 0, "好的", "friend", 42, 1000)
        val input = MessageContext.collect(target, 1) { previous }!!
        assertEquals(0, input.coverage.omittedMedia)
        val context = AnalysisState.build(input).getJSONArray("context").getJSONObject(0)
        assertEquals("我", context.getString("speaker"))
        assertEquals("好的，明天见", context.getString("message"))
        assertEquals("明天一起吃饭？", context.getJSONObject("quoted_message").getString("message"))
    }

    @Test fun `non text reference is unavailable evidence but the reply still analyzes`() {
        val message = MessageMetadata(49, 0, xml(quoted = "<img aeskey='secret'/>", quotedType = 3), "friend", 42)
        val state = AnalysisState.build(MessageContext.collect(message, 0) { null }!!)
        assertEquals("好的，明天见", state.getString("message"))
        assertTrue(state.getJSONObject("quoted_message").isNull("message"))
        assertFalse(state.toString().contains("secret"))
    }

    @Test fun `links files malformed xml and external entities never become analyzable text`() {
        for (content in listOf(xml(subtype = 5), xml(subtype = 6), xml().dropLast(7),
            "<!DOCTYPE msg [<!ENTITY x SYSTEM 'file:///never-read'>]>" + xml("&x;"),
            xml(" "), xml("长".repeat(1001)), "not xml", xml().replace("<type>57</type>", ""))) {
            assertNull(MessageMetadata(49, 0, content, "friend", 42).incomingText())
        }
        assertNull(MessageMetadata(3, 0, xml(), "friend", 42).incomingText())
    }

    @Test fun `xml escaping is decoded once and long quotations do not block a short reply`() {
        val message = MessageMetadata(49, 0, xml().replace("<![CDATA[好的，明天见]]>", "A &amp; B &lt; 3"), "friend", 42)
        assertEquals("A & B < 3", message.incomingText())
        val longQuote = message.copy(content = xml(quoted = "长".repeat(1001)))
        val state = AnalysisState.build(MessageContext.collect(longQuote, 0) { null }!!)
        assertEquals("好的，明天见", state.getString("message"))
        assertTrue(state.getJSONObject("quoted_message").isNull("message"))
        assertEquals("too_long", state.getJSONObject("quoted_message").getString("unavailable_reason"))
    }

    @Test fun `changed quotation cannot reuse target cache or manually selected evidence`() {
        fun input(quote: String) = MessageContext.collect(MessageMetadata(49, 0, xml("好的", quote), "friend", 42), 0) { null }!!
        val first = input("明天去吃饭")
        val changed = input("明天取消吃饭")
        assertNotEquals(first.key, changed.key)
        val snapshots = dev.jev.wechatmood.core.AnalysisSnapshots()
        snapshots.resolve(first)
        assertEquals(changed, snapshots.resolve(changed))
        val manual = dev.jev.wechatmood.core.ManualAnalysis()
        assertTrue(manual.select(first))
        assertNull(manual.selectedInput(changed))
        val previous = dev.jev.wechatmood.core.ContextMessage("对方", "好的", quoted = first.quoted)
        assertNotEquals(first.copy(quoted = null, context = listOf(previous)).key,
            first.copy(quoted = null, context = listOf(previous.copy(quoted = changed.quoted))).key)
    }

    @Test fun `quoted history shares the existing evidence budget without inventing chronology`() {
        val rows = (0..14).map {
            MessageMetadata(49, 0, xml("新".repeat(1000), "旧".repeat(1000)), "friend", it + 1L, it + 1L)
        }
        val target = MessageMetadata(49, 0, xml("当前", "引".repeat(1000)), "friend", 42, 100)
        val input = MessageContext.collect(target, rows.size) { rows[it] }!!
        assertTrue(input.coverage.truncated)
        assertEquals(5, input.context.size)
        val state = AnalysisState.build(input)
        assertEquals("当前", state.getString("message"))
        assertEquals(5, state.getJSONArray("context").length())
        assertFalse(state.getJSONObject("quoted_message").has("sent_at_ms"))
    }
}
