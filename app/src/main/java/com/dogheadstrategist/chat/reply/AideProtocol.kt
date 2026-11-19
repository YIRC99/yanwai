package com.dogheadstrategist.chat.reply

import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.MatterType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知代办：从一条微信消息里挑出值得记一笔的重要事项。
 *
 * 三种形态，由调用方按开关和本地预筛结果选：
 *
 * - [Mode.BRIEF]：只产出 left / right。payload **直接转发给 [CommunicateProtocol]**，
 *   和现在逐字节一致。这是回归基线——合并版出任何问题，切回这个模式就等于没改过。
 * - [Mode.MATTER_ONLY]：只产出 matter，不要求 left / right。通知摘要没开、只有通知代办在跑时用，
 *   模型可以把 token 全花在抽取上。
 * - [Mode.FULL]：一次产出三样。字段顺序是 left、right、matter——自回归解码下先出的字段
 *   质量更稳，万一 max_tokens 不够被截断，丢的是可降级的 matter，不是不能缺的 left / right。
 *
 * 解析一律宽容：整段 JSON 失败就做括号配对扫描，还失败就按字段名正则抠，
 * 抠不出来才降级。原则是「通知永不空白」——left / right 缺了用原始通知补，
 * matter 缺了就静默，绝不让通知代办的错误传染给通知摘要。
 */
object AideProtocol {
    /**
     * 输出预算一律跟随用户配置（设置里的「最大输出」）。
     *
     * 这里曾经每个场景各有一个更小的自定上限（160 / 260 / 按条数估算），
     * 本意是省 token、防模型话痨。但实际效果是：用户在面板上配了 1000，
     * 底层却只给 160，输出被截断，拿回半截 JSON，解析失败后界面显示
     * 「模型没给出可解析的结果」——数字对不上行为，问题还很难看出来。
     *
     * 只保留一个下限，防止配成 0 时请求直接失效。
     */
    private fun budgetOf(settings: ReplySettings): Int = settings.maxTokens.coerceAtLeast(256)

    /** content 太长会撑爆列表和小组件。 */
    const val MAX_CONTENT_CHARS = 60

    enum class Mode { BRIEF, MATTER_ONLY, FULL }

    /**
     * matter 的三种状态。区分它们是为了让调用方决定要不要重试：
     * 模型明确说「没有」（NONE）不该重试，解析失败（ABSENT）才值得再问一次。
     */
    enum class MatterState { PRESENT, NONE, ABSENT }

    /**
     * 一条提取结果。
     *
     * [importance] 是模型对「这条有多重要」的打分，0-10。本地再按类型门槛筛，
     * 而不是让模型知道门槛——告诉它门槛，它会往门槛上凑，分数就失去区分度了。
     *
     * -1 表示模型没给分。这种情况一律放行，不能当成 0——模型偶尔漏字段是常事，
     * 当成 0 就等于用门槛把所有此类消息永久挡在门外，用户还不知道被吞了。
     */
    data class MatterDraft(
        val typeKey: String,
        val content: String,
        val emoji: String,
        val importance: Int = -1,
    )

    data class Result(
        val left: String,
        val right: String,
        val matter: MatterDraft?,
        val matterState: MatterState,
    ) {
        val hasMatter: Boolean get() = matter != null
    }

    // ===================== 请求 =====================

    fun payload(
        settings: ReplySettings,
        sender: String,
        text: String,
        maxChars: Int,
        maxLeft: Int,
        groupHint: Boolean?,
        mode: Mode,
        types: List<MatterType>,
    ): JSONObject = when (mode) {
        // 基线：原样交给通知摘要，一个字节都不改
        Mode.BRIEF -> CommunicateProtocol.payload(settings, sender, text, maxChars, maxLeft, groupHint)
        Mode.MATTER_ONLY -> matterPayload(settings, sender, text, groupHint, types)
        Mode.FULL -> fullPayload(settings, sender, text, maxChars, maxLeft, groupHint, types)
    }

    /**
     * 关键用户段。
     *
     * 典型场景是监考表、值班表这类名单：整张表几十行，用户只关心自己那几行。
     * 没有这一段，模型会把名单里每个人的安排都记成一条，通知代办立刻变成噪音源。
     *
     * 空清单返回空串，调用方拼进去等于没拼——行为和加这个设置之前完全一致，
     * 老用户不会被新设置改变原有提取结果。
     */
    private fun keyUserSection(keyUsers: List<String>): String {
        if (keyUsers.isEmpty()) return ""
        val names = keyUsers.joinToString("、")
        return """

            ## 只保留与这些人相关的内容
            用户本人关心的人是：$names

            只提取明确指向上面某人的条目：他/她被安排、被要求、被点名、要去做或去参加的事。
            名单里其他人的安排一律判 null，即使内容本身看起来很具体、很像一条要事。
            判断标准是「这件事要上面某个人去做」，不是「这句话里恰好出现了这个名字」。
        """.trimEnd()
    }

    /** 类型清单的 prompt 片段。用户加的自定义类型会一起出现在这里。 */
    private fun typeSection(types: List<MatterType>): String {
        if (types.isEmpty()) return ""
        return types.joinToString("\n") { type ->
            val head = "- ${type.key}（${type.title}）：${type.desc}"
            // 示例这一行是输出规整的关键：只给描述，模型知道「找什么」但不知道「写多长」
            val example = type.outputExample.trim()
            if (example.isEmpty()) head else head + "\n  示例：" + example
        }
    }

    private fun userContent(sender: String, text: String, groupHint: Boolean?, max: Int = 500): String {
        val (speaker, body) = CommunicateProtocol.splitSpeaker(text)
        // 群聊只认 talker 的 @chatroom 后缀，拿不到就按单聊——不再靠冒号前缀猜。
        val isGroup = groupHint ?: false
        val input = JSONObject()
            .put("sender", sender)
            .put("message", (if (isGroup) body else text).take(max))
        if (isGroup && speaker.isNotEmpty()) input.put("speaker", speaker)
        return input.toString()
    }

    private fun matterPayload(
        settings: ReplySettings,
        sender: String,
        text: String,
        groupHint: Boolean?,
        types: List<MatterType>,
    ): JSONObject {
        val instructions = """
            你是狗头军师的「通知代办」，负责从一条刚收到的微信消息里，挑出值得记一笔的重要事项。

            必须只输出 JSON，不要代码块、不要解释：
            {"matter": {"type":"类型标识","content":"要记住的内容","emoji":"单个emoji","importance":0}}
            没有值得记的信息时输出：{"matter": null}

            ## 可识别的类型
            ${typeSection(types)}

            ## 判断标准
            1. type 必须严格使用上面列出的标识，不要自创
            2. content 一句话把要点说清，不超过 $MAX_CONTENT_CHARS 字，只保留要照做/照抄的内容
            3. emoji 选一个和内容相符的
            4. 只有确实需要用户记住或去做的事才算——寒暄、闲聊、表情、「好的」「收到」一律输出 null
            5. 拿不准的时候输出 null。记错一件不存在的事，比漏记更让人困扰
            6. importance 是这条信息对用户的重要程度，0-10 的整数：
                    9-10 不处理会有后果：马上要开的会、截止就在眼前、必须照抄才能取件的码
                    6-8  需要记住但不必立刻动手：明天的安排、要带的东西、明确的待办
                    3-5  知道了更好，忘了影响不大：一般性通知、模糊的约定
                    0-2  基本没有信息量
                    打分只看「漏掉这条会不会给用户添麻烦」，不看消息长短，也不看语气强烈程度
        """.trimIndent()

        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", budgetOf(settings))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", userContent(sender, text, groupHint))))
    }

    private fun fullPayload(
        settings: ReplySettings,
        sender: String,
        text: String,
        maxChars: Int,
        maxLeft: Int,
        groupHint: Boolean?,
        types: List<MatterType>,
    ): JSONObject {
        val (speaker, body) = CommunicateProtocol.splitSpeaker(text)
        // 同上：没有 talker 依据就按单聊，别用冒号猜。
        val isGroup = groupHint ?: false

        val groupRule = if (isGroup) {
            "这是群聊。speaker 才是说话的人，sender 是群名。left 必须用 speaker，不要用群名；群名一律不显示，标题放不下两个名字。"
        } else {
            "这是单聊。sender 就是对方的名字，left 从它提炼。"
        }

        val instructions = """
            你是狗头军师的「通知摘要」兼「通知代办」，一条消息要做两件事：
            先把它压成超级岛上的一行字，再判断里面有没有值得记一笔的重要事项。

            必须只输出 JSON，不要代码块、不要解释：
            {"left": "…", "right": "…", "matter": {"type":"类型标识","content":"要记住的内容","emoji":"单个emoji","importance":0}}
            没有重要事项时 matter 输出 null，left / right 照常填。

            $groupRule

            left 的要求：
            1. 不超过 $maxLeft 个字符（emoji 算一个）
            2. 不要直接照抄原文，去掉「xx群」「xx组」「xx工作群」这类无意义前缀
            3. 保留能让你认出「是谁」的那部分，姓名、职务称呼都行
            4. 末尾加一个和内容匹配的 emoji

            right 的要求：
            1. 不超过 $maxChars 个汉字，能更短就更好，8 个字以内最理想
            2. 概括正文的主要内容，相当于给这条消息写一个标题
            3. 不要编造原文没有的信息，不要「对方表示」「该消息称」这类转述腔
            4. 如果正文本身就很短，直接照抄原文，别硬凑
            5. 不要句号收尾，不要引号
            6. 去掉语气词、寒暄、表情符号

            ## 可识别的类型（只影响 matter，不影响 left / right）
            ${typeSection(types)}

            matter 的判断标准：
            1. type 必须严格使用上面列出的标识，不要自创
            2. content 一句话说清，不超过 $MAX_CONTENT_CHARS 字
            3. 只有确实需要记住或去做的事才算；寒暄、闲聊、「好的」「收到」输出 null
            4. 拿不准的时候输出 null
            5. importance 是这条信息对用户的重要程度，0-10 的整数：
                    9-10 不处理会有后果：马上要开的会、截止就在眼前、必须照抄才能取件的码
                    6-8  需要记住但不必立刻动手：明天的安排、要带的东西、明确的待办
                    3-5  知道了更好，忘了影响不大：一般性通知、模糊的约定
                    0-2  基本没有信息量
                    打分只看「漏掉这条会不会给用户添麻烦」，不看消息长短，也不看语气强烈程度
            5. matter 只填写 JSON 里对应的字段，**不得影响 right 的归纳风格与长度**
        """.trimIndent()

        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", budgetOf(settings))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", userContent(sender, text, groupHint))))
    }

    // ===================== 解析 =====================

    /**
     * 解析响应体。
     *
     * [fallbackLeft] / [fallbackRight] 是调用方手里的原始通知文本，
     * left / right 抠不出来时直接用它兜底——通知宁可显示原文，也不能空着。
     */
    fun parse(
        body: String,
        mode: Mode,
        fallbackLeft: String = "",
        fallbackRight: String = "",
    ): Result {
        val content = extractContent(body)
        if (content.isBlank()) {
            return Result(fallbackLeft, fallbackRight, null, MatterState.ABSENT)
        }
        // BRIEF 走原解析器，保证和现在行为一致
        if (mode == Mode.BRIEF) {
            val line = runCatching { CommunicateProtocol.parse(body) }.getOrNull()
            return Result(
                left = line?.left ?: fallbackLeft,
                right = line?.right ?: fallbackRight,
                matter = null,
                matterState = MatterState.NONE,
            )
        }

        val root = findJsonObject(content)
        val left = pick(root, content, "left") ?: fallbackLeft
        val right = pick(root, content, "right") ?: fallbackRight
        val (draft, state) = parseMatter(root, content, mode)
        return Result(left, right, draft, state)
    }

    /** 从 OpenAI 响应体里取出 message.content。 */
    private fun extractContent(body: String): String = runCatching {
        val root = JSONObject(body)
        val choice = root.getJSONArray("choices").getJSONObject(0)
        // length = 输出被 max_tokens 截断。这种情况下 JSON 一定是残的，
        // 后面解析必然失败，但用户只会看到「模型没给结果」。先把真相记下来。
        val reason = choice.optString("finish_reason")
        if (reason.isNotBlank() && reason != "stop") {
            val text = choice.optJSONObject("message")?.optString("content").orEmpty()
            MoodLog.w("AIDE_TRUNCATED finish_reason=" + reason + " chars=" + text.length)
        }
        val message = choice.getJSONObject("message")
        message.optString("content").trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }.getOrDefault("")

    /**
     * 括号配对扫描。
     *
     * 比「抠第一个 { 到最后一个 }」稳：right 里带花括号（「会议{改到3点}」）
     * 或者模型多说了几句带括号的话时不会切歪。字符串里的括号不计数。
     */
    private fun findJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(start, index + 1)
                            return runCatching { JSONObject(candidate) }.getOrNull()
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 取一个字符串字段。JSON 解析成功就直读，失败则按字段名正则抠。
     *
     * 正则救援是实战里救回最多的一种情况：模型少了个逗号、多说了一句
     * 「好的，以下是结果：」，整段 JSON 就废了，但字段本身是完整的。
     */
    private fun pick(root: JSONObject?, text: String, name: String): String? {
        root?.let { obj ->
            if (obj.has(name) && !obj.isNull(name)) {
                val value = obj.optString(name).trim()
                if (value.isNotEmpty()) return value
            }
        }
        val regex = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val match = regex.find(text) ?: return null
        return unescape(match.groupValues[1]).trim().ifEmpty { null }
    }

    /**
     * 解析 matter。三态要分清，否则「模型明确说没有」和「解析失败」会被混为一谈，
     * 导致要么该重试的不重试，要么对确定没有的消息反复重问。
     */
    private fun parseMatter(
        root: JSONObject?,
        text: String,
        mode: Mode,
    ): Pair<MatterDraft?, MatterState> {
        if (mode == Mode.BRIEF) return null to MatterState.NONE

        // 根对象里明确是 null → 模型判断没有
        if (root != null && root.has("matter") && root.isNull("matter")) {
            return null to MatterState.NONE
        }

        val obj = root?.optJSONObject("matter") ?: run {
            val nested = findJsonObject(text.substringAfter("\"matter\"", ""))
            if (nested != null && nested.has("type")) nested else null
        }

        if (obj == null) {
            // 整段里有 "matter": 的字样也算模型明确表态
            return if (text.contains("\"matter\"\\s*:\\s*".toRegex())) {
                null to MatterState.NONE
            } else {
                null to MatterState.ABSENT
            }
        }

        val typeKey = obj.optString("type").trim()
        val content = obj.optString("content").trim().take(MAX_CONTENT_CHARS)
        if (typeKey.isEmpty() || content.isEmpty()) return null to MatterState.ABSENT
        val emoji = obj.optString("emoji").trim().ifBlank { "\uD83D\uDCCC" }
        // 模型没给分时记 -1（放行）。当成 0 会让高门槛类型把这类消息全挡掉
        val importance = obj.optInt("importance", -1).coerceIn(-1, 10)
        return MatterDraft(typeKey, content, emoji, importance) to MatterState.PRESENT
    }

    // ===================== 批量 =====================

    /**
     * 一次送多条通知。
     *
     * 模型按 `i` 回填，返回和 [notices] 等长的结果列表——对不齐的话调用方没法
     * 知道哪条提取到了。所以这里要求模型「每条输入都必须有一条输出」，
     * 没有要事的显式给 null，而不是省略。
     */
    fun batchPayload(
        settings: ReplySettings,
        notices: List<com.dogheadstrategist.chat.feature.PendingNotice>,
        types: List<MatterType>,
        keyUsers: List<String> = emptyList(),
    ): JSONObject {
        val instructions = """
            你是狗头军师的「通知代办」，从下面几条微信消息里挑出值得记一笔的重要事项。

            必须只输出 JSON，不要代码块、不要解释：
            {"results":[{"i":0,"type":"time","content":"明天15:00 部门例会","emoji":"📅","importance":7},{"i":1,"type":}]}

            ## 可识别的类型
            ${typeSection(types)}${keyUserSection(keyUsers)}

            ## 规则
            1. i 是消息序号，必须和输入一一对应，不要漏项也不要重排
            2. type 必须严格使用上面列出的标识，不要自创
            3. content 一句话说清要点，不超过 $MAX_CONTENT_CHARS 字，只保留要照做或照抄的内容
            4. emoji 选一个和内容相符的
            5. 你的职责是过滤，宁可漏也不要编。只有消息里明确出现了时间、地点、待办、金额、
               要照抄的码或明确约定时，才产出条目；靠猜测补出来的内容会让用户白白记错一件事
            6. 这条消息没有值得记的内容时，输出 {"i":序号,"type":}，不要省略这一项
            7. 寒暄、闲聊、「好的」「收到」「哈哈哈」一律判为 null
            8. 拿不准的时候判 null。记错一件不存在的事，比漏记更让人困扰
            9. importance 是这条信息对用户的重要程度，0-10 的整数。判 null 的项不需要这个字段：
                    9-10 不处理会有后果：马上要开的会、截止就在眼前、必须照抄才能取件的码
                    6-8  需要记住但不必立刻动手：明天的安排、要带的东西、明确的待办
                    3-5  知道了更好，忘了影响不大：一般性通知、模糊的约定
                    0-2  基本没有信息量
                    打分只看「漏掉这条会不会给用户添麻烦」，不看消息长短，也不看语气强烈程度
        """.trimIndent()

        val input = JSONArray()
        for (index in notices.indices) {
            val notice = notices[index]
            input.put(
                JSONObject()
                    .put("i", index)
                    .put("from", notice.sender)
                    .put("text", notice.text.take(300))
            )
        }

        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", budgetOf(settings))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", input.toString())))
    }

    /**
     * 图片识别的 payload。
     *
     * 和 [batchPayload] 共用同一份类型清单和同一套输出 schema（{"results":[...]}），
     * 所以解析可以直接走 parseBatch(body, 1)，落库走同一条 MatterStore 通道，
     * 连统计都还是 ai_stats_record。区别只有输入源：那边是一批通知文本，
     * 这边是一张图。
     *
     * 图片版必须单独写规则，不能复用文本那份：图里有头像、昵称、时间戳、
     * 输入框、按钮，还有对方说的话。模型很容易把别人的约定也当成用户的事，
     * 所以规则 5~7 专门盯着这一层。
     */
    fun picturePayload(
        settings: ReplySettings,
        types: List<MatterType>,
        imageBase64: String,
        senderHint: String = "",
        mimeType: String = "image/jpeg",
        keyUsers: List<String> = emptyList(),
    ): JSONObject {
        val instructions = """
            你是狗头军师的「通知代办」，从用户提供的一张微信聊天截图或图片里，挑出值得记一笔的重要事项。

            必须只输出 JSON，不要代码块、不要解释：
            {"results":[{"i":0,"type":"time","content":"明天15:00 部门例会","emoji":"📅","importance":7}]}

            ## 可识别的类型
            ${typeSection(types)}${keyUserSection(keyUsers)}

            ## 规则
            1. 最多产出一条结果，i 固定为 0
            2. type 必须严格使用上面列出的标识，不要自创
            3. content 一句话说清要点，不超过 $MAX_CONTENT_CHARS 字，只保留要照做或照抄的内容
            4. emoji 选一个和内容相符的
            5. 只提取与用户本人相关的内容：要求用户去做的事、用户要赴的约、要照抄的码或金额
            6. 忽略所有界面元素：头像、昵称、时间戳、输入框、按钮、系统提示
            7. 忽略对话框里其他人的闲聊和他人之间的约定，那不是用户要办的事
            8. 图片里没有值得记的内容时，输出 {"results":[]}
            9. 你的职责是过滤，宁可漏也不要编。靠猜测补出来的内容会让用户白白记错一件事
            10. 拿不准就判空。记错一件不存在的事，比漏记更让人困扰
            11. importance 是这条信息对用户的重要程度，0-10 的整数：
                        9-10 不处理会有后果：马上要开的会、截止就在眼前、必须照抄才能取件的码
                        6-8  需要记住但不必立刻动手：明天的安排、要带的东西、明确的待办
                        3-5  知道了更好，忘了影响不大：一般性通知、模糊的约定
                        0-2  基本没有信息量
        """.trimIndent()

        // content 用数组形式，这是 OpenAI 兼容协议里多模态输入的标准写法
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", pictureHint(senderHint)))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:" + mimeType + ";base64," + imageBase64),
                ),
            )

        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", budgetOf(settings))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", content)))
    }

    private fun pictureHint(sender: String): String =
        if (sender.isBlank()) "请看这张图片，提取其中值得记的事。"
        else "图片来自「$sender」。请看这张图片，提取其中值得记的事。"

    /**
     * 解析批量结果。
     *
     * 返回长度恒等于 [count] 的列表：提取到的位置放 [MatterDraft]，
     * 模型判空的放空草稿（typeKey 为空），整个字段没解析出来的放 null。
     * 调用方据此区分「明确没有」和「丢失了」——前者可以移出队列，后者要留着重试。
     */
    fun parseBatch(body: String, count: Int): List<MatterDraft?> {
        if (count <= 0) return emptyList()
        val content = extractContent(body)
        val empty = List<MatterDraft?>(count) { null }
        if (content.isBlank()) return empty

        // 先找 results 数组。整段解析失败时退回正则抠每个 {"i":N,...}
        val root = findJsonObject(content)
        val array = root?.optJSONArray("results")
            ?: runCatching { JSONArray(content.substring(content.indexOf('['), content.lastIndexOf(']') + 1)) }.getOrNull()

        val filled = arrayOfNulls<MatterDraft>(count)
        if (array != null) {
            // 空数组是模型明确说「这里没有值得记的事」，不是解析失败。
            // 早先这里直接返回全 null，调用方会当成「模型没按格式回」，
            // 于是明明判了空的图也提示「没给出可解析的结果」。
            if (array.length() == 0) {
                for (slot in 0 until count) filled[slot] = MatterDraft("", "", "")
                return filled.toList()
            }
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val slot = obj.optInt("i", index)
                if (slot !in 0 until count) continue
                val typeKey = obj.optString("type").trim()
                if (typeKey.isEmpty() || typeKey.equals("", true) || obj.isNull("type")) {
                    // 模型明确判空：给一个空草稿，调用方据此把这条移出队列
                    filled[slot] = MatterDraft("", "", "")
                    continue
                }
                val text = obj.optString("content").trim().take(MAX_CONTENT_CHARS)
                if (text.isEmpty()) continue
                val emoji = obj.optString("emoji").trim().ifBlank { "\uD83D\uDCCC" }
                filled[slot] = MatterDraft(typeKey, text, emoji, obj.optInt("importance", -1).coerceIn(-1, 10))
            }
            return filled.toList()
        }

        // 整个数组都抠不出来时，按 "i":N 逐个救
        val itemRegex = Regex("\\{\\s*\"i\"\\s*:\\s*(\\d+)((?:[^{}]|\\{[^{}]*\\})*)\\}")
        var rescued = 0
        for (match in itemRegex.findAll(content)) {
            val slot = match.groupValues[1].toIntOrNull() ?: continue
            if (slot !in 0 until count) continue
            val chunk = match.groupValues[0]
            val typeKey = pickIn(chunk, "type") ?: continue
            if (typeKey.equals("", true)) {
                filled[slot] = MatterDraft("", "", "")
                rescued++
                continue
            }
            val text = pickIn(chunk, "content")?.take(MAX_CONTENT_CHARS) ?: continue
            if (text.isEmpty()) continue
            filled[slot] = MatterDraft(
                typeKey, text, pickIn(chunk, "emoji") ?: "\uD83D\uDCCC",
                pickInInt(chunk, "importance") ?: -1,
            )
            rescued++
        }
        if (rescued == 0) {
            // 只记长度等于什么都不知道。把开头一小段打出来，下次出问题时
            // 才能分清是模型说了废话、换了个 JSON 结构，还是压根没按格式回。
            MoodLog.w("AIDE_PARSE_BATCH_FAILED len=" + content.length +
                " head=" + content.take(200).replace("\n", " "))
        }
        return filled.toList()
    }

    /** 从片段里抠整数字段，抠不到返回 null。 */
    private fun pickInInt(chunk: String, name: String): Int? =
        Regex("\"" + name + "\"\\s*:\\s*(\\d{1,2})").find(chunk)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 10)

    /** 从一个片段里抠字符串字段。 */
    private fun pickIn(chunk: String, name: String): String? {
        val regex = Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val match = regex.find(chunk) ?: return null
        return unescape(match.groupValues[1]).trim().ifEmpty { null }
    }

    /** 反转义正则抠出来的 JSON 字符串。 */
    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch != '\\' || index + 1 >= raw.length) {
                builder.append(ch)
                index++
                continue
            }
            when (val next = raw[index + 1]) {
                'n' -> builder.append('\n')
                't' -> builder.append('\t')
                'r' -> builder.append('\r')
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                '/' -> builder.append('/')
                'u' -> {
                    val hex = raw.substring(index + 2, minOf(index + 6, raw.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        builder.append(code.toChar())
                        index += 6
                        continue
                    }
                    builder.append(next)
                }
                else -> builder.append(next)
            }
            index += 2
        }
        return builder.toString()
    }
}
