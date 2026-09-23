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
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap

/** Observe visible rows only. Never scrape arbitrary TextViews or read the message database. */
object MessageSniffer {
    private val main = Handler(Looper.getMainLooper())
    private val bindings = Collections.synchronizedMap(WeakHashMap<View, MessageMetadata>())
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
                val methods = paths.flatMap { path ->
                    DexKitBridge.create(path).use { bridge ->
                        bridge.findMethod {
                            matcher { usingStrings("MicroMsg.MvvmChattingItem", "[onBindView]") }
                        }.mapNotNull { data ->
                            runCatching { data.getMethodInstance(loader) }.getOrNull()
                        }
                    }
                }.filter { it.parameterCount >= 3 && it.parameterTypes[2] == Int::class.javaPrimitiveType }
                    .distinct()
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
                                        val item = adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)
                                            .invoke(adapter, position)
                                        MessageMetadata.read(item)
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
                MoodLog.e("新版聊天连接失败：${it.javaClass.simpleName}")
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
        ui?.dispose()
        ui = null
        visibleKeys = emptySet()
        active = WeakReference(activity)
        main.post(tick)
    }

    fun pause(activity: Activity) {
        if (active.get() !== activity) return
        main.removeCallbacks(tick)
        ui?.dispose()
        ui = null
        visibleKeys = emptySet()
        active.clear()
    }

    private fun scan(activity: Activity) {
        ModulePrefs.reload()
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val nodes = nodes(root)
        val settings = activity.javaClass.name.let {
            it.endsWith(".SettingsUI") || it.endsWith(".MainSettingsUI")
        }
        val chat = nodes.any { it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
        if (!settings && !chat) {
            ui?.hide()
            visibleKeys = emptySet()
            return
        }
        val panel = ui ?: HostUi(activity).also { ui = it }
        if (settings) {
            visibleKeys = emptySet()
            panel.showSettings()
            report("微信设置入口已显示 · $adapterStatus")
            return
        }
        val records = mutableListOf<Pair<View, MessageMetadata>>()
        // Legacy ListView: query the data item of each VISIBLE child, never the whole adapter.
        for (list in nodes.filterIsInstance<ListView>()) {
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index)
                if (!isVisible(row)) continue
                val item = runCatching { list.getItemAtPosition(list.firstVisiblePosition + index) }.getOrNull()
                MessageMetadata.read(item)?.let { records += row to it }
            }
        }
        synchronized(bindings) {
            bindings.entries.filter { it.key.rootView === root.rootView && isVisible(it.key) }
                .forEach { records += it.key to it.value }
        }
        val messages = records.sortedBy { (view, _) ->
            IntArray(2).also { view.getLocationOnScreen(it) }[1]
        }.mapNotNull { (_, message) ->
            val text = message.incomingText() ?: return@mapNotNull null
            VisibleMessage(text, message.talker)
        }.distinctBy { it.key }
        visibleKeys = messages.map { it.key }.toSet()
        if (ModulePrefs.canAnalyze) {
            for (message in messages) {
                val key = message.key
                SignalAnalyzer.submit(message.text, message.talker) { key in visibleKeys }
            }
        }
        val status = when {
            !ModulePrefs.bridgeAvailable -> "设置连接失败，点此打开助手后重试"
            !ModulePrefs.enabled -> "分析已关闭，点此打开设置"
            ModulePrefs.apiKey.isBlank() -> "安装包缺少模型配置"
            records.isEmpty() -> "未识别到消息 · $adapterStatus"
            messages.isEmpty() -> "本屏无对方纯文本，其他消息已跳过"
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
        report(status)
        panel.showStatus("Jev · $status", if (ModulePrefs.enabled && ModulePrefs.showBadge) messages else emptyList())
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
        val root = activity.findViewById<View>(android.R.id.content) ?: return
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

data class VisibleMessage(val text: String, val talker: String) {
    val key get() = dev.jev.wechatmood.core.MoodStore.keyOf(text.trim().take(4000), talker)
}
