package com.dogheadstrategist.chat.reply

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知摘要：把一条刚收到的微信消息提炼成通知的标题和正文。
 *
 * 群聊和单聊在通知里的形态不一样：
 *   单聊 —— title 是联系人，text 是正文
 *   群聊 —— title 是群名，text 是「张三: 正文」
 * 群里真正说话的人藏在 text 前缀里，所以在交给模型之前先拆出来，
 * 否则通知里只会显示群名，用户看不出是谁说的。
 */
object CommunicateProtocol {
    /** 总结很短，给再多 token 也是浪费，封顶 160。 */
    private const val MAX_TOKENS = 160

    /** 群聊前缀：「张三: 」「李四：」这种。冒号前后不限制空格。 */
    private val GROUP_PREFIX =
        Regex("^([^:：\\n]{1,20})[:：]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)

    data class Line(val left: String, val right: String)

    /**
     * 拆出群聊前缀。
     *
     * @return (说话人, 正文)。不是群聊时说话人为空，正文原样返回。
     */
    fun splitSpeaker(text: String): Pair<String, String> {
        val trimmed = text.trim()
        val match = GROUP_PREFIX.find(trimmed) ?: return "" to trimmed
        return match.groupValues[1].trim() to match.groupValues[2].trim()
    }

    fun payload(
        settings: ReplySettings,
        sender: String,
        text: String,
        maxChars: Int,
        maxLeft: Int,
        groupHint: Boolean? = null,
    ): JSONObject {
        val (speaker, body) = splitSpeaker(text)
        // 群聊只认 talker 的 @chatroom 后缀。拿不到 talker 时一律按单聊处理：
        // 以前退回「正文有没有冒号前缀」，单聊里「http://」「时间: ...」会被拆成
        // 假前缀，正文被剥掉一截，还会被当成群聊套上群规则，误判很常见。
        val isGroup = groupHint ?: false

        val groupRule = if (isGroup) {
            "这是群聊。speaker 才是说话的人，sender 是群名。left 必须用 speaker，不要用群名；群名一律不显示，标题放不下两个名字。"
        } else {
            "这是单聊。sender 就是对方的名字，left 从它提炼。"
        }

        val instructions = """
            你是狗头军师的「通知摘要」，负责把一条刚收到的微信消息提炼成超级岛上的两段文字。

            用户正忙着别的事，只能瞥一眼这个岛。所以这两段字要让他立刻判断：要不要现在回。

            必须只输出 JSON，不要代码块、不要解释、不要多余文字：
            {"left": "…", "right": "…"}

            $groupRule

            left 的要求：
            1. 不超过 $maxLeft 个字符（emoji 算一个）
            2. 不要直接照抄原文，去掉「xx群」「xx组」「xx工作群」这类无意义前缀
            3. 保留能让你认出「是谁」的那部分，姓名、职务称呼都行
            4. 末尾加一个和内容匹配的 emoji，只能从下面清单里挑，不要用清单外的（冷门或新出的字体画不出来，会变成黑框问号）：
               表情 😀 😄 😊 😉 😍 🤔 😅 😂 😢 😮 😴 😎 🥳 😇 😌 🙃 😳 😱
               手势 👍 👌 ✌️ 🙏 💪 👏 🤝 ✋ 👉 👈 👆 👇
               物品 📌 📅 📆 ⏰ ⏳ 📞 📎 📝 💡 🔑 📦 💰 🎁 📢 📷 🎵 📊 📈 📉 🗂️ 📁 📋 📖 ✏️
               符号 ✅ ❌ ⚠️ ❗ ❓ ⭕ 🔥 ⭐ 💯 🆗 🆕 🔔 🔕 ➡️ ⬆️ ⬇️
               天气 ☀️ 🌙 ⛅ 🌧️ ❄️ ⚡ 🌈 🌊 ☂️ 🌡️
               动物 🐶 🐱 🐼 🐷 🐔 🐟 🦋 🐝 🐦 🐢
               食物 🍚 🍜 🍎 ☕ 🍺 🍰 🎂 🍵 🍉 🍞
               活动 ⚽ 🎮 🎬 📚 🎉 🏆 🎯 🎤 🎨 🏃
               交通 🚗 🚄 ✈️ 🚲 🚀 🚌 🚕 🚢

            right 的要求：
            1. 不超过 $maxChars 个汉字，能更短就更好，8 个字以内最理想
            2. 概括正文的主要内容，相当于给这条消息写一个标题
            3. 不要编造原文没有的信息，不要「对方表示」「该消息称」这类转述腔
            4. 如果正文本身就很短，直接照抄原文，别硬凑
            5. 不要句号收尾，不要引号
            6. 去掉语气词、寒暄、表情符号
        """.trimIndent()

        val input = JSONObject()
            .put("sender", sender)
            .put("message", (if (isGroup) body else text).take(500))
        if (isGroup && speaker.isNotEmpty()) input.put("speaker", speaker)

        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", minOf(settings.maxTokens, MAX_TOKENS))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", input.toString())))
    }

    fun parse(body: String): Line = try {
        val root = JSONObject(body)
        check(!root.has("error"))
        val choice = root.getJSONArray("choices").getJSONObject(0)
        check(choice.optString("finish_reason") == "stop")
        val message = choice.getJSONObject("message")
        check(message.isNull("refusal") || message.optString("refusal").isBlank())
        val content = message.getString("content").trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(content)
        val left = json.optString("left").trim().trim('"', '「', '」', '“', '”')
        val right = json.optString("right").trim().trim('"', '「', '」', '“', '”', '。')
        check(left.isNotBlank() && right.isNotBlank())
        // 长度不在这里截：左边由配置决定，右边交给岛自己排
        Line(left, right)
    } catch (_: Exception) {
        throw IllegalStateException("模型没有返回可用的总结")
    }
}
