package dev.jev.wechatmood.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.voice.VoiceHostContract
import dev.jev.wechatmood.voice.VoiceSource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Reuses the host transform queue and its persisted transcripts. Never plays audio or sends messages. */
object NativeVoiceBridge {
    private data class Setup(val contract: VoiceHostContract, val chatClass: Class<*>, val talker: Method)
    @Volatile private var setup: Setup? = null
    private val components = ArrayList<WeakReference<Any>>()
    private var activeChat = WeakReference<Any>(null)
    private val lane = Mutex()
    private val failures = object : LinkedHashMap<String, Long>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }

    fun install(bridge: DexKitBridge, loader: ClassLoader) {
        if (setup != null) return
        runCatching {
            val componentClasses = bridge.findClass { matcher { usingStrings("MicroMsg.TransformComponent", "[onChattingPause]") } }
            if (componentClasses.isEmpty()) { MoodLog.w("VOICE_COMPONENT_NOT_FOUND"); return }
            val component = componentClasses.first().getInstance(loader)
            val chatClasses = bridge.findClass { matcher { usingStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]") } }
            if (chatClasses.isEmpty()) { MoodLog.w("VOICE_CHAT_CLASS_NOT_FOUND"); return }
            val chat = chatClasses.first().getInstance(loader)
            val talkers = bridge.findMethod { matcher { usingStrings("getTalker returns null.") } }
                .mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
                .filter { it.declaringClass == chat && it.parameterCount == 0 && it.returnType == String::class.java }
            check(talkers.isNotEmpty()) { "getTalker 未找到匹配方法" }
            if (talkers.size > 1) MoodLog.w("VOICE_TALKER_AMBIGUOUS count=${talkers.size}，取第一个")
            val talker = talkers.first()
            val contract = requireNotNull(VoiceHostContract.resolve(component, chat)) { "语音转写契约解析失败" }
            val hooks = mutableListOf<XC_MethodHook.Unhook>()
            try {
                val observer = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.thisObject?.let(::observe)
                    }
                }
                hooks += XposedBridge.hookAllConstructors(component, observer)
                component.declaredMethods.filter { !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) }
                    .forEach { hooks += XposedBridge.hookMethod(it, observer) }
                setup = Setup(contract, chat, talker.apply { isAccessible = true })
                MoodLog.i("VOICE_BRIDGE_READY 原生语音转写入口已连接")
            } catch (e: Exception) { hooks.forEach { it.unhook() }; throw e }
        }.onFailure {
            MoodLog.w("VOICE_BRIDGE_UNAVAILABLE ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    @Synchronized private fun observe(component: Any) {
        components.removeAll { it.get() == null || it.get() === component }
        components.add(0, WeakReference(component))
        while (components.size > 8) components.removeAt(components.lastIndex)
    }

    fun observeBinding(owner: Any?) {
        val config = setup ?: return
        if (owner == null) return
        runCatching {
            val field = generateSequence(owner.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.type == config.chatClass && !Modifier.isStatic(it.modifiers) } ?: return
            activeChat = WeakReference(field.apply { isAccessible = true }.get(owner))
        }
    }

    fun clearActiveChat() { activeChat.clear() }
    fun retryFailures() { synchronized(failures) { failures.clear() } }

    /** Main-thread access only; a component from another retained chat is never invoked. */
    private fun component(config: Setup, source: VoiceSource): Any {
        val candidates = synchronized(this) { components.mapNotNull { it.get() } }.filter {
            runCatching { config.talker.invoke(config.contract.chatField.get(it)) == source.talker }.getOrDefault(false)
        }
        val current = activeChat.get()
        return candidates.firstOrNull { current != null && config.contract.chatField.get(it) === current }
            ?: candidates.singleOrNull() ?: error("语音转写尚未连接当前聊天，请退出后重新进入")
    }

    suspend fun transcribe(source: VoiceSource, allowed: () -> Boolean): String = lane.withLock {
        currentCoroutineContext().ensureActive()
        suspend fun checkChat() = withContext(Dispatchers.Main.immediate) {
            ensureActive()
            if (!allowed() || MessageSniffer.currentReplyTalker() != source.talker) throw CancellationException("聊天或分析开关已变化")
        }
        checkChat()
        val config = setup
        val record = withContext(Dispatchers.IO) { ReplyDatabaseHistory.voiceRecord(source, config?.contract?.messageClass) }
            ?: error("无法读取这条语音，请确认语音仍在本机并重新进入聊天")
        val existing = withContext(Dispatchers.IO) { ReplyDatabaseHistory.voiceText(record) }
        if (!existing.isNullOrBlank()) { checkChat(); return@withLock existing }
        val failedAt = synchronized(failures) { failures[source.key] }
        if (failedAt != null && System.nanoTime() - failedAt < 30_000_000_000L) error("语音暂未能转写，点击重试或稍后再试")
        try {
            check(config != null && record.message != null) { "当前微信版本的语音转写尚未适配" }
            val host = withContext(Dispatchers.Main.immediate) {
                checkChat()
                component(config, source)
            }
            val contract = config.contract
            withContext(Dispatchers.Main.immediate) {
                checkChat()
                val state = (contract.state.invoke(host, source.id) as Enum<*>).name
                // An existing host task is awaited, never toggled off by another consumer.
                if (state == "NoTransform") contract.submitMessage(host, record.message)
            }
            val result = withTimeoutOrNull(30_000) {
                var polls = 0
                var delayMs = 300L
                while (true) {
                    checkChat()
                    // 语音有效性每 3 轮才查一次；转写文本直接查，不再顺带重复校验。
                    if (polls % 3 == 0) {
                        check(withContext(Dispatchers.IO) { ReplyDatabaseHistory.voiceStillMatches(record) }) {
                            "语音已删除或聊天账号已变化"
                        }
                    }
                    val state = withContext(Dispatchers.Main.immediate) { (contract.state.invoke(host, source.id) as Enum<*>).name }
                    if (state == "Transformed") {
                        val text = withContext(Dispatchers.Main.immediate) {
                            contract.text.invoke(host, source.id, source.fileToken) as? String
                        }?.trim()
                        if (!text.isNullOrBlank()) return@withTimeoutOrNull text
                    }
                    val stored = withContext(Dispatchers.IO) { ReplyDatabaseHistory.voiceTextUnchecked(record) }
                    if (!stored.isNullOrBlank() && state !in setOf("PreTransform", "Transforming")) return@withTimeoutOrNull stored
                    check(state != "NoTransform" || polls < 5) { "微信未能转写这条语音，可先播放确认文件可用后重试" }
                    polls++
                    // 300 -> 600 -> 900 -> 1200ms：30 秒内的轮询次数从 100 降到 30 出头。
                    delay(delayMs)
                    if (delayMs < 1200) delayMs += 300
                }
                @Suppress("UNREACHABLE_CODE") ""
            } ?: error("语音转写超时，请稍后重试")
            checkChat()
            result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            synchronized(failures) { failures[source.key] = System.nanoTime() }
            MoodLog.w("VOICE_TRANSCRIBE_FAILED ${e.javaClass.simpleName}")
            throw IllegalStateException(if (e is IllegalStateException) e.message else "微信语音转写失败，请稍后重试")
        }
    }
}
