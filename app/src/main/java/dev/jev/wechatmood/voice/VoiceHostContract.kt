package dev.jev.wechatmood.voice

import dev.jev.wechatmood.core.MoodLog
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

data class VoiceHostContract(val submit: Method, val state: Method, val text: Method, val chatField: Field) {
    val messageClass get() = submit.parameterTypes[0]

    /** 微信 8.0.7x 起 submit 从 4 个参数变成 5 个（多一个 int），按实际个数拼参数。 */
    fun submitMessage(host: Any, message: Any) {
        if (submit.parameterCount >= 5) submit.invoke(host, message, false, -1, 0, 0)
        else submit.invoke(host, message, false, -1, 0)
    }
    companion object {
        fun resolve(component: Class<*>, chat: Class<*>): VoiceHostContract? = runCatching {
            val methods = component.methods.filter { !Modifier.isStatic(it.modifiers) }
            // 同一形状出现多个候选时无法确认哪个是被 Hook 的宿主方法，宁可失败也不要注入错方法。
            fun <T> pick(candidates: List<T>, name: String): T {
                check(candidates.isNotEmpty()) { "$name 没有候选" }
                check(candidates.size == 1) { "$name 存在 ${candidates.size} 个候选，无法唯一确定" }
                return candidates.single()
            }
            // 4 参数是旧版，5 参数是 8.0.7x 之后：只有参数个数不同才算版本差异，
            // 同参数个数的多个候选仍然是歧义。
            val submitCandidates = methods.filter { method ->
                val p = method.parameterTypes
                p.size in 4..5 && !p[0].isPrimitive && p[1] == Boolean::class.javaPrimitiveType &&
                    p[2] == Int::class.javaPrimitiveType && p[3] == Int::class.javaPrimitiveType &&
                    method.returnType == Void.TYPE
            }
            val submit = submitCandidates.maxByOrNull { it.parameterCount }?.let { best ->
                check(submitCandidates.count { it.parameterCount == best.parameterCount } == 1) {
                    "submit 存在多个相同签名的候选，无法唯一确定"
                }
                best
            } ?: error("submit 没有候选")
            val state = pick(methods.filter { it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                it.returnType.isEnum && it.returnType.enumConstants.orEmpty().map { value -> (value as Enum<*>).name }
                    .containsAll(listOf("NoTransform", "PreTransform", "Transforming", "Transformed")) }, "state")
            val text = pick(methods.filter { it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, String::class.java)) &&
                it.returnType == String::class.java }, "text")
            val field = pick(generateSequence(component) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { it.type == chat && !Modifier.isStatic(it.modifiers) }.toList(), "chatField")
                .apply { isAccessible = true }
            VoiceHostContract(submit.apply { isAccessible = true }, state.apply { isAccessible = true },
                text.apply { isAccessible = true }, field)
        }.onFailure { error ->
            MoodLog.w("VOICE_CONTRACT_FAILED ${error.javaClass.simpleName}: ${error.message}")
            // 打印实际方法签名，便于按版本精确适配；不含任何聊天内容。
            runCatching {
                val dump = component.methods.filter { !Modifier.isStatic(it.modifiers) }
                    .map { m -> m.name + "(" + m.parameterTypes.joinToString(",") { type -> type.simpleName } + ")=" + m.returnType.simpleName }
                    .take(60).joinToString("; ")
                MoodLog.w("VOICE_CONTRACT_DUMP ${dump.take(1800)}")
            }
        }.getOrNull()
    }
}
