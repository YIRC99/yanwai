package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.MoodLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(message.items.any { it.text == "外层内容" })
        assertTrue(message.items.any { it.text == "里层内容" })
        assertTrue(message.nested >= 1)
    }

    @Test fun `超过条目上限时记录省略数量`() {
        val many = (1..ForwardDigest.MAX_ITEMS + 5).joinToString("") { item("张三$it", "第 $it 条内容") }
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
        val texts = message.items.map { it.text }
        assertTrue(texts.any { it.contains("外层内容") })
        assertTrue(texts.any { it.contains("中层内容") })
        assertTrue(texts.any { it.contains("里层内容") })
        assertTrue(message.nested >= 2)
    }

    @Test fun `超过展开深度时只留占位`() {
        var body = "<recordinfo><title>第4层</title><datalist>" + item("丁", "最里") + "</datalist></recordinfo>"
        repeat(7) { level ->
            body = "<recordinfo><title>第${level}层</title><datalist>" + item("张三", "第${level}层正文") +
                "<dataitem datatype=\"17\"><datatitle>下层</datatitle><datadesc>[聊天记录]</datadesc>" +
                "<recordxml>${body}</recordxml></dataitem></datalist></recordinfo>"
        }
        val message = requireNotNull(ForwardDigest.parse(wrap(body)))
        assertTrue(message.items.any { it.text.contains("[聊天记录]") })
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

    @Test fun `整屏填充字符被跳过`() {
        val filler = "uinull".repeat(200)
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>" +
            item("张三", filler) + item("李四", "正常内容") + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals(1, message.items.size)
        assertEquals("正常内容", message.items[0].text)
        assertTrue(message.omitted >= 1)
    }

    @Test fun `零宽字符与不可打印字符被清掉`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>" +
            item("张三", "前面\u200B\u200C后面") + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals("前面后面", message.items[0].text)
    }

    @Test fun `desc 里的 null 填充被剥掉`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><desc>李工: " + "null".repeat(100) +
            "真正内容</desc></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals("真正内容", message.items[0].text)
    }

    @Test fun `合并转发在回复上下文里摊平成文字`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>" +
            item("张三", "明晚八点见") + "</datalist></recordinfo>")
        val meta = dev.jev.wechatmood.hook.MessageMetadata(49, 0, raw, "chat", 9, 1000)
        val context = ReplyContext.collect("chat", listOf(meta))
        assertEquals(1, context.messages.size)
        assertTrue(context.messages[0].text.contains("合并转发"))
        assertTrue(context.messages[0].text.contains("明晚八点见"))
    }

    @Test fun `夹在中间的填充段也会被剥掉`() {
        val filler = "null".repeat(40)
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><desc>诺: " + filler +
            "[聊天记录]" + filler + "李工在群里说要去广医</desc></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals("[聊天记录]李工在群里说要去广医", message.items[0].text)
    }

    @Test fun `uinull 重复段被整段剥掉`() {
        val filler = "uinull".repeat(120)
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><desc>Sun: " + filler +
            "全世界都在找李工</desc></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals("全世界都在找李工", message.items[0].text)
    }

    @Test fun `嵌套里层的 datadesc 不会漏`() {
        val inner = "<recordinfo><datalist><dataitem datatype=\"1\"><datadesc>里层内容</datadesc>" +
            "<sourcename>乙</sourcename></dataitem></datalist></recordinfo>"
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist count=\"2\">" +
            "<dataitem datatype=\"17\"><datatitle>内层</datatitle><datadesc>[聊天记录]</datadesc>" +
            "<recordxml>$inner</recordxml></dataitem></datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertTrue(message.items.any { it.text.contains("里层内容") })
    }

    @Test fun `带偏移的 null 重复也能剥掉`() {
        val filler = "l" + "null".repeat(100)
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><desc>Sun: " + filler +
            "全世界都在找李工</desc></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        val text = message.items[0].text
        assertTrue(text.contains("全世界都在找李工"))
        assertTrue(!text.contains("null"))
    }

    @Test fun `同一人连续发言合并且纯语气被丢掉`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>" +
            item("张三", "哈哈哈") + item("张三", "明天几点到") + item("张三", "我开车") +
            item("李四", "好") + item("李四", "八点半吧") + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        val sender = message.items.first { it.sender == "张三" }
        assertTrue(sender.text.contains("明天几点到"))
        assertTrue(sender.text.contains("我开车"))
        assertTrue(message.items.none { it.text == "哈哈哈" || it.text == "好" })
    }

    @Test fun `摘要请求的日志不记录转录内容`() {
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>" +
            item("张三", "机密内容不要进日志") + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        val settings = ReplySettings.fromInput("https://api.example.com", "sk-test", "test-model")
        ForwardDigestProtocol.payload(settings, message)
        val line = MoodLog.read().lineSequence().last { it.contains("FORWARD_PROMPT") }
        assertTrue(line, line.contains("items=1"))
        assertTrue(line, line.contains("chars="))
        assertTrue(line, line.contains("ms="))
        assertFalse(line, line.contains("机密内容"))
        assertFalse(line, line.contains("张三"))
    }

    @Test fun `截断在记录内部时保留前缀里完整的条目`() {
        // 模拟长度上限把 CDATA 和 recordinfo 都切断：前两条仍然完整。
        val raw = "<msg><appmsg><type>19</type><recorditem><![CDATA[<recordinfo>" +
            "<title>群聊的聊天记录</title><datalist count=\"3\">" +
            item("张三", "第一条内容") + item("李四", "第二条内容") +
            "<dataitem datatype=\"1\"><datadesc>第三条被截断"
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertEquals(2, message.items.size)
        assertEquals("第一条内容", message.items[0].text)
        assertEquals("第二条内容", message.items[1].text)
    }

    @Test fun `原始 XML 截断在长条目内部时保留前面的条目`() {
        val huge = "长".repeat(ForwardDigest.MAX_RAW_CHARS + 4096)
        val raw = wrap("<recordinfo><title>群聊的聊天记录</title><datalist>" +
            item("张三", "前面那条完整的") + item("李四", huge) + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        assertTrue(message.truncatedRaw)
        assertEquals("前面那条完整的", message.items[0].text)
    }

    @Test fun `补扫和结构解析共用同一份预算`() {
        val body = "<recordinfo><datalist>" + item("甲", "一二三四五六") + item("乙", "七八九十甲乙") +
            item("丙", "子丑寅卯辰巳") + item("丁", "午未申酉戌亥") + "</datalist></recordinfo>"
        val parsed = listOf(ForwardDigest.Item(1, "戊", "已有一条", "", false))
        // 结构解析已经用尽预算，补扫一条都不能加。
        assertEquals(1, ForwardDigest.supplement(body, parsed, budget = 0).size)
        // 只剩 13 个字：只够两条 6 字的，第三条必须停下。
        assertEquals(3, ForwardDigest.supplement(body, parsed, budget = 13).size)
        assertEquals(5, ForwardDigest.supplement(body, parsed, budget = 400).size)
    }

    @Test fun `超出展开深度的内容不会被补扫加回`() {
        var body = "<recordinfo><title>最内层</title><datalist>" +
            item("丁", "最里层正文") + "</datalist></recordinfo>"
        repeat(ForwardDigest.MAX_DEPTH + 1) { level ->
            body = "<recordinfo><title>第${level}层</title><datalist>" + item("张三", "第${level}层正文") +
                "<dataitem datatype=\"17\"><datatitle>下层</datatitle><datadesc>[聊天记录]</datadesc>" +
                "<recordxml>$body</recordxml></dataitem></datalist></recordinfo>"
        }
        val message = requireNotNull(ForwardDigest.parse(wrap(body)))
        assertTrue(message.items.none { it.text.contains("最里层正文") })
    }

    @Test fun `短应答不会被当成语气词丢掉`() {
        val raw = wrap("<recordinfo><title>单聊的聊天记录</title><datalist>" +
            item("张三", "明晚八点") + item("李四", "收到") + item("李四", "好") +
            item("王五", "哈哈哈") + "</datalist></recordinfo>")
        val message = requireNotNull(ForwardDigest.parse(raw))
        val reply = message.items.first { it.sender == "李四" }
        assertTrue(reply.text, reply.text.contains("收到"))
        assertTrue(reply.text, reply.text.contains("好"))
        assertTrue(message.items.none { it.text.contains("哈哈") })
    }
}
