package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.core.MessagePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Time arithmetic belongs to code. Blocks describe gaps, never an automatic emotion reset. */
object AnalysisState {
    fun build(input: AnalysisInput): JSONObject {
        val zone = runCatching { ZoneId.of(input.zoneId) }.getOrDefault(ZoneId.of("UTC"))
        fun local(time: Long): String? = if (time > 0) Instant.ofEpochMilli(time).atZone(zone).toOffsetDateTime().toString() else null
        fun gap(older: Long, newer: Long): Long? = if (older > 0 && newer >= older) (newer - older) / 60000 else null
        fun sameTurn(older: ContextMessage?, speaker: String, time: Long): Boolean = older != null &&
            older.speaker == speaker && older.createdAt > 0 && time >= older.createdAt && time - older.createdAt <= 120000
        fun crossDay(older: Long, newer: Long): Boolean? = if (gap(older, newer) != null)
            Instant.ofEpochMilli(older).atZone(zone).toLocalDate() != Instant.ofEpochMilli(newer).atZone(zone).toLocalDate() else null
        val selected = mutableListOf<ContextMessage>()
        var budget = MessagePolicy.MAX_CONTEXT_CHARACTERS
        var newer = input.createdAt
        var rejected = 0
        for (message in input.context.asReversed()) {
            if (selected.size >= MessagePolicy.MAX_CONTEXT_MESSAGES) break
            if (message.createdAt > 0 && newer > 0 && message.createdAt > newer) { rejected++; continue }
            val text = MessagePolicy.textOrNull(message.text)
            if (text == null) { rejected++; continue }
            if (text.length > budget) break
            budget -= text.length
            selected += message.copy(text = text)
            if (message.createdAt > 0) newer = message.createdAt
        }
        val context = selected.asReversed()
        var block = 0
        var turn = 0
        var previous: ContextMessage? = null
        val messages = JSONArray()
        for (message in context) {
            val interval = previous?.let { gap(it.createdAt, message.createdAt) }
            if (interval != null && interval >= 120) block++
            if (!sameTurn(previous, message.speaker, message.createdAt)) turn++
            messages.put(JSONObject().put("message_id", message.messageId.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("speaker", message.speaker).put("message", message.text)
                .put("sent_at_ms", message.createdAt.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("sent_at", local(message.createdAt) ?: JSONObject.NULL)
                .put("minutes_before_target", gap(message.createdAt, input.createdAt) ?: JSONObject.NULL)
                .put("different_day_from_target", crossDay(message.createdAt, input.createdAt) ?: JSONObject.NULL)
                .put("gap_from_previous_minutes", interval ?: JSONObject.NULL)
                .put("time_block", block).put("speaker_turn", turn))
            previous = message
        }
        val last = context.lastOrNull()
        val interval = last?.let { gap(it.createdAt, input.createdAt) }
        if (interval != null && interval >= 120) block++
        if (!sameTurn(last, input.speaker, input.createdAt)) turn++
        val coverage = input.coverage
        return JSONObject()
            .put("message", requireNotNull(MessagePolicy.textOrNull(input.text)) { "消息为空或超过 1000 字符" })
            .put("speaker", input.speaker).put("message_id", input.messageId.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("sent_at_ms", input.createdAt.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("sent_at", local(input.createdAt) ?: JSONObject.NULL).put("timezone", zone.id)
            .put("gap_from_previous_minutes", interval ?: JSONObject.NULL)
            .put("different_day_from_previous", last?.let { crossDay(it.createdAt, input.createdAt) } ?: JSONObject.NULL)
            .put("time_block", block).put("speaker_turn", turn).put("context", messages)
            .put("context_coverage", JSONObject().put("source", coverage.source).put("count", context.size)
                .put("scanned", coverage.scanned).put("omitted_media", coverage.omittedMedia)
                .put("unavailable", coverage.unavailable).put("omitted_text", coverage.omittedText)
                .put("invalid_time", coverage.invalidTime + rejected)
                .put("truncated", coverage.truncated || context.size != input.context.size)
                .put("oldest_sent_at", context.firstOrNull()?.let { local(it.createdAt) } ?: JSONObject.NULL)
                .put("latest_sent_at", last?.let { local(it.createdAt) } ?: JSONObject.NULL))
            .put("time_note", "判断目标消息发送时的表达，不推测阅读时的心理。间隔和跨天已由程序计算；" +
                "time_block 仅按两小时间隔分组，不代表新话题或消气。speaker_turn 仅把同一发送者两分钟内的连续文字分组。" +
                "旧情绪不能自动延续，隔夜也不能自动清零；当前明确重提的问题仍可能未解决。" +
                "时间 null 表示未知；context 只有目标之前的已读取文字，省略的图片语音和缺失历史不是无事发生。")
    }

    fun description(input: AnalysisInput): String {
        val state = build(input)
        val coverage = state.getJSONObject("context_coverage")
        val zone = ZoneId.of(state.getString("timezone"))
        val format = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)
        val times = state.getJSONArray("context").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).optLong("sent_at_ms", 0) }.filter { it > 0 }
        }
        val range = if (times.isEmpty()) "时间未知" else "${format.format(Instant.ofEpochMilli(times.first()))}—${format.format(Instant.ofEpochMilli(times.last()))}"
        val source = if (coverage.getString("source") == "loaded_page") "页面前文" else "提供前文"
        val incomplete = coverage.getBoolean("truncated") || listOf("omitted_media", "unavailable", "omitted_text", "invalid_time")
            .any { coverage.getInt(it) > 0 }
        val target = if (input.createdAt > 0) format.format(Instant.ofEpochMilli(input.createdAt)) else "时间未知"
        return "消息：$target · $source ${coverage.getInt("count")} 条（$range）" +
            (if (times.isNotEmpty() && times.size < coverage.getInt("count")) " · 部分时间未知" else "") +
            (if (incomplete) " · 有未读取或截断内容" else "")
    }
}
