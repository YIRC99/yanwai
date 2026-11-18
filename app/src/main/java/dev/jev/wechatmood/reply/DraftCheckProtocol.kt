package dev.jev.wechatmood.reply

import org.json.JSONArray
import org.json.JSONObject

/** 一条草稿体检结果：最多两条问题，外加一条保持原意的改写。 */
data class DraftCheck(val issues: List<String>, val rewrite: String?, val parsed: Boolean = true,
    val verdict: String? = null, val relates: Boolean? = null, val referenceCount: Int = 0,
    val responded: String? = null) {
    val isEmpty: Boolean get() = issues.isEmpty() && rewrite.isNullOrBlank()
}

object DraftCheckProtocol {
    private const val MAX_CONTEXT = 8
    private const val MAX_ISSUE = 40
    private const val MAX_REWRITE = 60

    private const val INSTRUCTIONS = """你是聊天回复检查器。draft 是用户打好、但还没发出去的一句话。
先看 focus（用户长按的那条，可能是图片），再看 last_incoming（对方最后说的那句）。
按下面四行输出，不要 JSON，不要 Markdown，不要额外解释：
回应：先写下你要回应的那条消息的开头一句原文（从 focus 或对方最后一句里摘，最多 15 字）
判断：一句话说明 draft 和这段对话的关系；focus 是图片时，先用十个字左右描述图里是什么
问题：最多两条，用「；」分隔；确实没有就写「无」
改写：一条能直接替换 draft 发出去的话，沿用 my_style 的句长和标点，最多 30 字；即使这句没问题，也要给一条更自然的说法
要求：
- 不要在回答里出现 draft、focus、last_incoming、my_style 这些字段名，用「你打的这句」「对方发的这张图」这类自然说法
- 不要推测对方心理；不要编造没出现过的信息
- 改写必须能直接发送，不要加「你可以说」这类前缀"""

    fun payload(settings: ReplySettings, context: ReplyContext, draft: String,
        relationship: ReplyRelationship, customRelationship: String = "", image: String? = null): JSONObject {
        val custom = relationship.customValue(customRelationship)
        val style = StyleProfileCache.of(context.talker, context.messages)
        val evidence = JSONObject()
            .put("relationship", JSONObject().put("id", relationship.id).put("label", relationship.displayLabel(custom)))
            .put("my_style", style?.summary() ?: JSONObject.NULL)
            // 单独把对方最后一句拎出来，模型更容易对比 draft 有没有接住。
            .put("last_incoming", context.messages.lastOrNull { it.speaker != "我" }?.text?.take(120) ?: JSONObject.NULL)
            // 长按的那条才是这一轮要回应的对象，图片/表情只有类型没有文字。
            .put("focus", context.targetMedia?.let {
                JSONObject().put("id", context.focusMessageId).put("type", "media").put("media", it)
                    .put("note", "对方发来一张$it")
            } ?: context.focusText?.let {
                JSONObject().put("id", context.focusMessageId).put("speaker", "对方").put("text", it.take(300))
            } ?: context.messages.firstOrNull { it.id == context.focusMessageId }?.let {
                JSONObject().put("id", it.id).put("speaker", it.speaker).put("text", it.text.take(120))
            } ?: JSONObject.NULL)
            .put("messages", JSONArray(context.messages.takeLast(MAX_CONTEXT).map {
                JSONObject().put("speaker", it.speaker).put("text", it.text.take(120))
            }))
            .put("draft", draft.take(500))
        dev.jev.wechatmood.core.MoodLog.i("DRAFT_FOCUS id=${context.focusMessageId} media=${context.targetMedia} " +
            "text=${context.focusText != null} msgs=${context.messages.size}")
        // 图片没取到时明确写出来，别让模型凭上下文编造图里是什么。
        if (image == null && context.targetMedia != null) {
            evidence.put("focus", JSONObject().put("id", context.focusMessageId).put("type", "media")
                .put("media", context.targetMedia)
                .put("note", "对方发来一张${context.targetMedia}，但这次没取到图片内容，只能按上下文判断，不要编造图里是什么"))
        }
        val userMessage = if (image != null) JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", evidence.toString()))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image))))
        else JSONObject().put("role", "user").put("content", evidence.toString())
        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 500).put("temperature", 0.1)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", INSTRUCTIONS))
                .put(userMessage))
    }

    private val LABEL = Regex("^(回应|判断|问题|毛病|改写|可以改成|建议)[：:](.*)$")

    /** 按「判断/问题/改写」三个标签切段，容忍冒号全半角与多行内容。 */
    private fun sections(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var current: String? = null
        val buffer = StringBuilder()
        fun flush() { current?.let { map[it] = buffer.toString().trim() }; buffer.clear() }
        for (line in text.lines()) {
            val match = LABEL.find(line.trim())
            if (match != null) {
                flush()
                current = match.groupValues[1]
                buffer.append(match.groupValues[2])
            } else if (current != null && line.isNotBlank() && current == "判断") {
                // 只有「判断」允许跨行；改写段被额外说明污染会直接发错内容。
                buffer.append(' ').append(line.trim())
            }
        }
        flush()
        return map
    }

    /** 模型连续失败时的简化请求：只要一句能直接发的改写。 */
    fun simplePayload(settings: ReplySettings, draft: String, focus: String?, lastIncoming: String?): JSONObject {
        val context = buildString {
            if (focus != null) append("对方发的这条：").append(focus.take(120)).append('\n')
            if (lastIncoming != null && lastIncoming != focus) append("对方最后说：").append(lastIncoming.take(120)).append('\n')
            append("我打好但还没发：").append(draft.take(200))
        }
        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 200).put("temperature", 0.3)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "你是聊天回复助手。根据上下文把用户打好但还没发出去的话改得更自然、更接得住对方，" +
                        "只说改写后的这一句，不要解释，不要引号，不超过 30 字，沿用我平时的句长和标点。"))
                .put(JSONObject().put("role", "user").put("content", context)))
    }

    internal fun simpleParse(body: String): String? = runCatching {
        val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("「", "」")?.removeSurrounding("\"")
            ?.take(MAX_REWRITE)?.takeIf { it.isNotBlank() && it != "无" }
    }.getOrNull()

    internal fun parse(body: String): DraftCheck = runCatching {
        val content = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content").trim()
        val sections = sections(content)
        val responded = sections["回应"]?.take(40)?.takeIf { it.isNotBlank() }
        val verdict = sections["判断"]?.take(120)?.takeIf { it.isNotBlank() }
        val problems = sections["问题"] ?: sections["毛病"]
        val issues = problems?.split("；", ";", "\n")?.map { it.trim() }?.filter { it.isNotEmpty() && it != "无" }
            ?.take(2)?.map { it.take(MAX_ISSUE) }.orEmpty()
        val rewrite = (sections["改写"] ?: sections["可以改成"] ?: sections["建议"])
            ?.trim()?.take(MAX_REWRITE)?.takeIf { it.isNotBlank() && it != "无" && it != "不需要" }
        dev.jev.wechatmood.core.MoodLog.i(
            "DRAFT_CHECK ok chars=${content.length} issues=${issues.size} rewrite=${rewrite != null} verdict=${verdict != null}")
        DraftCheck(issues, rewrite, verdict = verdict, responded = responded)
    }.onFailure {
        dev.jev.wechatmood.core.MoodLog.w("DRAFT_CHECK_PARSE_FAILED " + it.javaClass.simpleName)
    }.getOrDefault(DraftCheck(emptyList(), null, parsed = false))
}

/** 模型连续失败时的本地兜底：按字面重合度判断有没有接住对方最后一句。 */
object DraftFallback {
    fun hint(draft: String, lastIncoming: String?): String {
        if (lastIncoming.isNullOrBlank()) {
            return "模型没给出判断，上下文也不够；可以点「生成回复」参考几条，或直接说出你的想法"
        }
        val overlap = RelationFacts.similarity(draft, lastIncoming)
        return if (overlap < 0.1) {
            "这句和对方刚说的「${lastIncoming.take(20)}」接不上。要么直接回应他那句，要么点「生成回复」看几条参考再改"
        } else {
            "模型没给出判断；这句和对方上一句有重合，语气合不合适还得你自己定"
        }
    }
}

/** 脏话这类明显问题不依赖模型判断，本地先兜一层，避免模型放行。 */
object DraftGuard {
    private val HOSTILE = listOf("操你妈", "草泥马", "傻逼", "去死", "贱人", "废物", "神经病", "滚蛋", "他妈的", "你麻痹")

    fun hint(draft: String): String? {
        HOSTILE.firstOrNull { draft.contains(it) }?.let { hit ->
            return "这句话带攻击性（包含「$hit」），发出去容易把话说死；可以换成说自己感受的说法，比如「我现在挺生气的，晚点再说」"
        }
        if (looksLikeGibberish(draft)) {
            return "这看起来是一串字母或测试输入，和当前聊天没有关系；确认要发的话，先换成一句正常的话"
        }
        return null
    }

    /**
     * 中文用户随手敲的字母串（如 asdfgh）元音比例很低，直接本地判掉，
     * 不依赖模型，避免「没问题」这种明显不对的结论。
     */
    private fun looksLikeGibberish(draft: String): Boolean {
        if (draft.any { it in '\u4e00'..'\u9fff' }) return false
        val letters = draft.filter { it.isLetter() }
        if (letters.length >= 4) {
            val vowels = letters.count { it.lowercaseChar() in "aeiou" }
            return vowels.toDouble() / letters.length < 0.2
        }
        val digits = draft.filter { it.isDigit() }
        return digits.length >= 4 && digits.length == draft.count { it.isLetterOrDigit() }
    }
}

/** 同一份草稿在短时间内重复点只算一次，避免反复花钱。 */
class DraftCheckCache(private val windowMs: Long = 30L * 60 * 1000) {
    private data class Entry(val at: Long, val draft: String, val check: DraftCheck)
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized fun get(talker: String, draft: String, now: Long = System.currentTimeMillis()): DraftCheck? {
        val entry = entries[talker] ?: return null
        if (entry.draft != draft || now - entry.at > windowMs) return null
        return entry.check
    }

    @Synchronized fun put(talker: String, draft: String, check: DraftCheck, now: Long = System.currentTimeMillis()) {
        if (entries.size > 16) entries.clear()
        entries[talker] = Entry(now, draft, check)
    }

    @Synchronized fun clear() = entries.clear()
}
