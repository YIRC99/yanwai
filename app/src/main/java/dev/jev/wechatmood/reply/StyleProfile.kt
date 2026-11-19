package dev.jev.wechatmood.reply

/**
 * 从「我」的历史消息里统计说话习惯。
 *
 * 只关心可量化的表面特征（句长、标点、语气词、emoji、开场字），
 * 不做任何心理推断，也不保存聊天原文：产出只有一段摘要和几条示例短语。
 */
data class StyleProfile(
    val messageCount: Int,
    val medianLength: Int,
    val p90Length: Int,
    val questionRate: Double,
    val exclaimRate: Double,
    val periodRate: Double,
    val particles: List<String>,
    val emojiRate: Double,
    val openers: List<String>,
    val samples: List<String>,
) {
    /** 给模型看的一段自然语言描述，控制在 300 字以内。 */
    fun summary(): String = buildString {
        append("我平时一条消息")
        append(if (medianLength <= 6) "很短，大多 $medianLength 个字左右"
        else if (medianLength <= 14) "大多 $medianLength 个字左右"
        else "偏长，通常 $medianLength 个字上下")
        if (p90Length >= medianLength + 8) append("，偶尔会说到 $p90Length 个字")
        append("。")
        when {
            periodRate < 0.1 -> append("句末基本不加句号。")
            periodRate > 0.6 -> append("句末习惯带句号。")
        }
        when {
            questionRate > 0.3 -> append("问句占比不低，常用反问或确认。")
            questionRate < 0.05 -> append("很少用问句。")
        }
        when {
            exclaimRate > 0.25 -> append("感叹号用得多。")
            exclaimRate < 0.03 -> append("几乎不用感叹号。")
        }
        if (particles.isNotEmpty()) append("常带「${particles.joinToString("」「")}」这类语气词。")
        when {
            emojiRate < 0.02 -> append("几乎不用表情。")
            emojiRate > 0.3 -> append("经常带表情。")
        }
    }.trim()

    /** 注入模型时用：摘要 + 示例短语，都控制在很短的量级。 */
    fun hint(): String = summary()
}

object StyleAnalyzer {
    /** 出现过的语气词，按命中次数取前几个，避免把偶发词当习惯。 */
    private val PARTICLES = listOf("嗯", "啊", "呀", "哈", "嘛", "哦", "昂", "诶", "唉", "嘿", "啦", "咯", "呗", "嗷", "嘞", "哟", "唉", "哇")
    private const val MIN_MESSAGES = 8
    private const val MAX_SAMPLE_LENGTH = 24

    fun analyze(messages: List<ReplyMessage>): StyleProfile? {
        val mine = messages.filter { it.speaker == "我" }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() && it.length <= 200 && !it.startsWith("【引用") }
        if (mine.size < MIN_MESSAGES) return null
        val lengths = mine.map { it.codePointCount(0, it.length) }.sorted()
        val median = lengths[lengths.size / 2]
        val p90 = lengths[minOf(lengths.size - 1, (lengths.size * 9) / 10)]
        val question = mine.count { '?' in it || '？' in it }.toDouble() / mine.size
        val exclaim = mine.count { '!' in it || '！' in it }.toDouble() / mine.size
        val period = mine.count { it.endsWith("。") || it.endsWith(".") || it.endsWith("…") }.toDouble() / mine.size
        val emoji = mine.count { hasEmoji(it) }.toDouble() / mine.size
        val particles = PARTICLES.map { particle -> particle to mine.sumOf { countOf(it, particle) } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second }
            .take(3)
            .map { it.first }
        val openers = mine.mapNotNull { openerOf(it) }
            .groupingBy { it }.eachCount()
            .filter { it.value >= 3 }
            .entries.sortedByDescending { it.value }
            .take(2)
            .map { it.key }
        val samples = mine.filter { it.length <= 40 && !it.contains("http") }
            .sortedBy { kotlin.math.abs(it.codePointCount(0, it.length) - median) }
            .distinct()
            .take(3)
            .map { it.take(MAX_SAMPLE_LENGTH) }
        return StyleProfile(mine.size, median, p90, question, exclaim, period, particles, emoji, openers, samples)
    }

    private fun countOf(text: String, piece: String): Int {
        var count = 0
        var index = text.indexOf(piece)
        while (index >= 0) {
            count++
            index = text.indexOf(piece, index + piece.length)
        }
        return count
    }

    private fun hasEmoji(text: String): Boolean = text.codePoints().anyMatch { code ->
        code in 0x1F300..0x1FAFF || code in 0x2600..0x27BF || code in 0x1F000..0x1F0FF
    }

    /** 开场只看第一个字，超过两字的短语意义不大。 */
    private fun openerOf(text: String): String? {
        val first = text.take(1)
        return first.takeIf { it[0].isLetter() && it !in "我你他她它" }
    }
}

/** 同一会话的画像只在内存里复用，消息集合变化才重算。 */
object StyleProfileCache {
    private const val LIMIT = 32
    private data class Entry(val size: Int, val lastId: Long, val profile: StyleProfile?)
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > LIMIT
    }

    fun of(talker: String, messages: List<ReplyMessage>): StyleProfile? {
        if (talker.isBlank()) return null
        val mine = messages.count { it.speaker == "我" }
        val lastId = messages.lastOrNull { it.speaker == "我" }?.id ?: 0
        synchronized(cache) {
            cache[talker]?.let { if (it.size == mine && it.lastId == lastId) return it.profile }
        }
        val profile = StyleAnalyzer.analyze(messages)
        synchronized(cache) { cache[talker] = Entry(mine, lastId, profile) }
        return profile
    }

    @Synchronized fun clear() = cache.clear()
}
