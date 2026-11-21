package dev.jev.wechatmood.reply

import java.util.Calendar

/** 一条参与聚合的消息，只保留统计需要的字段。 */
data class StatRecord(val time: Long, val send: Int, val type: Int, val content: String, val speaker: String = "")

/** 单个成员的发言概况。 */
data class MemberStat(
    val name: String,
    val count: Int,
    val media: Int,
    val voice: Int,
    val firstAt: Long,
    val lastAt: Long,
    val hours: List<Int>,
)

/** 群聊统计结果，只放聚合数字，不保留任何聊天正文。 */
data class GroupStats(
    val total: Int = 0,
    val senders: Int = 0,
    val mine: Int = 0,
    val media: Int = 0,
    val voice: Int = 0,
    val links: Int = 0,
    val topSenders: List<Pair<String, Int>> = emptyList(),
    val activeHours: List<Int> = emptyList(),
    val sampled: Boolean = false,
    val earliest: Long = 0,
    val latest: Long = 0,
    val members: List<MemberStat> = emptyList(),
) {
    val perCapita: Int get() = if (senders <= 0) 0 else total / senders

    fun lines(): List<String> = buildList {
        if (total == 0) {
            add("这段时间没有消息")
            return@buildList
        }
        add("共 $total 条 · $senders 人参与 · 人均 $perCapita 条")
        if (topSenders.isNotEmpty()) {
            add("最活跃：" + topSenders.joinToString("、") { "${it.first} ${it.second}" })
        }
        if (activeHours.isNotEmpty()) {
            add("活跃时段：" + activeHours.joinToString("、") { "${it} 点" })
        }
        val extras = buildList {
            if (media > 0) add("图片/表情 $media")
            if (voice > 0) add("语音 $voice")
            if (links > 0) add("链接 $links")
        }
        if (extras.isNotEmpty()) add(extras.joinToString(" · "))
        if (sampled) add("（消息较多，已按最近 5000 条采样）")
    }
}

object GroupStatsCalculator {
    const val SAMPLE_LIMIT = 5000
    private val LINK = Regex("https?://", RegexOption.IGNORE_CASE)

    fun compute(records: List<StatRecord>, sampled: Boolean = false): GroupStats {
        if (records.isEmpty()) return GroupStats(sampled = sampled)
        val members = LinkedHashMap<String, MutableMember>()
        val hours = HashMap<Int, Int>()
        var mine = 0
        var media = 0
        var voice = 0
        var links = 0
        var earliest = Long.MAX_VALUE
        var latest = 0L
        val calendar = Calendar.getInstance()
        for (record in records) {
            if (record.send == 1) mine++
            when (record.type) {
                3, 47 -> media++
                34 -> voice++
            }
            if (record.type == 1 && LINK.containsMatchIn(record.content)) links++
            calendar.timeInMillis = record.time
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            hours[hour] = (hours[hour] ?: 0) + 1
            if (record.time > 0) {
                if (record.time < earliest) earliest = record.time
                if (record.time > latest) latest = record.time
            }
            // 「我」也进成员表，否则会以为自己的消息没统计。
            val name = if (record.send == 1) "我" else displayName(record)
            val member = members.getOrPut(name) { MutableMember(name) }
            member.count++
            when (record.type) {
                3, 47 -> member.media++
                34 -> member.voice++
            }
            if (record.time > 0) {
                if (member.firstAt == 0L || record.time < member.firstAt) member.firstAt = record.time
                if (record.time > member.lastAt) member.lastAt = record.time
            }
            if (hour !in member.hours) member.hours += hour
        }
        val memberStats = members.values
            .sortedByDescending { it.count }
            .map { MemberStat(it.name, it.count, it.media, it.voice, it.firstAt, it.lastAt, it.hours.take(3).sorted()) }
        return GroupStats(
            total = records.size,
            // 参与人数只算对方：自己的条数单独用 mine 表示，混进来会多算一个人。
            senders = memberStats.count { it.name != "我" },
            mine = mine,
            media = media,
            voice = voice,
            links = links,
            topSenders = memberStats.take(5).map { it.name to it.count },
            activeHours = hours.entries.sortedByDescending { it.value }.take(3).map { it.key }.sorted(),
            sampled = sampled,
            earliest = if (earliest == Long.MAX_VALUE) 0 else earliest,
            latest = latest,
            members = memberStats,
        )
    }

    private class MutableMember(val name: String) {
        var count = 0
        var media = 0
        var voice = 0
        var firstAt = 0L
        var lastAt = 0L
        val hours = mutableListOf<Int>()
    }

    /** 昵称优先取 speaker，其次正文前缀；空白或纯符号统一成「匿名成员」。 */
    private fun displayName(record: StatRecord): String {
        val raw = record.speaker.trim().takeIf { it.isNotEmpty() }
            ?: record.content.substringBefore(":\n", "").takeIf { it.isNotEmpty() }
            ?: return UNKNOWN
        if (raw.none { it.isLetterOrDigit() }) return UNKNOWN
        return raw.take(24)
    }

    const val UNKNOWN = "匿名成员"

    /** 最大余数法：保证占比加总正好 100%。 */
    fun percents(values: List<Int>): List<Int> {
        val total = values.sum()
        if (total <= 0) return values.map { 0 }
        val exact = values.map { it * 100.0 / total }
        val floors = exact.map { kotlin.math.floor(it).toInt() }.toMutableList()
        var remaining = 100 - floors.sum()
        val order = exact.indices.sortedByDescending { exact[it] - floors[it] }
        var index = 0
        while (remaining > 0 && order.isNotEmpty()) {
            floors[order[index % order.size]] += 1
            remaining--
            index++
        }
        return floors
    }
}
