package dev.jev.wechatmood.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object MessageSniffer {
    private var installed = false
    private val chatLists = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val hooked = mutableSetOf<Method>()
    var hookedAdapterClass: String? = null
        private set

    fun install(context: Context) {
        if (installed) return
        installed = true
        // Discover the actual adapter used by a chatting screen; do not pick the first dex match.
        XposedBridge.hookAllMethods(ListView::class.java, "setAdapter", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val list = param.thisObject as? ListView ?: return
                    val adapter = param.args.firstOrNull() ?: return
                    val fromChat = adapter.javaClass.name.contains(".ui.chatting.") ||
                        list.javaClass.name.contains(".ui.chatting.") ||
                        Throwable().stackTrace.any { it.className.contains(".ui.chatting.") }
                    if (!fromChat) return
                    chatLists.add(list)
                    val method = adapter.javaClass.getMethod("getView", Int::class.javaPrimitiveType, View::class.java, ViewGroup::class.java)
                    if (!hooked.add(method)) return
                    hookedAdapterClass = adapter.javaClass.name
                    XposedBridge.hookMethod(method, rowHook)
                    MoodLog.i("已连接聊天列表：${adapter.javaClass.name}")
                    ModulePrefs.report("已连接聊天列表，等待显示对方的文字消息")
                }.onFailure { MoodLog.e("连接聊天列表失败：${it.javaClass.simpleName}") }
            }
        })
    }

    private val rowHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val recycled = param.args.getOrNull(1) as? MoodRow ?: return
            param.setObjectExtra("jev_row", recycled)
            param.args[1] = recycled.takeOriginal()
        }
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                val list = param.args.getOrNull(2) as? ViewGroup ?: return
                if (list !in chatLists) return
                val original = param.result as? View ?: return
                ModulePrefs.reload()
                if (!ModulePrefs.canAnalyze) return
                val body = MessageExtractor.body(original) ?: return
                val row = (param.getObjectExtra("jev_row") as? MoodRow) ?: MoodRow(original.context)
                row.bind(original)
                param.result = row
                val generation = row.generation
                row.post {
                    if (generation != row.generation) return@post
                    runCatching {
                        ModulePrefs.reload()
                        if (!ModulePrefs.canAnalyze || MessageExtractor.sideOf(body, original, list) != Side.OTHER) return@runCatching
                        val text = body.text.toString().trim()
                        val key = SignalAnalyzer.submit(text, System.identityHashCode(list).toString()) ?: return@runCatching
                        BubbleDecorator.watch(row, key)
                    }.onFailure { MoodLog.e("处理消息失败：${it.javaClass.simpleName}") }
                }
            }.onFailure { MoodLog.e("处理消息行失败：${it.javaClass.simpleName}") }
        }
    }
}

enum class Side { ME, OTHER, UNKNOWN }

object MessageExtractor {
    private fun texts(view: View, out: MutableList<TextView>, depth: Int = 0) {
        if (depth > 16 || view.visibility != View.VISIBLE) return
        if (view is TextView) out.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) texts(view.getChildAt(i), out, depth + 1)
    }
    fun body(root: View): TextView? {
        val views = mutableListOf<TextView>()
        texts(root, views)
        val body = MessageTextPicker.pickMessageBody(views.map { it.text.toString() }) ?: return null
        return views.firstOrNull { it.text.toString().trim() == body }
    }
    fun sideOf(body: View, root: View, list: ViewGroup): Side {
        if (list.width <= 0 || body.width <= 0) return Side.UNKNOWN
        val origin = IntArray(2).also { list.getLocationOnScreen(it) }
        val density = root.resources.displayMetrics.density
        val avatars = mutableListOf<Side>()
        fun scan(view: View, depth: Int) {
            if (depth > 16 || view.visibility != View.VISIBLE) return
            if (view is ImageView && view.width in (28 * density).toInt()..(64 * density).toInt() &&
                kotlin.math.abs(view.width - view.height) < 4 * density) {
                val xy = IntArray(2).also { view.getLocationOnScreen(it) }
                val center = xy[0] - origin[0] + view.width / 2f
                if (center < list.width * .16f) avatars.add(Side.OTHER)
                if (center > list.width * .84f) avatars.add(Side.ME)
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) scan(view.getChildAt(i), depth + 1)
        }
        scan(root, 0)
        // Ambiguous or avatar-less rows are skipped instead of sending our own text to the model.
        return avatars.distinct().singleOrNull() ?: Side.UNKNOWN
    }
}