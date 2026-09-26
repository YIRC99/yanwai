package dev.jev.wechatmood.hook

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.ManualAnalysis
import dev.jev.wechatmood.core.MoodLog
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Adds one action to the host menu without replacing the bubble's long-click listener. */
object MessageMenu {
    private const val ITEM_ID = 0x4a455601
    private const val REPLY_ITEM_ID = 0x4a455602
    private data class Target(val view: WeakReference<View>, val input: AnalysisInput? = null, val reply: MessageMetadata? = null)
    // Bind callbacks to the actual menu item, not a global last-pressed message.
    private val targets = Collections.synchronizedMap(WeakHashMap<MenuItem, Target>())

    class HookPoints(val create: List<Method>, val select: List<Method>)

    /** Share the chat-binding scan's bridge; parsing a large host APK twice is unnecessary. */
    fun locate(bridge: DexKitBridge, loader: ClassLoader): HookPoints = runCatching {
        val create = bridge.findMethod {
            matcher { usingStrings("MicroMsg.ChattingItem", "msg is null!") }
        }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
        val select = bridge.findMethod {
            matcher { usingStrings("MicroMsg.ChattingItem", "context item select failed, null dataTag") }
        }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
        HookPoints(create, select)
    }.onFailure { MoodLog.e("MESSAGE_MENU_LOCATE_FAILED 消息菜单定位失败", it) }
        .getOrElse { HookPoints(emptyList(), emptyList()) }

    fun install(points: List<HookPoints>) {
        val hooks = mutableListOf<XC_MethodHook.Unhook>()
        runCatching {
            val builders = points.flatMap { it.create }.distinct().filter {
                it.declaringClass.name.startsWith("com.tencent.mm.ui.chatting.viewitems.") &&
                    it.parameterCount >= 2 && View::class.java.isAssignableFrom(it.parameterTypes[1])
            }
            val handlers = points.flatMap { it.select }.distinct().filter {
                it.declaringClass.name.startsWith("com.tencent.mm.ui.chatting.viewitems.") &&
                    it.parameterCount >= 1 && MenuItem::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                    it.returnType == Void.TYPE
            }
            check(builders.isNotEmpty() && handlers.isNotEmpty()) { "当前微信未找到消息菜单创建或选择入口" }
            // Install click handling first so there can never be an action without a handler.
            for (method in handlers) hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args[0] as? MenuItem ?: return
                    if (item.itemId != ITEM_ID && item.itemId != REPLY_ITEM_ID) return
                    val target = targets.remove(item) ?: return
                    param.result = null
                    runCatching {
                        val view = target.view.get() ?: return@runCatching
                        val accepted = if (item.itemId == REPLY_ITEM_ID) target.reply?.let { MessageSniffer.suggestReply(view, it) } == true
                            else target.input?.let { MessageSniffer.analyzeMessage(view, it) } == true
                        if (!accepted) {
                            Toast.makeText(view.context, "消息已变化，请重新长按需要分析的文字或语音", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { MoodLog.e("MESSAGE_MENU_ACTION_FAILED 单条分析启动失败", it) }
                    runCatching { finishSelection(param.thisObject) }
                        .onFailure { MoodLog.e("MESSAGE_MENU_CLEANUP_FAILED 消息菜单收尾失败", it) }
                }
            })
            for (method in builders) hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val view = param.args[1] as? View ?: return@runCatching
                        val input = MessageSniffer.inputForView(view)?.takeIf { ManualAnalysis.identity(it) != null }
                        val replyTarget = MessageSniffer.replyTargetForView(view)
                        if (input == null && replyTarget == null) return@runCatching
                        val menu = param.args[0] ?: return@runCatching
                        if (menu is Menu && (menu.findItem(ITEM_ID) != null || menu.findItem(REPLY_ITEM_ID) != null)) return@runCatching
                        val add = menu.javaClass.methods.singleOrNull {
                            it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType,
                                CharSequence::class.java, Drawable::class.java)) &&
                                MenuItem::class.java.isAssignableFrom(it.returnType)
                        } ?: error("消息菜单不支持带图标的扩展入口")
                        add.isAccessible = true
                        val icon = IntentIcon(view.resources.displayMetrics.density,
                            view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                                android.content.res.Configuration.UI_MODE_NIGHT_YES)
                        if (input != null) {
                            val item = add.invoke(menu, ITEM_ID, "翻译意图", icon) as? MenuItem
                                ?: error("消息菜单未返回新增选项")
                            targets[item] = Target(WeakReference(view), input)
                        }
                        if (replyTarget != null) {
                            val reply = add.invoke(menu, REPLY_ITEM_ID, "帮我回", icon) as? MenuItem
                            if (reply != null) targets[reply] = Target(WeakReference(view), reply = replyTarget)
                        }
                    }.onFailure { MoodLog.e("MESSAGE_MENU_BUILD_FAILED 无法添加翻译意图菜单", it) }
                }
            })
            MoodLog.i("MESSAGE_MENU_READY 翻译意图菜单已连接：${builders.size}/${handlers.size}")
        }.onFailure {
            hooks.forEach { hook -> runCatching { hook.unhook() } }
            MoodLog.e("MESSAGE_MENU_HOOK_FAILED 翻译意图菜单未适配", it)
        }
    }

    private fun fields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }.filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }.toList()

    /** The host selection handler normally invokes these two menu/selection cleanup callbacks. */
    private fun finishSelection(handler: Any) {
        val listener = fields(handler.javaClass).firstOrNull {
            View.OnLongClickListener::class.java.isAssignableFrom(it.type)
        }?.get(handler) ?: error("未找到消息长按监听器")
        val callbacks = fields(listener.javaClass).filter { field ->
            val type = field.type
            !type.isInterface && Modifier.isAbstract(type.modifiers) &&
                type.declaredMethods.filter { Modifier.isAbstract(it.modifiers) }.singleOrNull()?.let {
                    it.parameterCount == 0 && it.returnType == Void.TYPE
                } == true
        }
        check(callbacks.size == 2) { "消息菜单收尾回调数量不匹配：${callbacks.size}" }
        callbacks.forEach { field ->
            val callback = field.get(listener) ?: return@forEach
            val method = field.type.declaredMethods.single { Modifier.isAbstract(it.modifiers) }
            method.isAccessible = true
            method.invoke(callback)
        }
    }

    /** Runtime drawable avoids looking up module resources from WeChat's resource table. */
    private class IntentIcon(density: Float, dark: Boolean) : Drawable() {
        private val size = (24 * density).toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFFE8E8E8.toInt() else 0xFF333333.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        override fun draw(canvas: Canvas) {
            val saved = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            canvas.drawPath(Path().apply {
                moveTo(5f, 3f); lineTo(19f, 3f); quadTo(21f, 3f, 21f, 5f)
                lineTo(21f, 15f); quadTo(21f, 17f, 19f, 17f)
                lineTo(10f, 17f); lineTo(5f, 21f); lineTo(5f, 17f)
                quadTo(3f, 17f, 3f, 15f); lineTo(3f, 5f); quadTo(3f, 3f, 5f, 3f)
            }, paint)
            canvas.drawLine(7f, 8f, 17f, 8f, paint)
            canvas.drawLine(7f, 12f, 14f, 12f, paint)
            canvas.restoreToCount(saved)
        }
        override fun getIntrinsicWidth() = size
        override fun getIntrinsicHeight() = size
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
