package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardDigestTest {
    private fun wrap(inner: String) = "<msg><appmsg><title>聊天记录</title><type>19</type>" +
        "<recorditem><![CDATA[$inner]]></recorditem></appmsg></msg>"

    private fun item(sender: String, text: String, type: Int = 1) =
        """<dataitem datatype="$type" dataid="x"><datadesc>${text.encode()}</datadesc>""" +
            "<sourcename>$sender</sourcename><sourcetime>2026-11-18 18:40:47</sourcetime></dataitem>"

    private fun String.encode() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Test fun `解析合并转发的基本结构`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist count=\"2\">" +
            item("张三", "换新的就行了") + item("李四", "[图片]", type = 2) +
            "</datalist><isChatRoom>1</isChatRoom></recordinfo>")
        assertTrue(ForwardDigest.isForward(raw))
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals("群聊的聊天记录", message.title)
        assertTrue(message.chatroom)
        assertEquals(2, message.items.size)
        assertEquals("张三", message.items[0].sender)
        assertEquals("换新的就行了", message.items[0].text)
        assertEquals(2, message.items[1].type)
    }

    @Test fun `实体编码的换行和空格被还原`() {
        val raw = wrap("<recordinfo><title>单聊的聊天记录</title><datalist count=\"1\">" +
            item("王五", "第一行&#x0A;第二行&#x20;带空格") + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals("第一行\n第二行 带空格", message.items[0].text)
    }

    @Test fun `嵌套的聊天记录展开一层并标注`() {
        val inner = "<recordinfo><title>更早的记录</title><datalist count=\"1\">" +
            item("赵六", "里层内容") + "</datalist></recordinfo>"
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist count=\"2\">" +
            item("张三", "外层内容") + item("张三", inner) + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals(2, message.items.size)
        assertEquals(1, message.nested)
        assertEquals("里层内容", message.items[1].text)
        assertTrue(message.items[1].nested)
    }

    @Test fun `超过条目上限时记录省略数量`() {
        val many = (1..ForwardDigest.MAX_ITEMS + 5).joinToString("") { item("张三", "第 $it 条") }
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>$many</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals(ForwardDigest.MAX_ITEMS, message.items.size)
        assertTrue(message.omitted > 0)
    }

    @Test fun `单条过长时截断`() {
        val long = "字".repeat(600)
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>${item("张三", long)}</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals(ForwardDigest.MAX_ITEM_CHARS, message.items[0].text.length)
    }

    @Test fun `没有 datalist 时退回 desc 文本`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title>" +
            "<desc>张三: 第一条&#x0A;李四: 第二条</desc></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals(2, message.items.size)
        assertEquals("张三", message.items[0].sender)
        assertEquals("第一条", message.items[0].text)
    }

    @Test fun `普通消息不会被当成合并转发`() {
        assertNull(ForwardDigest.parse("明天下午三点见"))
        assertEquals(false, ForwardDigest.isForward("<msg><appmsg><type>5</type></appmsg></msg>"))
    }

    @Test fun `transcript 不带 XML 且保留条数`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist count=\"2\">" +
            item("张三", "一") + item("李四", "二") + "</datalist></recordinfo>")
        val text = requireNotNull(ForwardDigest.parse(raw)).transcript()
        assertTrue(text.contains("张三: 一"))
        assertTrue(text.contains("2. 李四: 二"))
        assertTrue(!text.contains("<"))
    }

    @Test fun `datatype 17 的三层嵌套按 recordxml 展开`() {
        val innermost = "<recordinfo><title>最早</title><datalist>${item("甲", "里层内容")}</datalist></recordinfo>"
        val middle = "<recordinfo><title>中层</title><datalist>${item("乙", "中层内容")}" +
            "<dataitem datatype=\"17\"><datatitle>最早</datatitle><datadesc>[聊天记录]</datadesc>" +
            "<recordxml>$innermost</recordxml></dataitem></datalist></recordinfo>"
        val raw = wrap("<recordinfo><title>外层</title><datalist>${item("丙", "外层内容")}" +
            "<dataitem datatype=\"17\"><datatitle>中层</datatitle><datadesc>中层</datadesc>" +
            "<recordxml>$middle</recordxml></dataitem></datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals(3, message.items.size)
        assertEquals("外层内容", message.items[0].text)
        assertEquals("中层内容", message.items[1].text)
        assertEquals("里层内容", message.items[2].text)
        assertEquals(2, message.nested)
    }

    @Test fun `超过展开深度时只留占位`() {
        var body = "<recordinfo><title>第4层</title><datalist>" + item("丁", "最里") + "</datalist></recordinfo>"
        repeat(3) { level ->
            body = "<recordinfo><title>第${level}层</title><datalist>" + item("张三", "第${level}层正文") +
                "<dataitem datatype=\"17\"><datatitle>下层</datatitle><datadesc>[聊天记录]</datadesc>" +
                "<recordxml>${body}</recordxml></dataitem></datalist></recordinfo>"
        }
        val message = requireNotNull(ForwardDigest.parse(wrap(body)))
        assertTrue(message.items.any { it.text == "[聊天记录]" })
        assertTrue(message.items.size <= ForwardDigest.MAX_ITEMS)
    }

    @Test fun `超长原始 XML 被截断而不是异常`() {
        val padding = "x".repeat(ForwardDigest.MAX_RAW_CHARS + 2048)
        assertNull(ForwardDigest.parse(padding))
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>" + item("张三", "正常内容") +
            "</datalist></recordinfo>") + padding
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals("正常内容", message.items[0].text)
    }
}
