package dev.jev.wechatmood.reply

import dev.jev.wechatmood.core.MoodLog
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关系事实提取：一次请求把最近聊天压成几条稳定事实。
 *
 * 只用用户自己配置的回复模型；输入是最近 100 条、最多 6000 字，每条带序号，
 * 输出必须是 JSON 数组，越界或非法项直接丢弃，不做二次解释。
 * 约定的相对时间在这里换算成绝对时间，之后不再依赖自然语言。
 */
class FactExtractor(private val client: ReplyHttpClient = ReplyHttpClient()) {
    suspend fun extract(settings: ReplySettings, messages: List<ReplyMessage>,
        existing: List<RelationFact> = emptyList(),
        now: Long = System.currentTimeMillis(), limit: Int = MAX_MESSAGES): List<RelationFact> {
        check(settings.isConfigured) { "请先配置回复模型" }
        val usable = messages.filter { it.text.isNotBlank() && !it.text.startsWith("【引用") }
            .takeLast(limit.coerceIn(10, MAX_MESSAGES))
        // 每条都带上原始发送时间：周一的「明天见」拖到周四整理，不能按整理当天换算成周五。
        val transcript = usable.withIndex()
            .joinToString("\n") { (index, message) ->
                val stamp = if (message.time > 0) FactTimes.formatLocal(message.time, "MM-dd E HH:mm") else "时间未知"
                "[${index + 1}] $stamp ${message.speaker}: ${message.text.take(80)}"
            }
            .take(MAX_CHARACTERS)
        check(transcript.isNotBlank()) { "没有可用的聊天内容" }
        val payload = JSONObject().put("model", settings.model).put("stream", false)
            // 推理模型会把预算花在思考上：800 时实测 finish=length 且 content 为空，给足空间。
            .put("max_tokens", 6000).put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions(now, existing)))
                .put(JSONObject().put("role", "user").put("content", transcript)))
        val messageIdAt: (Int) -> Long = { index -> usable.getOrNull(index - 1)?.id ?: 0 }
        val messageTimeAt: (Int) -> Long = { index -> usable.getOrNull(index - 1)?.time ?: 0 }
        return client.request(settings, payload, parse = { body -> this.parse(body, messageIdAt, messageTimeAt) })
            .also { MoodLog.i("RELATION_FACT_EXTRACT total=${it.size} timed=${it.count { fact -> fact.isTimed }} " +
                "dated=${it.count { fact -> fact.occurredAt > 0 }} evidence=${it.count { fact -> fact.sourceChatMessageId > 0 }} " +
                "replaced=${it.count { fact -> fact.replaces.isNotEmpty() }}") }
    }

    internal fun parse(body: String, messageIdAt: (Int) -> Long = { 0 },
        messageTimeAt: (Int) -> Long = { 0 }): List<RelationFact> = runCatching {
        val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
        val content = choice.getJSONObject("message").getString("content").trim()
        // 只记长度、结构与结束原因，不落聊天原文：这份日志会被导出。
        MoodLog.i("RELATION_FACT_RAW len=${content.length} array=${content.indexOf('[') >= 0} " +
            "finish=${choice.optString("finish_reason")}")
        if (content.isBlank() && choice.optString("finish_reason") == "length") {
            MoodLog.w("RELATION_FACT_BUDGET 输出为空且被截断，推理占满预算")
        }
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) return@runCatching emptyList()
        val array = JSONArray(content.substring(start, end + 1))
        (0 until minOf(array.length(), MAX_FACTS))
            .mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                // 模型给的是本地时间字符串，先换成毫秒再走统一解码，解码失败就当作没有时间语义。
                val occurredAt = FactTimes.parseLocal(item.optString("occurred_at"))
                val evidence = item.optInt("evidence", 0)
                // 模型指出的被替换旧句：只用来在合并时标作废，不写库。
                val replaces = item.optString("replaces").trim().take(RelationFact.MAX_TEXT)
                item.put("occurred_at", occurredAt)
                if (evidence > 0) {
                    item.put("source_chat_message_id", messageIdAt(evidence))
                    // 依据消息的时间让「这条事实有多旧」可判断，之前一直是 0。
                    item.put("evidence_time", messageTimeAt(evidence))
                }
                RelationFact.decode(item)?.copy(replaces = replaces)
            }
            .distinctBy { Triple(it.kind, it.text, it.occurredAt) }
    }.onFailure { MoodLog.w("FACT_PARSE_FAILED ${it.javaClass.simpleName}") }.getOrDefault(emptyList())

    companion object {
        // 100 条会让推理模型把 token 全花在思考上（实测 finish=length 且正文为空），50 条足够覆盖近期对话。
        const val MAX_MESSAGES = 50
        const val MAX_CHARACTERS = 6000
        const val MAX_FACTS = 12

        /** 把「周五」「下周三」换算成绝对日期需要一个明确基准，所以当前时间写进提示词。 */
        private const val MAX_EXISTING = 20

        /** 已有档案只给一句摘要，让模型判断“哪条被替换了”，不是让它照着抄。 */
        private fun existingBlock(existing: List<RelationFact>): String {
            val items = existing.filter { it.isOpen }.sortedByDescending { it.updatedAt }.take(MAX_EXISTING)
            if (items.isEmpty()) return "（暂无档案）"
            return items.joinToString("\n") { "- [" + it.kind + "] " + it.text }
        }

        private fun instructions(now: Long, existing: List<RelationFact> = emptyList()): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date(now))
            return """你是资料整理助手。下面是一段微信聊天记录，每行形如「[序号] 发送时间 说话人: 内容」。
「我」是用户自己，其他说话人都是「对方」。只提取关于对方的信息，不要提取我自己的。
只输出 JSON 数组，不要任何解释或 Markdown。
kind 只能是：工作、城市、家人、喜好、忌口、宠物、重要日期、约定、称呼。
每条字段：
- kind：上面九种之一
- text：不超过 30 个字的陈述句，例如「在游戏公司做运营」
- evidence：这条取自哪条消息的序号，不确定填 0
- occurred_at：只有「约定」「重要日期」要填，格式 YYYY-MM-DDTHH:mm，只说了日期就写 YYYY-MM-DDT00:00，含糊说法（过两天、有空、改天）填空字符串
- replaces：这条替换了下面已有档案里的哪一条，就填那条的原句；换了工作、搬了城市、改了称呼、约定改期都算。没有就填空字符串

下面是我已经存下的档案，只用来判断哪条被替换，不要当成新的信息复述：
${existingBlock(existing)}
当前时间：$stamp。
换算「周五」「下周三」「明天」这类相对日期时，以该条消息自己的发送时间为基准，不要用当前时间；发送时间是「时间未知」时才用当前时间。
提取范围，只要对方原话说过或明确确认过就算：
- 工作、职业、公司、所在城市、住的区域
- 家人、孩子、宠物、彼此怎么称呼
- 喜好、习惯、口味、忌口
- 明确的计划或约定，带时间的必须填 occurred_at
- 生日、纪念日这类重要日期
规则：
1. 不要推测、不要总结情绪、不要编造。
2. 同一件事只写一次。
3. 日常闲聊里提到的具体信息也算，例如「今天加班到十点」「我在杭州」「养了只猫」。
4. 有内容就一定要写，哪怕只有一条；真的没有可提取的内容才输出 []。
示例：[{"kind":"约定","text":"周五一起吃饭","evidence":8,"occurred_at":"2026-10-23T19:00"},{"kind":"工作","text":"在游戏公司做运营","evidence":3},{"kind":"宠物","text":"养了一只橘猫","evidence":15}]"""
        }
    }
}
