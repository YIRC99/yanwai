package dev.jev.wechatmood.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MessageTextPicker] 的单测。
 *
 * 这条规则最容易出错，而且出错方式很隐蔽：挑错了不会崩，只会静静地分析
 * 「8:11」或者「已读」这种垃圾内容——白花钱，还看不出问题。
 * 所以它必须是纯函数，必须被测到。
 */
class MessageTextPickerTest {

    @Test
    fun `挑最长的那段作为正文`() {
        val result = MessageTextPicker.pickMessageBody(
            listOf("张三", "12:30", "晚上一起吃饭吗")
        )
        assertEquals("晚上一起吃饭吗", result)
    }

    @Test
    fun `时间戳被排除`() {
        assertEquals("好", MessageTextPicker.pickMessageBody(listOf("12:30", "8：11", "好")))
    }

    @Test
    fun `已读标记被排除`() {
        assertEquals("收到", MessageTextPicker.pickMessageBody(listOf("已读", "Read", "收到")))
    }

    @Test
    fun `日期分隔符被排除`() {
        assertEquals("明天见", MessageTextPicker.pickMessageBody(listOf("3月5日", "昨天", "明天见")))
    }

    @Test
    fun `星期分隔符被排除`() {
        assertEquals("开会", MessageTextPicker.pickMessageBody(listOf("星期三", "开会")))
    }

    @Test
    fun `全是装饰文字时返回 null`() {
        assertNull(MessageTextPicker.pickMessageBody(listOf("12:30", "已读", "  ")))
    }

    @Test
    fun `空列表返回 null`() {
        assertNull(MessageTextPicker.pickMessageBody(emptyList()))
    }

    @Test
    fun `只含空白的列表返回 null`() {
        assertNull(MessageTextPicker.pickMessageBody(listOf("", "   ", "\n")))
    }

    @Test
    fun `正文含冒号不会被误判成时间`() {
        // 「项目：明天上线」是正文，不是时间戳
        assertEquals(
            "项目：明天上线",
            MessageTextPicker.pickMessageBody(listOf("12:30", "项目：明天上线"))
        )
    }

    @Test
    fun `正文含日期不会被误判`() {
        // 「3月5日要交」比纯日期长，且不是整串匹配日期格式
        assertEquals(
            "3月5日要交",
            MessageTextPicker.pickMessageBody(listOf("3月5日", "3月5日要交"))
        )
    }

    @Test
    fun `结果两端的空白被去掉`() {
        assertEquals("你好", MessageTextPicker.pickMessageBody(listOf("  你好  ")))
    }

    @Test
    fun `全是装饰文字时不会被空串骗过`() {
        // 脏数据：空白串混进来不能让它变成「最长」
        assertNull(MessageTextPicker.pickMessageBody(listOf("12:30", "        ", "已读")))
    }

    @Test
    fun `纯时间样式的正文会被误伤`() {
        // 已知取舍：一条只写着「12:30」的消息会被当成时间戳丢掉。
        // 这种消息本来也没什么情绪可分析，接受这个代价。
        assertNull(MessageTextPicker.pickMessageBody(listOf("12:30")))
    }

    @Test
    fun `英文 Read 被排除但正文保留`() {
        assertEquals("see you tomorrow", MessageTextPicker.pickMessageBody(listOf("Read", "see you tomorrow")))
    }
}
