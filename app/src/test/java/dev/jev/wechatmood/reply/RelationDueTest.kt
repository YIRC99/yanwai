package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 到期提醒的筛选与文案：这些判据以前是「有没有通知」，现在是「通知里有没有说出是哪件事」。 */
class RelationDueTest {
    private fun fact(kind: String, text: String, at: Long,
        status: String = RelationFact.STATUS_ACTIVE) =
        RelationFact(kind = kind, text = text, occurredAt = at, status = status)

    private fun today(hour: Int): Long = FactTimes.startOfDay(System.currentTimeMillis()) + hour * 3_600_000L

    @Test fun `only today and overdue timed items are due`() {
        val now = today(20)
        val facts = listOf(
            fact("约定", "今天下午见面", today(14)),
            fact("重要日期", "昨天生日", today(9) - 86_400_000L),
            fact("约定", "明天见面", today(10) + 86_400_000L),
            fact("工作", "在游戏公司做运营", 0),
            fact("约定", "已经完成的事", today(11), RelationFact.STATUS_DONE),
        )
        val due = RelationDue.select(facts, now)
        assertEquals(2, due.size)
        assertEquals("昨天生日", due.first().text)
        assertTrue(due.none { it.text == "明天见面" })
        assertTrue(due.none { it.text == "在游戏公司做运营" })
    }

    @Test fun `day only item is not overdue before the day ends`() {
        val now = today(15)
        val todo = fact("重要日期", "今天生日", FactTimes.startOfDay(now))
        assertFalse(FactTimes.overdue(todo.occurredAt, now))
        assertEquals(1, RelationDue.select(listOf(todo), now).size)
    }

    @Test fun `line says what and when instead of counting`() {
        val now = today(15)
        val overdue = fact("约定", "答应帮他看简历", today(9) - 2 * 86_400_000L)
        val text = RelationDue.line(overdue, now)
        assertTrue(text.contains("答应帮他看简历"))
        assertTrue(text.contains("已过"))
    }
}
