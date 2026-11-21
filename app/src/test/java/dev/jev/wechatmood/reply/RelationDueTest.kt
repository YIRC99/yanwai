package dev.jev.wechatmood.reply

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test fun `days late counts whole days for day only items`() {
        val now = today(15)
        assertEquals(0, RelationDue.daysLate(today(9), now))
        assertEquals(1, RelationDue.daysLate(FactTimes.startOfDay(now) - 86_400_000L, now))
        assertEquals(0, RelationDue.daysLate(0, now))
    }

    @Test fun `today item keeps a daily reminder`() {
        val now = today(15)
        val item = fact("约定", "今天下午见面", today(9))
        val first = RelationDue.stampKey(listOf(item), now)
        assertEquals(first, RelationDue.stampKey(listOf(item), now + 3_600_000L))
        assertNotEquals(first, RelationDue.stampKey(listOf(item), now + 86_400_000L))
    }

    @Test fun `long overdue item drops to a three day cadence`() {
        val start = FactTimes.startOfDay(System.currentTimeMillis())
        val stale = fact("约定", "拖了很久的事", start - 10 * 86_400_000L)
        val now = start + 12 * 3_600_000L
        val first = RelationDue.stampKey(listOf(stale), now)
        assertEquals(first, RelationDue.stampKey(listOf(stale), now + 5 * 3_600_000L))
        // 隔三天必然跨档：同一条只会在跨档时再弹一次。
        assertNotEquals(first, RelationDue.stampKey(listOf(stale), now + 3 * 86_400_000L))
    }

    @Test fun `mixed batch still uses the daily stamp`() {
        val start = FactTimes.startOfDay(System.currentTimeMillis())
        val now = start + 15 * 3_600_000L
        val fresh = fact("约定", "今天见面", start + 9 * 3_600_000L)
        val stale = fact("约定", "上个月的约定", start - 40 * 86_400_000L)
        val stamp = RelationDue.stampKey(listOf(fresh, stale), now)
        assertEquals(stamp, RelationDue.stampKey(listOf(stale, fresh), now))
        assertNotEquals(stamp, RelationDue.stampKey(listOf(fresh, stale), now + 86_400_000L))
    }
}
