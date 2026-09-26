package dev.jev.wechatmood.reply

import android.icu.util.Calendar
import android.icu.util.ChineseCalendar
import android.icu.util.TimeZone
import java.time.ZonedDateTime

/** Android's bundled calendar supplies lunar dates without a network call or a stale year table. */
object TopicCalendar {
    fun current(now: ZonedDateTime = ZonedDateTime.now()): TopicTimeContext {
        val fixed = TopicTimeContext.fixedOccasions(now.toLocalDate())
        return runCatching {
            val lunar = ChineseCalendar(TimeZone.getTimeZone(now.zone.id))
            val festivals = mapOf((1 to 1) to "春节", (1 to 15) to "元宵节", (5 to 5) to "端午节",
                (7 to 7) to "七夕", (8 to 15) to "中秋节", (9 to 9) to "重阳节")
            var today = ""
            val upcoming = (0..14).mapNotNull { offset ->
                val day = now.plusDays(offset.toLong())
                lunar.timeInMillis = day.toInstant().toEpochMilli()
                val month = lunar.get(Calendar.MONTH) + 1
                val date = lunar.get(Calendar.DAY_OF_MONTH)
                val leap = lunar.get(Calendar.IS_LEAP_MONTH) == 1
                if (offset == 0) today = "${if (leap) "闰" else ""}${month}月${date}日"
                if (leap) null else festivals[month to date]?.let { TopicOccasion(it, day.toLocalDate().toString(), offset) }
            }
            TopicTimeContext(now, today, (fixed + upcoming).sortedBy { it.daysAway })
        }.getOrElse { TopicTimeContext(now) }
    }
}
