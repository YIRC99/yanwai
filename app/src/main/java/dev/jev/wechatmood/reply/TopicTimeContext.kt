package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class TopicOccasion(val name: String, val date: String, val daysAway: Int)
data class TopicTimeContext(val now: ZonedDateTime, val lunarDate: String? = null,
    val occasions: List<TopicOccasion> = fixedOccasions(now.toLocalDate())) {
    val date get() = now.toLocalDate().toString()
    fun toJson(): JSONObject = JSONObject().put("date", date)
        .put("local_datetime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).put("timezone", now.zone.id)
        .put("weekday", listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[now.dayOfWeek.value - 1])
        .put("weekend", now.dayOfWeek.value >= 6)
        .put("time_of_day", when (now.hour) { in 0..5 -> "凌晨"; in 6..11 -> "上午"; in 12..17 -> "下午"; else -> "晚上" })
        .put("lunar_date", lunarDate ?: JSONObject.NULL)
        .put("occasions", JSONArray(occasions.map { JSONObject().put("name", it.name).put("date", it.date).put("days_away", it.daysAway) }))
        .put("source", "设备本地时钟与历法，仅作聊天背景，不代表所在地、放假安排或对方作息")

    companion object {
        private val fixed = mapOf("01-01" to "元旦", "02-14" to "情人节", "03-08" to "妇女节", "05-01" to "劳动节",
            "06-01" to "儿童节", "10-01" to "国庆节", "12-24" to "平安夜", "12-25" to "圣诞节")
        fun fixedOccasions(date: LocalDate): List<TopicOccasion> = (0..14).mapNotNull { offset ->
            val day = date.plusDays(offset.toLong())
            fixed[day.format(DateTimeFormatter.ofPattern("MM-dd"))]?.let { TopicOccasion(it, day.toString(), offset) }
        }
    }
}
