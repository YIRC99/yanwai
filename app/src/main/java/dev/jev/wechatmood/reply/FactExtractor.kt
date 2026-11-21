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
        val lines = usable.withIndex().map { (index, message) ->
            val stamp = if (message.time > 0) FactTimes.formatLocal(message.time, "MM-dd E HH:mm") else "时间未知"
            "[${index + 1}] $stamp ${message.speaker}: ${message.text.take(80)}"
        }
        // 从最新往回攒到预算上限：以前正序拼完再 take，超长时砍掉的反而是刚聊完的那几条。
        val transcript = buildList {
            var used = 0
            for (line in lines.asReversed()) {
                if (isNotEmpty() && used + line.length + 1 > MAX_CHARACTERS) break
                add(line)
                used += line.length + 1
            }
        }.asReversed().joinToString("\n")
        check(transcript.isNotBlank()) { "没有可用的聊天内容" }
        val messageIdAt: (Int) -> Long = { index -> usable.getOrNull(index - 1)?.id ?: 0 }
        val messageTimeAt: (Int) -> Long = { index -> usable.getOrNull(index - 1)?.time ?: 0 }
        // 推理模型会把预算花在思考上：6000 时实测 finish=length 且正文为空。
        // 空正文就加大预算再试一次；两次都空才算失败，由调用方决定不推进水位。
        var lastEmpty = ""
        for (tokens in intArrayOf(6000, 8000)) {
            val payload = JSONObject().put("model", settings.model).put("stream", false)
                .put("max_tokens", tokens).put("temperature", 0.2)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instructions(now, existing)))
                    .put(JSONObject().put("role", "user").put("content", transcript)))
            val facts = client.request(settings, payload, parse = { body ->
                parseOrNull(body, messageIdAt, messageTimeAt)
            })
            if (facts != null) {
                MoodLog.i("RELATION_FACT_EXTRACT total=${facts.size} tokens=$tokens " +
                    "timed=${facts.count { it.isTimed }} dated=${facts.count { it.occurredAt > 0 }} " +
                    "evidence=${facts.count { it.sourceChatMessageId > 0 }} " +
                    "replaced=${facts.count { it.replaces.isNotEmpty() }}")
                return facts
            }
            lastEmpty = "tokens=$tokens"
            MoodLog.w("RELATION_FACT_EMPTY 正文为空，换更大预算重试")
        }
        throw IllegalStateException("模型两次都没返回正文（$lastEmpty），这次整理不算数")
    }

    /** 供单测直接喂响应体用：解析不出来时按「没有事实」处理。 */
    internal fun parse(body: String, messageIdAt: (Int) -> Long = { 0 },
        messageTimeAt: (Int) -> Long = { 0 }): List<RelationFact> =
        parseOrNull(body, messageIdAt, messageTimeAt) ?: emptyList()

    /**
     * 解析响应体；返回 null 表示这次没有正文可用（被思考截断、或者不是数组）。
     * null 与「模型明确回了 []」是两件事：前者不能推进水位，否则这批消息再也不会重抽。
     */
    private fun parseOrNull(body: String, messageIdAt: (Int) -> Long,
        messageTimeAt: (Int) -> Long): List<RelationFact>? = runCatching {
        val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
        val content = choice.getJSONObject("message").getString("content").trim()
        // 只记长度、结构与结束原因，不落聊天原文：这份日志会被导出。
        MoodLog.i("RELATION_FACT_RAW len=${content.length} array=${content.indexOf('[') >= 0} " +
            "finish=${choice.optString("finish_reason")}")
        if (content.isBlank()) {
            MoodLog.w("RELATION_FACT_BUDGET 正文为空 finish=${choice.optString("finish_reason")}")
            return@runCatching null
        }
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start < 0 || end <= start) {
            MoodLog.w("RELATION_FACT_NO_ARRAY len=${content.length}")
            return@runCatching null
        }
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
            return """你是聊天里的信息整理助手。下面是一段微信聊天记录，每行形如「[序号] 发送时间 说话人: 内容」。
「我」是用户自己，其他说话人都是「对方」。只整理跟「我」和「对方」这两个人直接相关的内容。
只输出 JSON 数组，不要任何解释或 Markdown。
kind 只能是：工作、城市、家人、喜好、忌口、宠物、重要日期、约定、称呼。
每条字段：
- kind：上面九种之一
- text：不超过 30 个字的陈述句，例如「周六帮他搬家」
- evidence：这条取自哪条消息的序号，不确定填 0
- occurred_at：只有「约定」「重要日期」要填，格式 YYYY-MM-DDTHH:mm，只说了日期就写 YYYY-MM-DDT00:00，含糊说法（过两天、有空、改天）填空字符串
- replaces：这条替换了下面已有档案里的哪一条，就填那条的原句；约定改期、换了工作、搬了城市、改了称呼都算。没有就填空字符串

只写下面这五类，其它一律不写：
1. 说定了要做的事：谁答应在什么时候做什么，谁的请求我应下了（「周六帮你搬」「下周一交报告」「这周给你答复」）
2. 对方要我做、或要给我的事：让我带东西、催我回话、帮他问人、约好的见面和电话
3. 重要日期：生日、纪念日、考试、面试、体检、出发、截止
4. 会改变以后怎么相处的近况：换工作、搬家、换城市、家人生病、结婚离婚、养了新宠物
5. 明确的边界和称呼：不能提的事、忌口、该怎么称呼对方

不写这些：
- 泛泛的喜好和一次性日常（喜欢看电影、今天加班到十点、吃了什么），除非它直接带出上面的事
- 情绪、八卦、第三方自己的事，除非跟你们的约定有关
- 我自己的信息、重复的信息、推测出来的信息

判据：这条以后会不会让我漏掉一件要做的事，或者让我说错话？答不上来就不要写。
其它规则：
1. 不要编造，不要补充聊天里没说过的时间、地点和人。
2. 同一件事只写一次；同一件事换了说法或改了期，用 replaces 指向旧的那条。
3. 上面五类里有内容就必须写，哪怕只有一条；一条都没有才输出 []。
4. 换算「周五」「下周三」「明天」这类相对日期时，以该条消息自己的发送时间为基准，不要用当前时间；发送时间是「时间未知」时才用当前时间。

下面是我已经存下的档案，只用来判断哪条被替换，不要当成新的信息复述：
${existingBlock(existing)}
当前时间：$stamp。
示例：[{"kind":"约定","text":"周六帮他搬家","evidence":8,"occurred_at":"2026-10-24T09:00"},{"kind":"工作","text":"换到新公司做运营","evidence":3,"replaces":"在游戏公司做运营"},{"kind":"重要日期","text":"下周三面试","evidence":12,"occurred_at":"2026-10-28T00:00"}]"""
        }
    }
}
