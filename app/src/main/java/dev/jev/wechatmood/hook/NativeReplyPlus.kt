package dev.jev.wechatmood.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ReplyPlusItems
import dev.jev.wechatmood.reply.ReplyPlusOwnership
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * Verified against the installed 8.0.71/3080 APK, not guessed obfuscated names.
 * Append a native dynamic model; let AppPanel own dimensions, counts, paging and listeners.
 * See docs/REPLY_PLUS_RESEARCH.md for the inspected methods and unsupported-build boundary.
 */
internal object NativeReplyPlus {
    private const val KEY = "dev.jev.wechatmood.native_reply_plus"
    private val ownership = ReplyPlusOwnership()
    private var installed = false
    private val replyViews = WeakHashMap<View, Boolean>()
    private val blockedPanels = WeakHashMap<Any, Boolean>()

    private fun owns(item: Any?): Boolean = ownership.owns(item)

    private data class Contract(
        val rebuild: Method, val setItems: Method, val panelConfig: Field, val dynamicItems: Field,
        val otherItems: Field, val grids: Field, val localCount: Field, val totalCount: Field,
        val width: Field, val height: Field, val itemClass: Class<*>, val labelClass: Class<*>,
        val otherOption: Field, val optionEnabled: Field,
        val getView: Method, val click: Method, val longClick: Method, val holderClass: Class<*>,
        val icon: Field,
    ) {
        fun newItem(): Any = itemClass.getConstructor().newInstance().also { item ->
            itemClass.getField("field_appId").set(item, "dev.jev.wechatmood.reply.plus")
            itemClass.getField("e2").set(item, "yanwai_reply")
            // A known native branch avoids remote icon loading. Replace only our icon after binding.
            itemClass.getField("r2").set(item, "icons_filled_live_mark")
            itemClass.getField("s2").set(item, "icons_filled_live_mark")
            itemClass.getField("g2").setInt(item, 0) // inert even if native dispatch is reached
            listOf("n2", "o2", "p2", "q2").forEach { field ->
                val label = labelClass.getConstructor().newInstance()
                labelClass.getField("a").set(label, "帮我回")
                labelClass.getField("b").set(label, "")
                itemClass.getField(field).set(item, label)
            }
            ownership.remember(item)
        }
    }

    private data class Build(val native: ArrayList<Any>?, val expected: List<Any>)

    @Synchronized fun install(context: Context) {
        if (installed) return
        val hooks = mutableListOf<XC_MethodHook.Unhook>()
        runCatching {
            val version = context.packageManager.getPackageInfo("com.tencent.mm", 0)
            if (!ReplyPlusItems.supports(version.versionName, version.longVersionCode)) {
                MoodLog.w("REPLY_PLUS_UNSUPPORTED host=${version.versionName}/${version.longVersionCode}")
                return
            }
            val c = resolve(context.classLoader)
            // All binding/click guards must be ready before any native model is added.
            hooks += XposedBridge.hookMethod(c.getView, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val adapter = param.thisObject as BaseAdapter
                    val item = adapter.getItem(param.args[0] as Int)
                    val recycled = param.args[1] as? View
                    if (owns(item) || replyViews.containsKey(recycled)) param.args[1] = null
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val adapter = param.thisObject as BaseAdapter
                    if (!owns(adapter.getItem(param.args[0] as Int))) return
                    val view = param.result as? View ?: return
                    replyViews[view] = true
                    // Keep the native holder, layout, label, selector and AdapterView click handling.
                    runCatching {
                        check(c.holderClass.isInstance(view.tag))
                        (c.icon.get(view.tag) as ImageView).setImageDrawable(ReplyIcon(ReplyTheme(view.context)))
                    }.onFailure { MoodLog.w("REPLY_PLUS_ICON_FAILED ${it.javaClass.simpleName}") }
                }
            })
            hooks += XposedBridge.hookMethod(c.click, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val grid = param.args[0] as? GridView ?: return
                    val item = grid.adapter?.getItem(param.args[2] as Int)
                    if (!owns(item)) return // original positions and original callback, untouched
                    param.result = null
                    val opened = runCatching { MessageSniffer.suggestReplyFromPlus(grid) }
                        .onFailure { MoodLog.w("REPLY_PLUS_OPEN_FAILED ${it.javaClass.simpleName}") }.getOrDefault(false)
                    if (!opened) Toast.makeText(grid.context, "当前聊天尚未就绪，请稍后再点帮我回", Toast.LENGTH_SHORT).show()
                }
            })
            hooks += XposedBridge.hookMethod(c.longClick, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val grid = param.args[0] as? GridView ?: return
                    if (owns(grid.adapter?.getItem(param.args[2] as Int))) param.result = true
                }
            })
            hooks += XposedBridge.hookMethod(c.rebuild, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val panel = param.args[0] ?: return
                    if (blockedPanels.containsKey(panel)) return
                    runCatching {
                        if (c.width.getInt(panel) <= 0 || c.height.getInt(panel) <= 0) return
                        val config = c.panelConfig.get(panel) ?: return
                        val raw = c.dynamicItems.get(config) as? ArrayList<*>
                        if (raw != null && raw.any { !c.itemClass.isInstance(it) }) {
                            if (raw.any(::owns)) c.setItems.invoke(panel, ArrayList(raw.filterNot(::owns)))
                            return
                        }
                        val original = raw?.map { requireNotNull(it) }.orEmpty()
                        val other = (c.otherItems.get(panel) as? List<*>)?.map { requireNotNull(it) } ?: return
                        val otherEnabled = c.optionEnabled.getBoolean(c.otherOption.get(config))
                        if (!ReplyPlusItems.canAppend(otherEnabled, other.size)) {
                            if (original.any(::owns)) c.setItems.invoke(panel, ArrayList(original.filterNot(::owns)))
                            return
                        }
                        val items = ReplyPlusItems.appendOnce(original, ::owns, c::newItem)
                        val native = raw?.let { ArrayList(original.filterNot(::owns)) }
                        c.setItems.invoke(panel, items)
                        param.setObjectExtra(KEY, Build(native, other + items))
                    }.onFailure { MoodLog.w("REPLY_PLUS_SKIP ${it.javaClass.simpleName}") }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    val build = param.getObjectExtra(KEY) as? Build ?: return
                    val panel = param.args[0]
                    val verified = !param.hasThrowable() && runCatching { verify(c, panel, build) }.getOrDefault(false)
                    if (verified) return
                    blockedPanels[panel] = true
                    // Rebuild once with the exact native item order, never leave a half-added item.
                    runCatching {
                        c.setItems.invoke(panel, build.native)
                        param.result = XposedBridge.invokeOriginalMethod(c.rebuild, null, arrayOf(panel))
                    }.onFailure { MoodLog.e("REPLY_PLUS_ROLLBACK_FAILED", it) }
                    MoodLog.w("REPLY_PLUS_SKIPPED native grid contract mismatch; panel disabled for this instance")
                }
            })
            installed = true
            MoodLog.i("REPLY_PLUS_READY native dynamic entry enabled for 8.0.71/3080")
        }.onFailure {
            hooks.asReversed().forEach { hook -> runCatching { hook.unhook() } }
            MoodLog.w("REPLY_PLUS_UNAVAILABLE ${it.javaClass.simpleName}")
        }
    }

    private fun verify(c: Contract, panel: Any, build: Build): Boolean {
        val grids = c.grids.get(panel) as? List<*> ?: return false
        if (grids.isEmpty()) return false
        val actual = mutableListOf<Any>()
        var count = 0
        for (view in grids) {
            val grid = view as? GridView ?: return false
            val adapter = grid.adapter ?: return false
            if (adapter.javaClass != c.getView.declaringClass || adapter.count !in 1..1000) return false
            count += adapter.count
            for (position in 0 until adapter.count) adapter.getItem(position)?.let(actual::add)
        }
        return count == c.totalCount.getInt(panel) && count == c.localCount.getInt(panel) + build.expected.size &&
            actual.size == build.expected.size && actual.indices.all { actual[it] === build.expected[it] } &&
            actual.count(::owns) == 1 && owns(actual.lastOrNull())
    }

    private fun resolve(loader: ClassLoader): Contract {
        val prefix = "com.tencent.mm.pluginsdk.ui.chat."
        fun type(name: String) = Class.forName(prefix + name, false, loader)
        val panel = type("AppPanel"); val grid = type("AppGrid"); val config = type("a0"); val option = type("z")
        val item = type("x"); val label = type("y"); val adapter = type("c"); val holder = type("e")
        check(ViewGroup::class.java.isAssignableFrom(panel) && GridView::class.java.isAssignableFrom(grid))
        check(BaseAdapter::class.java.isAssignableFrom(adapter))
        check(AdapterView.OnItemClickListener::class.java.isAssignableFrom(type("a")))
        check(AdapterView.OnItemLongClickListener::class.java.isAssignableFrom(type("b")))
        fun field(owner: Class<*>, name: String, expected: Class<*>) = owner.getDeclaredField(name).apply {
            check(type == expected && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val intType = Int::class.javaPrimitiveType!!
        item.getConstructor(); label.getConstructor()
        check(item.getField("field_appId").type == String::class.java)
        listOf("e2", "r2", "s2").forEach { field(item, it, String::class.java) }
        field(item, "g2", intType)
        listOf("n2", "o2", "p2", "q2").forEach { field(item, it, label) }
        field(label, "a", String::class.java); field(label, "b", String::class.java)
        field(adapter, "i", grid)
        field(holder, "e", TextView::class.java)
        val rebuild = panel.getDeclaredMethod("n", panel).apply {
            check(Modifier.isStatic(modifiers) && returnType == Void.TYPE); isAccessible = true
        }
        val setter = panel.getMethod("setAppPanelUnCertainEnterArrayList", ArrayList::class.java).apply {
            check(returnType == Void.TYPE)
        }
        val bind = adapter.getDeclaredMethod("getView", intType, View::class.java, ViewGroup::class.java).apply {
            check(returnType == View::class.java)
        }
        val clickArgs = arrayOf(AdapterView::class.java, View::class.java, intType, Long::class.javaPrimitiveType!!)
        val click = type("a").getDeclaredMethod("onItemClick", *clickArgs).apply { check(returnType == Void.TYPE) }
        val longClick = type("b").getDeclaredMethod("onItemLongClick", *clickArgs).apply {
            check(returnType == Boolean::class.javaPrimitiveType)
        }
        return Contract(rebuild, setter, field(panel, "C", config), field(config, "G", ArrayList::class.java),
            field(panel, "T", List::class.java), field(panel, "q", List::class.java),
            field(panel, "w", intType), field(panel, "x", intType), field(panel, "t", intType), field(panel, "u", intType),
            item, label, field(config, "j", option), field(option, "a", Boolean::class.javaPrimitiveType!!),
            bind, click, longClick, holder, field(holder, "g", ImageView::class.java))
    }

    private class ReplyIcon(theme: ReplyTheme) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val foreground = theme.accent
        private val background = theme.soft
        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 56f, bounds.height() / 56f)
            paint.style = Paint.Style.FILL; paint.color = background
            canvas.drawRoundRect(RectF(0f, 0f, 56f, 56f), 12f, 12f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.4f; paint.color = foreground
            canvas.drawRoundRect(RectF(13f, 13f, 43f, 38f), 6f, 6f, paint)
            canvas.drawPath(Path().apply { moveTo(20f, 38f); lineTo(19f, 44f); lineTo(27f, 38f) }, paint)
            canvas.drawLine(20f, 22f, 36f, 22f, paint); canvas.drawLine(20f, 29f, 32f, 29f, paint)
            canvas.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
