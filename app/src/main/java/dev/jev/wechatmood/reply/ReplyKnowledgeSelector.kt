package dev.jev.wechatmood.reply

/**
 * 把整份知识库裁成「本次对话真正相关的片段」。
 *
 * 动机：狗头军师全文 29.5–47K 字符，每次全量塞进 system 让单次输入轻松上两万 token。
 * 这里按当前对话的中文二元组做一次本地检索，只保留骨架 + 4 个高分段，
 * 目标 4.6K 字符以内，不联网、不引第三方依赖。
 */
object ReplyKnowledgeSelector {
    private const val MAX_CHARS = 3_500
    private const val HEAD_CHARS = 1_000
    private const val MAX_SECTIONS = 4
    private const val MIN_SECTION_CHARS = 120

    fun select(knowledge: String, sample: String): String {
        if (knowledge.length <= MAX_CHARS) return knowledge
        val head = knowledge.take(HEAD_CHARS)
        val keywords = keywords(sample)
        if (keywords.isEmpty()) return head + "\n\n（知识库较长，本次对话没有可用于检索的关键词）"
        val picked = sections(knowledge)
            .map { it to score(it, keywords) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(MAX_SECTIONS)
        if (picked.isEmpty()) return head + "\n\n（知识库较长，本次对话未命中相关片段，只保留通用原则）"
        val builder = StringBuilder(head)
        builder.append("\n\n（以下片段按本次对话检索得出，未必覆盖全部方法）")
        var budget = MAX_CHARS - head.length
        for ((text, _) in picked) {
            if (budget <= MIN_SECTION_CHARS) break
            val chunk = text.take(minOf(text.length, budget))
            builder.append("\n\n").append(chunk)
            budget -= chunk.length
        }
        return builder.toString()
    }

    private fun sections(knowledge: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (line in knowledge.lineSequence()) {
            if ((line.startsWith("## ") || line.startsWith("### ")) && current.isNotEmpty()) {
                result += current.toString().trim()
                current.setLength(0)
            }
            current.append(line).append('\n')
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result.filter { it.length >= MIN_SECTION_CHARS }
    }

    /** 中文取二元组，拉丁取整词；忽略虚词与纯标点。 */
    private fun keywords(sample: String): Set<String> {
        val text = sample.take(4_000)
        val result = mutableSetOf<String>()
        var cjk = StringBuilder()
        var latin = StringBuilder()
        fun flushCjk() {
            val s = cjk.toString()
            for (i in 0 until s.length - 1) {
                val bigram = s.substring(i, i + 2)
                if (bigram.any { it in STOP_CHARS }) continue
                result += bigram
            }
            cjk = StringBuilder()
        }
        fun flushLatin() {
            if (latin.length >= 2) result += latin.toString()
            latin = StringBuilder()
        }
        for (ch in text) {
            when {
                ch.code in 0x4e00..0x9fff -> {
                    flushLatin()
                    cjk.append(ch)
                }
                ch.code < 128 && ch.isLetterOrDigit() -> {
                    if (cjk.isNotEmpty()) flushCjk()
                    latin.append(ch.lowercaseChar())
                }
                else -> {
                    if (cjk.isNotEmpty()) flushCjk()
                    flushLatin()
                }
            }
        }
        if (cjk.isNotEmpty()) flushCjk()
        flushLatin()
        return result
    }

    private fun score(section: String, keywords: Set<String>): Int =
        keywords.count { section.contains(it) }

    private val STOP_CHARS = "的了是我你他她它在和就都也很不没这那什么啊吧呢吗着过把被让给对说".toSet()
}
