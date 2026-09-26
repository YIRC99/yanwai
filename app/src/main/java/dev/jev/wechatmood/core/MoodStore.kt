package dev.jev.wechatmood.core

import java.util.concurrent.ConcurrentHashMap

/**
 * 分析结果。
 *
 * [label] 是给界面看的短标签（比如「开心」「生气」「敷衍」），
 * [score] 是情绪强度 -1.0（负面）到 1.0（正面），[raw] 留着排查模型返回。
 */
data class Mood(
    val label: String,
    val score: Double,
    val risk: Int,
    val raw: String,
    val detail: String = label,
)

/**
 * 分析结果缓存。
 *
 * 三条约束决定了它的形状：
 * 1. 同一条消息及上下文不能重复请求模型 —— 用会话、消息身份和完整输入做键。
 * 2. 界面线程要能**立刻**拿到结果，不能等网络 —— 所以是「先占位、后填充」，
 *    装饰器拿到 null 就先不画，异步补上再通知刷新。
 * 3. 微信进程可能被回收 —— 只放内存，不做持久化；丢了大不了重新分析。
 */
object MoodStore {

    private val cache = ConcurrentHashMap<String, Mood>()
    private val pending = mutableMapOf<String, Claim>()

    class Claim internal constructor(val key: String)

    /** Length-prefix every field so different contexts or message identities never share a result. */
    fun keyOf(text: String, talker: String?, context: List<ContextMessage> = emptyList(),
        messageId: Long = 0, speaker: String = "对方", createdAt: Long = 0,
        coverage: ContextCoverage = ContextCoverage(), zoneId: String = java.util.TimeZone.getDefault().id): String {
        val source = buildString {
            fun field(value: String) { append(value.length).append(':').append(value) }
            field(talker.orEmpty())
            field(messageId.toString())
            field(speaker)
            field(text)
            field("temporal-intent-v1")
            field(createdAt.toString())
            field(zoneId)
            field(coverage.toString())
            context.forEach {
                field(it.speaker); field(it.text); field(it.createdAt.toString()); field(it.messageId.toString())
                it.voice?.let { source -> field(source.key); field(it.voiceState.name) }
            }
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): Mood? = cache[key]

    /** 尝试认领一次分析任务；已经在跑或已完成返回 null。 */
    @Synchronized fun acquire(key: String): Claim? {
        if (cache.containsKey(key) || pending.containsKey(key)) return null
        return Claim(key).also { pending[key] = it }
    }

    @Synchronized fun complete(claim: Claim, mood: Mood): Boolean {
        if (pending[claim.key] !== claim) return false
        cache[claim.key] = mood
        pending.remove(claim.key)
        return true
    }

    /** 失败也要释放认领，否则这条消息永远不会重试。 */
    @Synchronized fun release(claim: Claim): Boolean {
        if (pending[claim.key] !== claim) return false
        pending.remove(claim.key)
        return true
    }

    fun size(): Int = cache.size

    @Synchronized fun clear() {
        cache.clear()
        pending.clear()
    }
}
