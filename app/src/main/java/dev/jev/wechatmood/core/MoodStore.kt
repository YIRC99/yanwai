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
 * 1. 同一条消息不能重复请求模型 —— 用内容哈希做键。
 * 2. 界面线程要能**立刻**拿到结果，不能等网络 —— 所以是「先占位、后填充」，
 *    装饰器拿到 null 就先不画，异步补上再通知刷新。
 * 3. 微信进程可能被回收 —— 只放内存，不做持久化；丢了大不了重新分析。
 */
object MoodStore {

    private val cache = ConcurrentHashMap<String, Mood>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Include the conversation and use SHA-256 to avoid Java hash collisions. */
    fun keyOf(text: String, talker: String?): String {
        val source = "${talker.orEmpty().length}:${talker.orEmpty()}$text"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun get(key: String): Mood? = cache[key]

    /** 尝试认领一次分析任务；已经在跑或已完成返回 false。 */
    fun claim(key: String): Boolean {
        if (cache.containsKey(key)) return false
        return pending.add(key)
    }

    fun complete(key: String, mood: Mood) {
        cache[key] = mood
        pending.remove(key)
    }

    /** 失败也要释放认领，否则这条消息永远不会重试。 */
    fun release(key: String) {
        pending.remove(key)
    }

    fun size(): Int = cache.size

    fun clear() {
        cache.clear()
        pending.clear()
    }
}
