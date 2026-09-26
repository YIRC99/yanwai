package dev.jev.wechatmood.voice

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

data class VoiceHostContract(val submit: Method, val state: Method, val text: Method, val chatField: Field) {
    val messageClass get() = submit.parameterTypes[0]
    companion object {
        fun resolve(component: Class<*>, chat: Class<*>): VoiceHostContract? = runCatching {
            val methods = component.methods.filter { !Modifier.isStatic(it.modifiers) }
            val submit = methods.single { method ->
                val p = method.parameterTypes
                p.size == 4 && !p[0].isPrimitive && p[1] == Boolean::class.javaPrimitiveType &&
                    p[2] == Int::class.javaPrimitiveType && p[3] == Int::class.javaPrimitiveType && method.returnType == Void.TYPE
            }
            val state = methods.single { it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType)) &&
                it.returnType.isEnum && it.returnType.enumConstants.orEmpty().map { value -> (value as Enum<*>).name }
                    .containsAll(listOf("NoTransform", "PreTransform", "Transforming", "Transformed")) }
            val text = methods.single { it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, String::class.java)) &&
                it.returnType == String::class.java }
            val field = generateSequence(component) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .single { it.type == chat && !Modifier.isStatic(it.modifiers) }.apply { isAccessible = true }
            VoiceHostContract(submit.apply { isAccessible = true }, state.apply { isAccessible = true },
                text.apply { isAccessible = true }, field)
        }.getOrNull()
    }
}
