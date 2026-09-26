package dev.jev.wechatmood.hook

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ManualAnalysis
import dev.jev.wechatmood.reply.ReplyContext
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/** Jev uses loaded rows. Manual LLM replies capture live identity here, then read history separately. */
object MessageSniffer {
    private val main = Handler(Looper.getMainLooper())
    private data class BoundMessage(val talker: String, val input: AnalysisInput?,
        val adapter: WeakReference<Any>? = null, val position: Int = -1, val metadata: MessageMetadata? = null)
    private val bindings = Collections.synchronizedMap(WeakHashMap<View, BoundMessage>())
    private var active = WeakReference<Activity>(null)
    private var installed = false
    @Volatile private var adapterStatus = "正在连接新版聊天列表"
    private var lastReport = ""
    @Volatile private var visibleKeys = emptySet<String>()
    private var ui: HostUi? = null
    private val tick = object : Runnable {
        override fun run() {
            val activity = active.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            runCatching { scan(activity) }.onFailure {
                visibleKeys = emptySet()
                SignalAnalyzer.cancelAll()
                BubbleDecorator.clearAll()
                report("读取当前页面失败：${it.javaClass.simpleName}")
                ui?.showStatus("Jev · 页面读取失败，点击查看", emptyList())
            }
            main.postDelayed(this, 700)
        }
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        val loader = context.classLoader
        val paths = listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()
        Thread({
            runCatching {
                System.loadLibrary("dexkit")
                val menuPoints = mutableListOf<MessageMenu.HookPoints>()
                val methods = paths.flatMap { path ->
                    DexKitBridge.create(path).use { bridge ->
                        menuPoints += MessageMenu.locate(bridge, loader)
                        runCatching {
                            bridge.findMethod {
                                matcher { usingStrings("MicroMsg.MvvmChattingItem", "[onBindView]") }
                            }.mapNotNull { data ->
                                runCatching { data.getMethodInstance(loader) }.getOrNull()
                            }
                        }.onFailure { MoodLog.e("CHAT_BIND_LOCATE_FAILED 新版聊天绑定定位失败", it) }
                            .getOrDefault(emptyList())
                    }
                }.filter { it.parameterCount >= 3 && it.parameterTypes[2] == Int::class.javaPrimitiveType }
                    .distinct()
                MessageMenu.install(menuPoints)
                check(methods.isNotEmpty()) { "未找到新版聊天绑定点" }
                for (method in methods) {
                    val adapterFields = fields(method.declaringClass).filter { field ->
                        field.type.methods.any { it.name == "getItem" &&
                            it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
                    }
                    if (ModulePrefs.exploreMode) {
                        MoodLog.i("聊天绑定适配：${method.declaringClass.name}.${method.name}，数据字段 ${adapterFields.size} 个")
                    }
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val holder = param.args.firstOrNull() ?: return
                            runCatching {
                                val row = fields(holder.javaClass).firstOrNull { it.type == View::class.java }?.get(holder) as? View
                                if (row != null) { BubbleDecorator.clear(row); bindings.remove(row) }
                            }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val holder = param.args.firstOrNull() ?: return
                            val row = runCatching {
                                fields(holder.javaClass).firstOrNull { it.type == View::class.java }?.get(holder) as? View
                            }.getOrNull() ?: return
                            // Clear first, even on a non-text rebind or a host reflection failure.
                            bindings.remove(row)
                            runCatching {
                                val position = param.args[2] as Int
                                val message = adapterFields.firstNotNullOfOrNull { field ->
                                    val adapter = field.get(param.thisObject) ?: return@firstNotNullOfOrNull null
                                    runCatching {
                                        val getItem = adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
                                        val current = MessageMetadata.read(getItem.invoke(adapter, position))
                                            ?: return@runCatching null
                                        BoundMessage(current.talker, MessageContext.collect(current, position) { index ->
                                            MessageMetadata.read(getItem.invoke(adapter, index))
                                        }, WeakReference(adapter), position, current)
                                    }.getOrNull()
                                }
                                if (message != null) bindings[row] = message
                                else if (ModulePrefs.exploreMode) MoodLog.w("消息对象未识别：${param.thisObject.javaClass.name}")
                            }.onFailure { MoodLog.w("消息类型读取失败：${it.javaClass.simpleName}") }
                        }
                    })
                }
                adapterStatus = "新版聊天监听已连接"
                MoodLog.i("新版消息绑定点已连接：${methods.size} 个")
                main.post { active.get()?.let { refreshVisibleAdapters(it) } }
            }.onFailure {
                adapterStatus = "新版聊天未适配（${it.javaClass.simpleName}），可重新进入聊天后查看"
                MoodLog.e("CHAT_BIND_FAILED 新版聊天连接失败", it)
            }
        }, "Jev-chat-hook").start()
    }

    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()
    private fun fields(type: Class<*>): List<Field> = fieldCache.getOrPut(type) {
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }.toList()
    }

    fun resume(activity: Activity) {
        main.removeCallbacks(tick)
        SignalAnalyzer.cancelAll()
        BubbleDecorator.clearAll()
        ui?.dispose()
        ui = null
        visibleKeys = emptySet()
        active = WeakReference(activity)
        main.post(tick)
    }

    fun pause(activity: Activity) {
        if (active.get() !== activity) return
        main.removeCallbacks(tick)
        SignalAnalyzer.cancelAll()
        BubbleDecorator.clearAll()
        ui?.dispose()
        ui = null
        visibleKeys = emptySet()
        active.clear()
    }

    fun refresh() {
        main.removeCallbacks(tick)
        if (active.get() != null) main.post(tick)
    }

    /** Resolve the pressed bubble back to its live row; never infer a message from screen text. */
    fun inputForView(view: View): AnalysisInput? {
        val activity = active.get() ?: return null
        if (!isVisible(view)) return null
        val scope = chatNodes(activity.window.decorView)
        if (view !in scope) return null
        val current = records(scope)
        val talker = conversation(activity, scope, current) ?: return null
        val ancestors = generateSequence(view) { it.parent as? View }.toSet()
        return current.filter { it.first in ancestors }.mapNotNull { it.second.input }
            .filter { it.talker == talker }.distinctBy { it.key }.singleOrNull()
    }

    fun analyzeMessage(view: View, expected: AnalysisInput): Boolean {
        val input = inputForView(view) ?: return false
        val identity = ManualAnalysis.identity(input) ?: return false
        if (identity != ManualAnalysis.identity(expected) || !ModulePrefs.selectMessage(input)) return false
        SignalAnalyzer.retryFailure(ModulePrefs.analysisInput(input).key)
        refresh()
        return true
    }

    fun replyTargetForView(view: View): MessageMetadata? {
        val activity = active.get() ?: return null
        val scope = chatNodes(activity.window.decorView)
        val current = records(scope)
        val talker = conversation(activity, scope, current) ?: return null
        val ancestors = generateSequence(view) { it.parent as? View }.toSet()
        return current.filter { it.first in ancestors }.mapNotNull { it.second.metadata }
            .filter { it.talker == talker && it.isReplyTarget() }.distinct().singleOrNull()
    }

    fun suggestReply(view: View, expected: MessageMetadata): Boolean {
        val target = replyTargetForView(view) ?: return false
        if (target != expected) return false
        return ui?.suggestReply(target.messageId) == true
    }

    fun currentReplyTalker(): String? {
        val activity = active.get() ?: return null
        val scope = chatNodes(activity.window.decorView)
        return if (scope.isEmpty()) null else conversation(activity, scope, records(scope))
    }

    /** Captures a bounded window from the active loaded adapter at user click time. */
    fun replyContext(requireBottom: Boolean = true): ReplyContext {
        val activity = active.get() ?: error("请先打开聊天")
        val scope = chatNodes(activity.window.decorView)
        val rows = records(scope)
        val talker = conversation(activity, scope, rows) ?: error("暂未识别当前聊天，请重新进入后重试")
        val list = scope.filterIsInstance<ListView>().firstOrNull { view ->
            (0 until view.childCount).any { MessageMetadata.read(runCatching { view.getItemAtPosition(view.firstVisiblePosition + it) }.getOrNull())?.talker == talker }
        }
        val adapter: Any
        val read: (Int) -> MessageMetadata?
        val count: Int
        val scrollingView: View
        if (list != null) {
            adapter = list.adapter
            count = list.count
            read = { index -> MessageMetadata.read(runCatching { list.getItemAtPosition(index) }.getOrNull()) }
            scrollingView = list
        } else {
            val bound = rows.map { it.second }.filter { it.talker == talker && it.adapter?.get() != null }.maxByOrNull { it.position }
                ?: error("当前聊天列表暂不支持回复上下文，请重新进入聊天后重试")
            adapter = bound.adapter!!.get() ?: error("聊天列表已变化，请重试")
            val counter = adapter.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name in setOf("getItemCount", "getCount") && it.returnType == Int::class.javaPrimitiveType
            } ?: error("无法确认聊天记录范围，请重新进入聊天后重试")
            count = counter.invoke(adapter) as Int
            val getItem = adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
            read = { index -> MessageMetadata.read(runCatching { getItem.invoke(adapter, index) }.getOrNull()) }
            val row = rows.firstOrNull { it.second.adapter?.get() === adapter }?.first ?: error("聊天已变化")
            scrollingView = generateSequence(row.parent as? View) { it.parent as? View }.firstOrNull { view ->
                generateSequence(view.javaClass as Class<*>) { it.superclass }.any { it.name.endsWith(".RecyclerView") }
            } ?: error("未识别聊天滚动区域")
        }
        if (requireBottom && scrollingView.canScrollVertically(1)) error("请先回到聊天底部，再生成这次的回复")
        val messages = mutableListOf<MessageMetadata>()
        var textCount = 0
        for (index in (count - 1) downTo (count - 400).coerceAtLeast(0)) {
            val record = read(index) ?: continue
            if (record.talker != talker) continue
            messages += record
            if (record.type == 1 && ++textCount >= ReplyContext.MAX_MESSAGES) break
        }
        val result = ReplyContext.collect(talker, messages.asReversed())
        check(result.historyAnchor != null || result.messages.isNotEmpty()) { "暂时无法核对当前聊天，请重新进入后重试" }
        return result.copy(trimmed = result.trimmed || messages.size < count)
    }

    /** Cheap live boundary check, without re-reading 100 messages on every scan. */
    fun replyBoundary(): Long? {
        val activity = active.get() ?: return null
        val scope = chatNodes(activity.window.decorView)
        val rows = records(scope)
        val talker = conversation(activity, scope, rows) ?: return null
        for (list in scope.filterIsInstance<ListView>()) {
            for (index in list.count - 1 downTo (list.count - 20).coerceAtLeast(0)) {
                val record = MessageMetadata.read(runCatching { list.getItemAtPosition(index) }.getOrNull())
                if (record?.talker == talker && record.messageId > 0) return record.messageId
            }
        }
        val adapter = rows.map { it.second }.firstNotNullOfOrNull {
            it.adapter?.get()?.takeIf { _ -> it.talker == talker }
        } ?: return null
        return runCatching {
            val counter = adapter.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name in setOf("getItemCount", "getCount") && it.returnType == Int::class.javaPrimitiveType
            } ?: return@runCatching null
            val count = counter.invoke(adapter) as Int
            val getter = adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
            (count - 1 downTo (count - 20).coerceAtLeast(0)).firstNotNullOfOrNull { index ->
                MessageMetadata.read(runCatching { getter.invoke(adapter, index) }.getOrNull())
                    ?.takeIf { it.talker == talker && it.messageId > 0 }?.messageId
            }
        }.getOrNull()
    }

    // Re-read the visible conversation at tap time, including during rapid chat navigation.
    fun setChatEnabled(talker: String?, enabled: Boolean): Boolean = runCatching {
        if (talker == null) return@runCatching false
        val activity = active.get() ?: return@runCatching false
        val scope = chatNodes(activity.window.decorView)
        if (scope.isEmpty() || conversation(activity, scope, records(scope)) != talker) return@runCatching false
        val saved = ModulePrefs.setChatEnabled(talker, enabled)
        if (saved && !enabled) SignalAnalyzer.cancelConversation(talker)
        saved
    }.onFailure { MoodLog.e("CHAT_SWITCH_FAILED 无法确认当前会话", it) }.getOrDefault(false)

    private fun chatNodes(root: View): List<View> {
        val footer = nodes(root).filter {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && isVisible(it)
        }.singleOrNull() ?: return emptyList()
        val chatRoot = generateSequence(footer.parent as? View) { it.parent as? View }
            .firstOrNull { it.javaClass.name.endsWith(".ChattingUILayout") } ?: root
        return nodes(chatRoot)
    }

    private fun conversation(activity: Activity, scope: List<View>, records: List<Pair<View, BoundMessage>>): String? {
        val footer = scope.firstOrNull { it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
        val footerTalker = footer?.let {
            runCatching { it.javaClass.getMethod("getTalkerUserName").invoke(it) as? String }.getOrNull()
        }
        // LauncherUI can retain the intent of an older embedded chat; never use that intent.
        val dedicated = if (activity.javaClass.name.endsWith(".ChattingUI"))
            activity.intent?.getStringExtra("Chat_User") else null
        return ConversationIdentity.resolve(records.map { it.second.talker }, footerTalker, dedicated)
    }

    private fun records(scope: List<View>): List<Pair<View, BoundMessage>> {
        val records = mutableListOf<Pair<View, BoundMessage>>()
        for (list in scope.filterIsInstance<ListView>()) {
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index)
                if (!isVisible(row)) continue
                val position = list.firstVisiblePosition + index
                val item = runCatching { list.getItemAtPosition(position) }.getOrNull()
                MessageMetadata.read(item)?.let { current ->
                    records += row to BoundMessage(current.talker, MessageContext.collect(current, position) { previous ->
                        MessageMetadata.read(list.getItemAtPosition(previous))
                    }, metadata = current)
                }
            }
        }
        val views = scope.toSet()
        synchronized(bindings) {
            bindings.entries.filter { it.key in views && isVisible(it.key) }
                .forEach { records += it.key to it.value }
        }
        return records
    }

    private fun scan(activity: Activity) {
        ModulePrefs.requestReload()
        // Embedded ChattingUILayout is a sibling of LauncherUI's content frame.
        val root = activity.window.decorView as? ViewGroup ?: return
        val settings = activity.javaClass.name.let {
            it.endsWith(".SettingsUI") || it.endsWith(".MainSettingsUI")
        }
        val chatScope = chatNodes(root)
        val chat = chatScope.isNotEmpty()
        if (!settings && !chat) {
            SignalAnalyzer.cancelAll()
            BubbleDecorator.clearAll()
            ui?.hide()
            visibleKeys = emptySet()
            return
        }
        val panel = ui ?: HostUi(activity).also { ui = it }
        if (settings) {
            SignalAnalyzer.cancelAll()
            BubbleDecorator.clearAll()
            visibleKeys = emptySet()
            panel.showSettings()
            report("微信设置入口已显示 · $adapterStatus")
            return
        }
        val records = records(chatScope)
        val talker = conversation(activity, chatScope, records)
        val enabled = ModulePrefs.isChatEnabled(talker)
        val messages = records.sortedBy { (view, _) ->
            IntArray(2).also { view.getLocationOnScreen(it) }[1]
        }.mapNotNull { (_, message) -> message.input?.takeIf { it.talker == talker } }
            .map(ModulePrefs::analysisInput).distinctBy { it.key }
        val selected = messages.filter(ModulePrefs::shouldDisplay)
        visibleKeys = selected.map { it.key }.toSet()
        SignalAnalyzer.reconcile(visibleKeys)
        for (message in selected) {
            val key = message.key
            SignalAnalyzer.submit(message) { key in visibleKeys }
        }
        BubbleDecorator.prune()
        var unsupported = 0
        records.distinctBy { it.first }.forEach { (row, message) ->
            runCatching {
                val input = message.input?.takeIf { it.talker == talker }?.let(ModulePrefs::analysisInput)
                    ?.takeIf(ModulePrefs::shouldDisplay)
                if (!BubbleDecorator.show(row, input) && input != null) unsupported++
            }
                .onFailure { BubbleDecorator.clear(row); MoodLog.w("气泡绘制失败：${it.javaClass.simpleName}") }
        }
        val status = when {
            talker == null -> "暂未识别当前聊天，收到消息后重试"
            !enabled -> if (selected.isEmpty()) "自动分析已关闭，可长按文字消息翻译意图"
                else "自动分析已关闭 · 本屏 ${selected.size} 条手动分析"
            !ModulePrefs.bridgeAvailable -> "设置连接失败，点此打开助手后重试"
            ModulePrefs.apiKey.isBlank() -> "请打开言外填写并保存 API Key"
            records.isEmpty() -> "未识别到消息 · $adapterStatus"
            messages.isEmpty() -> "本屏无可分析文字，非纯文本及超过 1000 字符的消息已跳过"
            else -> {
                val done = messages.count { dev.jev.wechatmood.core.MoodStore.get(it.key) != null }
                val failed = messages.count { SignalAnalyzer.failure(it.key) != null }
                when {
                    failed > 0 -> "本屏 ${messages.size} 条 · $failed 条失败，点击查看"
                    done == messages.size -> "本屏 $done 条已分析 · 点击查看"
                    else -> "正在分析本屏文字 $done/${messages.size} · 点击查看"
                }
            }
        }
        val displayStatus = if (unsupported > 0) "$status；$unsupported 条气泡布局暂不支持绘制" else status
        report(displayStatus)
        panel.showStatus("Jev · $displayStatus", messages, talker)
    }

    private fun report(status: String) {
        if (status == lastReport) return
        lastReport = status
        ModulePrefs.report(status)
    }

    private fun nodes(root: View): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View, depth: Int) {
            if (depth > 40 || result.size >= 4000 || view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        return result
    }

    private fun isVisible(view: View) = view.isShown && view.isAttachedToWindow &&
        view.windowVisibility == View.VISIBLE && view.getGlobalVisibleRect(Rect())

    private fun refreshVisibleAdapters(activity: Activity) {
        val root = activity.window.decorView
        val views = nodes(root)
        if (views.none { it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }) return
        for (view in views) {
            if (generateSequence(view.javaClass as Class<*>) { it.superclass }
                    .none { it.name.endsWith(".RecyclerView") }) continue
            runCatching {
                val adapter = view.javaClass.getMethod("getAdapter").invoke(view) ?: return@runCatching
                adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            }
        }
    }
}
