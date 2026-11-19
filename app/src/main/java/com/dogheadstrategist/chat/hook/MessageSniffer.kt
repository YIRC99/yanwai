package com.dogheadstrategist.chat.hook

import com.dogheadstrategist.chat.sentry.WarReviewCapture

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
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.AnalysisInput
import com.dogheadstrategist.chat.reply.ReplyContext
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/** Live rows feed the reply drawer; manual replies capture live identity here, history is read separately. */
object MessageSniffer {

    private val main = Handler(Looper.getMainLooper())

    /**
     * 我的风格：最近一次采到的 msgId。
     *
     * 只靠「是不是列表最后一条」不够——发消息时列表刷新分两次绑定，
     * 旧的末尾和新末尾都会短暂满足「位置 == count-1」，
     * 于是上一条留存消息和刚发的这条都被采了。
     * msgId 全局自增，比它小的必然不是新消息，用它兜住。
     */
    private val lastCapturedMsgId = AtomicLong(0)

    /**
     * 这一行是不是列表最后一条。
     *
     * 绑定会在打开聊天、上下滚动、列表刷新时把窗口里已有的消息重画一遍，
     * 如果每一行都推，一进聊天就把整屏自己发过的话全采了。
     * 只认末尾那行：新消息一定是追加在末尾的，重画的历史行位置都靠前。
     */
    private fun BoundMessage.isLastRow(): Boolean {
        val adapter = adapter?.get() ?: return false
        val count = runCatching {
            adapter.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name in setOf("getItemCount", "getCount") &&
                    it.returnType == Int::class.javaPrimitiveType
            }?.invoke(adapter) as? Int
        }.getOrNull() ?: return false
        return count > 0 && position == count - 1
    }
    private data class BoundMessage(val talker: String, val input: AnalysisInput?,
        val adapter: WeakReference<Any>? = null, val position: Int = -1, val metadata: MessageMetadata? = null)
    private val bindings = Collections.synchronizedMap(WeakHashMap<View, BoundMessage>())
    private var active = WeakReference<Activity>(null)
    private var installed = false
    @Volatile private var adapterStatus = "正在连接新版聊天列表"
    private var lastReport = ""
    private var ui: HostUi? = null
    private var displayedTalker: String? = null
    private val tick = object : Runnable {
        override fun run() {
            val activity = active.get() ?: return
            if (activity.isFinishing || activity.isDestroyed) return
            runCatching { scan(activity) }.onFailure {
                report("读取当前页面失败：${it.javaClass.simpleName}")
                ui?.showStatus("页面读取失败，点击查看")
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
                val settingsPoints = mutableListOf<HostSettingsEntry.Points>()
                val menuPoints = mutableListOf<AideMessageMenu.HookPoints>()
                val methods = paths.flatMap { path ->
                    DexKitBridge.create(path).use { bridge ->
                        NativeVoiceBridge.install(bridge, loader)
                        HostSettingsEntry.locate(bridge, loader)?.let(settingsPoints::add)
                        menuPoints += AideMessageMenu.locate(bridge, loader)
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
                HostSettingsEntry.install(settingsPoints)
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
                                if (row != null) bindings.remove(row)
                            }
                        }
                        override fun afterHookedMethod(param: MethodHookParam) {
                            NativeVoiceBridge.observeBinding(param.thisObject)
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
                                if (message != null) {
                                    bindings[row] = message
                                    // 我的风格：我自己发出的文字消息，实时推给 app 进程。
                                    // 用 metadata 而不是上面的 current——current 只在查找 lambda 里可见。
                                    val meta = message.metadata
                                    // 只采列表最后一条：新消息追加在末尾，重画的历史行位置靠前。
                                    // 再加 msgId 单调判断：刷新过程中 count 会从 N 变成 N+1，
                                    // 旧末尾和新末尾都会短暂满足「位置 == count-1」，
                                    // 只靠位置会把上一条留存消息一起采进来。
                                    if (meta != null && meta.isSend == 1 && meta.type == 1 &&
                                        meta.content.isNotBlank() && message.isLastRow()) {
                                        val seen = lastCapturedMsgId.get()
                                        if (seen == 0L) {
                                            // 首次：拿库里最大 msgId 当基线，历史消息一条不采
                                            val baseline = runCatching { ReplyDatabaseHistory.maxMsgId() }
                                                .getOrDefault(0L)
                                            lastCapturedMsgId.set(maxOf(baseline, meta.messageId))
                                            if (meta.messageId > baseline) {
                                                WarReviewCapture.capture(context, meta.messageId, meta.content)
                                            }
                                        } else if (meta.messageId > seen) {
                                            lastCapturedMsgId.set(meta.messageId)
                                            WarReviewCapture.capture(context, meta.messageId, meta.content)
                                        }
                                    }
                                } else if (ModulePrefs.exploreMode) {
                                    MoodLog.w("消息对象未识别：${param.thisObject.javaClass.name}")
                                }
                            }.onFailure { MoodLog.w("消息类型读取失败：${it.javaClass.simpleName}") }
                        }
                    })
                }
                adapterStatus = "新版聊天监听已连接"
                MoodLog.i("新版消息绑定点已连接：${methods.size} 个")
                AideMessageMenu.install(menuPoints)
                main.post { active.get()?.let { refreshVisibleAdapters(it) } }
            }.onFailure {
                adapterStatus = "新版聊天未适配（${it.javaClass.simpleName}），可重新进入聊天后查看"
                MoodLog.e("CHAT_BIND_FAILED 新版聊天连接失败", it)
            }
        }, "Goutoujunshi-chat-hook").start()
    }

    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>()

    private fun fields(type: Class<*>): List<Field> = fieldCache.getOrPut(type) {
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }.toList()
    }

    fun resume(activity: Activity) {
        ReplyDatabaseHistory.resetAccountScope()
        main.removeCallbacks(tick)
        ui?.dispose()
        ui = null
        active = WeakReference(activity)
        main.post(tick)
    }

    fun pause(activity: Activity) {
        if (active.get() !== activity) return
        ReplyDatabaseHistory.resetAccountScope()
        main.removeCallbacks(tick)
        ui?.dispose()
        ui = null
        active.clear()
        NativeVoiceBridge.clearActiveChat()
    }

    fun refresh() {
        main.removeCallbacks(tick)
        if (active.get() != null) main.post(tick)
    }

    /**
     * 从被长按的 View 反查它属于哪条消息。
     *
     * 不走 RecyclerView 的 adapter position：实测微信的 ScrollControlRecyclerView
     * 上 getChildAdapterPosition 返回 -1，拿不到位置。改用祖先链匹配——长按的 View
     * 必然落在某条消息的 itemView 内部，拿已经解析好的 records 对一遍就有答案。
     *
     * 匹配到多条时返回 null 而不是猜一条：宁可提示"取不到"，也不能把 A 的消息
     * 当成 B 的记下来。
     */
    fun messageForView(view: View): MessageMetadata? {
        val activity = active.get() ?: return null
        val scope = chatNodes(activity.window.decorView)
        if (scope.isEmpty()) return null
        val ancestors = generateSequence(view) { it.parent as? View }.toSet()
        return records(scope)
            .filter { it.first in ancestors }
            .mapNotNull { it.second.metadata }
            .distinct()
            .singleOrNull()
    }

    fun currentReplyTalker(): String? {
        val activity = active.get() ?: return null
        val scope = chatNodes(activity.window.decorView)
        return if (scope.isEmpty()) null else conversation(activity, scope, records(scope))
    }

    /**
     * 当前聊天界面最近几条可见消息的主键，由新到旧。
     *
     * 建议是针对某条具体消息生成的，而消息主键全局唯一。用它判断「界面还在不在原处」
     * 比会话标识可靠：会话标识取自微信内部字段，切会话时要等它自己更新；
     * 消息列表本身就是界面内容，换了就是真的换了。
     *
     * 取最近几条而不是只看最后一条：对方补一两句话时，原来的锚点还在其中，
     * 建议依然适用；连发很多条才判定对话已经翻篇。
     */
    /** Captures a bounded window from the active loaded adapter at user click time. */
    /**
     * @param requireBottom 要求列表停在底部才允许生成（续话在输入时取上下文，这里传 false）
     * @param limit 最多读多少条文本消息；<=0 时用 [ReplyContext.MAX_MESSAGES]
     * @param cacheable 保留参数，供调用方表达"这次读取可以复用"
     */
    fun replyContext(requireBottom: Boolean = true, limit: Int = 0, cacheable: Boolean = false): ReplyContext {
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
        val wanted = if (limit > 0) minOf(limit, ReplyContext.MAX_MESSAGES) else ReplyContext.MAX_MESSAGES
        for (index in (count - 1) downTo (count - 400).coerceAtLeast(0)) {
            val record = read(index) ?: continue
            if (record.talker != talker) continue
            messages += record
            if (record.type in setOf(1, 34) && ++textCount >= wanted) break
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


    private fun chatNodes(root: View): List<View> {
        // 这里必须用宽松的 isShown，和 SentryCard.findFooter() 保持一致。
        // 原来的 isVisible 多要求了 isAttachedToWindow/windowVisibility/getGlobalVisibleRect，
        // 而 ChatFooter 在布局过程中常常拿不到有效的可见矩形，于是被整片过滤掉，
        // chatNodes 直接返回空 → 锚点恒为空 → 「最近 N 条」判定从来没生效过。
        // 读错的风险由锚点按消息主键精确匹配兜住（匹配不上就不显示，不会串台）。
        val footers = nodes(root).filter {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" && it.isShown
        }
        val footer = footers.firstOrNull() ?: return emptyList()
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
            ui?.hide()
            return
        }
        val panel = ui ?: HostUi(activity).also { ui = it }
        if (settings) {
            panel.showSettings()
            report(HostSettingsEntry.status(activity))
            return
        }
        val records = records(chatScope)
        val talker = conversation(activity, chatScope, records)
        if (displayedTalker != talker) {
            ReplyDatabaseHistory.resetAccountScope()
            displayedTalker = talker
        }
        val status = when {
            talker == null -> "暂未识别当前聊天，收到消息后重试"
            !ModulePrefs.bridgeAvailable -> "设置连接失败，点此打开助手后重试"
            !ModulePrefs.canGenerateReply -> "请打开狗头军师配置回复模型"
            records.isEmpty() -> "未识别到消息 · $adapterStatus"
            else -> "已连接当前聊天 · 点「帮我回复 · 找话题」或长按消息选择「帮我回复」"
        }
        report(status)
        panel.showStatus(status, talker)
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
