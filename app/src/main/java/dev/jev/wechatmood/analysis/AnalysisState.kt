package dev.jev.wechatmood.analysis

import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ContextMessage
import dev.jev.wechatmood.core.MessagePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import dev.jev.wechatmood.voice.VoiceState

/** Time arithmetic belongs to code. Blocks describe gaps, never an automatic emotion reset. */
object AnalysisState {
    fun build(input: AnalysisInput): JSONObject {
        require(input.voiceState != VoiceState.WAITING && input.context.none { it.voiceState == VoiceState.WAITING }) {
            "语音尚未完成转写"
        }
        require(input.voice == null || input.voiceState == VoiceState.READY) { "目标语音未能转写" }
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
                .put("message_source", if (message.voice != null) "voice_transcript" else "text")
                .put("voice_state", message.voiceState.name)
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
            .put("message_source", if (input.voice != null) "voice_transcript" else "text")
            .put("speaker", input.speaker).put("message_id", input.messageId.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("sent_at_ms", input.createdAt.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("sent_at", local(input.createdAt) ?: JSONObject.NULL).put("timezone", zone.id)
            .put("gap_from_previous_minutes", interval ?: JSONObject.NULL)
            .put("different_day_from_previous", last?.let { crossDay(it.createdAt, input.createdAt) } ?: JSONObject.NULL)
            .put("time_block", block).put("speaker_turn", turn).put("context", messages)
            .put("context_coverage", JSONObject().put("source", coverage.source).put("count", context.size)
                .put("scanned", coverage.scanned).put("omitted_media", coverage.omittedMedia)
                .put("unavailable", coverage.unavailable).put("omitted_text", coverage.omittedText)
                .put("unavailable_voice", coverage.unavailableVoice)
                .put("invalid_time", coverage.invalidTime + rejected)
                .put("truncated", coverage.truncated || context.size != input.context.size)
                .put("oldest_sent_at", context.firstOrNull()?.let { local(it.createdAt) } ?: JSONObject.NULL)
                .put("latest_sent_at", last?.let { local(it.createdAt) } ?: JSONObject.NULL))
            .put("time_note", "判断目标消息发送时的表达，不推测阅读时的心理。间隔和跨天已由程序计算；" +
                "time_block 仅按两小时间隔分组，不代表新话题或消气。speaker_turn 仅把同一发送者两分钟内的连续文字分组。" +
                "旧情绪不能自动延续，隔夜也不能自动清零；当前明确重提的问题仍可能未解决。" +
                "时间 null 表示未知；context 只有目标之前的文字和语音转写，省略的媒体和缺失历史不是无事发生。" +
                "voice_transcript 仅为语音转成的文字，可能识别错误；不包含音调、哭腔、语速等声音证据。" +
                "voice_state 为 FAILED 的语音内容未知，不得据此推断态度、赞同或拒绝。")
    }

}
