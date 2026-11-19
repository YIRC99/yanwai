package com.dogheadstrategist.chat.reply

import com.dogheadstrategist.chat.feature.WarReviewPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 我的风格：把我发过的话提炼成「我怎么说话」——一段画像 + 一份用户词库。
 *
 * 一次请求出两样东西，不拆成两次调用：
 * 两者都从同一批样本里读，拆开等于把同样的样本喂两遍，多花一次 token 和时间。
 *
 * 词库走增量：模型只报「这次新冒出哪些词」，库里已有的由调用方挡掉。
 * 否则每次整理都要把整个词库重打印一遍，词越攒越多，输出越来越长。
 */
object WarReviewProtocol {

    /** 一次整理的结果。 */
    data class Result(
        /** 画像正文，直接进 system prompt。 */
        val profile: String,
        /** 这次新发现的用词偏好。空表示模型没给出，或返回的是旧版纯文本。 */
        val newWords: List<WarReviewPrefs.VocabWord>,
    )

    fun payload(
        settings: ReplySettings,
        samples: List<String>,
        oldProfile: String,
        oldVocab: String,
    ): JSONObject {
        val instructions = """
            你是狗头军师的「我的风格」，负责从用户本人发过的消息里提炼他的说话方式。

            下面的样本都是这个人自己发出的消息。其中可能混入少量由 AI 起草、经他确认后发出的内容，
            请优先采纳带个人特征的表达，忽略像客服话术的句子。

            只返回一个 JSON 对象，不要 Markdown、不要代码块、不要任何额外说明：
            {"profile":"画像正文","new_words":[{"cat":"类别","word":"说法","avoid":["常见但不用的说法"]}]}

            ## profile 怎么写

            300~500 字的中文，描述这个人说话的特点。这段画像会直接放进回复生成的提示词，
            用来让 AI 模仿他的口吻。

            重点观察：
            1. 句子长短：一句话说完，还是拆成几条连着发
            2. 标点习惯：用不用句号、问号、省略号，爱不爱用省略号、破折号、波浪号
            3. 语气词：啊、吧、呗、哈、嗯、emmm 这类，出现在句尾还是句首
            4. 表情与符号：常用哪几个表情，有没有固定写法（比如连打几个哈）
            5. 称呼：怎么称呼对方
            6. 口头禅：反复出现的词或句式
            7. 情绪基调：偏冷淡、偏热络、爱开玩笑，还是就事论事
            8. 回复节奏：短促连发，还是一次说完整

            写法要求：
            - 用「你」指代这个人，例如「你习惯用短句，几乎不打句号」
            - 只写能从样本里看出来的，不要推测性格、职业、年龄、地域
            - 不做评价，不说「这样很好」或「建议改进」
            - 不要分点罗列，写成一段连贯的话

            ## new_words 怎么给

            不要列一堆「他爱说的词」。要归纳的是映射：**某一类意思 → 他习惯用哪个说法**。

            整理按这个顺序走：
            1. 先看每句话在表达什么——是在同意、在拒绝、在打招呼，还是在催人
            2. 把表达同一类意思的几条归到一起
            3. 再看这一类里，他反复用的是哪个说法，以及这一类常见但**他不用**的说法

            举例：样本里他表达同意时总说「没问题」，从没出现过「好的」「可以」「ok」，就报
            {"cat":"同意","word":"没问题","avoid":["好的","可以","ok"]}

            cat 怎么定：
            - **由你自己从样本里归纳**，不要套用现成的类目清单
            - 用最朴素的说法，2~4 个字，不要写成一整句解释
            - 同一类意思前后用同一个名字，别这次叫「同意」下次叫「答应」
            - 归纳不出来就不报这一类，不要硬塞

            word 怎么选：
            - 必须是他嘴里真实出现过的说法，不能是你替他想的
            - 不超过 8 个字，是个词或短语，不是整句话

            avoid 怎么给：
            - 这一类意思里常见、但他明显不用的说法，最多 3 个
            - 只列你有把握的，拿不准就给空数组 []
            - 常见说法够多但看不出他避开哪些，也给空数组

            严格要求：
            - 只报**不在「已有词库」里**的内容
            - 已有词库里是「同意=行吧」，这批样本里他改口说「没问题」了，
              就报 {"cat":"同意","word":"没问题"} 覆盖旧的，别两条并存
            - 宁可少报也别凑数：这类看不出偏好就别报
            - 一条都没有就返回 {"profile":"...","new_words":[]}，这是正常结果
        """.trimIndent()

        val evidence = JSONObject()
            .put("samples", JSONArray(samples.map { it.take(WarReviewPrefs.MAX_SAMPLE_LENGTH) }))
        if (oldProfile.isNotBlank()) {
            evidence.put("previous_profile", oldProfile)
            evidence.put("note", "这是上一次的画像。请在此基础上更新：稳定的特征保留，只有样本里明显体现变化的才改。")
        }
        if (oldVocab.isNotBlank()) {
            evidence.put("existing_words", oldVocab)
            evidence.put("existing_words_note", "这些词已经在库里了，new_words 里不要再出现。")
        }

        return JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", settings.maxTokens).put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", evidence.toString())))
    }

    fun parse(body: String): Result = try {
        val root = JSONObject(body)
        check(!root.has("error"))
        val choice = root.getJSONArray("choices").getJSONObject(0)
        check(choice.optString("finish_reason") == "stop")
        val message = choice.getJSONObject("message")
        check(message.isNull("refusal") || message.optString("refusal").isBlank())
        val content = message.getString("content").trim()
            .removePrefix("```json").removePrefix("```markdown").removePrefix("```")
            .removeSuffix("```").trim()

        val parsed = runCatching { JSONObject(content) }.getOrNull()
        val profile = parsed?.optString("profile")?.trim().orEmpty()
        // 兼容：模型不听话返回了纯文本画像时，仍然收下画像，只是这次没有新词
        val text = if (profile.isNotBlank()) profile else content
        val words = parsed?.optJSONArray("new_words")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val word = item.optString("word").trim()
                if (word.isEmpty()) null
                else {
                    val avoidArray = item.optJSONArray("avoid")
                    val avoid = if (avoidArray == null) emptyList() else
                        (0 until avoidArray.length()).mapNotNull {
                            avoidArray.optString(it).trim().takeIf { w -> w.isNotEmpty() }
                        }
                    WarReviewPrefs.VocabWord(
                        cat = item.optString("cat").trim(),
                        word = word,
                        avoid = avoid,
                        at = System.currentTimeMillis(),
                    )
                }
            }
        }.orEmpty()

        check(text.isNotBlank())
        Result(
            profile = text.take(WarReviewPrefs.MAX_PROFILE_LENGTH),
            newWords = words.map {
                it.copy(
                    cat = it.cat.take(WarReviewPrefs.MAX_VOCAB_CAT),
                    word = it.word.take(WarReviewPrefs.MAX_VOCAB_WORD),
                    avoid = it.avoid.map { word -> word.take(WarReviewPrefs.MAX_VOCAB_WORD) }
                        .filter { word -> word.isNotEmpty() }
                        .distinct()
                        .take(WarReviewPrefs.MAX_VOCAB_AVOID),
                )
            }.take(WarReviewPrefs.MAX_VOCAB),
        )
    } catch (_: Exception) {
        throw IllegalStateException("模型未返回完整的画像，请重试或换一个支持指令的聊天模型")
    }
}
