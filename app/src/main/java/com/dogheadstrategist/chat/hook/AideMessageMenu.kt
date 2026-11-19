package com.dogheadstrategist.chat.hook

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
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.SettingsTransport
import com.dogheadstrategist.chat.feature.AidePicture
import com.dogheadstrategist.chat.feature.AidePrefs
import com.dogheadstrategist.chat.feature.MatterItem
import com.dogheadstrategist.chat.feature.PendingNotice
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.luckypray.dexkit.DexKitBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * 把「推送至 AI」加进微信的消息长按菜单。
 *
 * 刻意不去接管 performLongClick。那样做等于把复制、转发、撤回、多选整排原生
 * 选项一起吞掉，再自己重画一遍——既难对齐微信各版本，也一定丢功能。改成挂到
 * 微信自己的两个方法上：菜单创建时往里加一项，选中时只截住这一项自己的 id。
 * 原生选项原封不动，我们只是多了一行。
 *
 * 这两个方法用 DexKit 按字符串特征定位（"msg is null!" / "context item select
 * failed, null dataTag"），不硬编码混淆后的类名，微信小版本更新通常还能命中。
 *
 * 动作分两条：图片（type=3）走多模态，其余走文字提取。两条最后都落到
 * aide_items_put，和通知自动提取是同一条入库线。
 */
object AideMessageMenu {
    /** 随便取的稳定 id，落在应用私有段，不会和微信自己的菜单项撞上。 */
    private const val ITEM_ID = 0x4a455603

    private data class Target(val view: WeakReference<View>, val reply: MessageMetadata?)

    /**
     * 绑到 MenuItem 上而不是记「最后按的那条消息」。
     * 微信复用 MenuItem 实例，用全局变量串消息，迟早张冠李戴。
     */
    private val targets = Collections.synchronizedMap(WeakHashMap<MenuItem, Target>())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    class HookPoints(val create: List<Method>, val select: List<Method>)

    /** 复用消息绑定那次扫描的 bridge，不必为了两个方法把大 APK 再解析一遍。 */
    fun locate(bridge: DexKitBridge, loader: ClassLoader): HookPoints = runCatching {
        val create = bridge.findMethod {
            matcher { usingStrings("MicroMsg.ChattingItem", "msg is null!") }
        }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
        val select = bridge.findMethod {
            matcher { usingStrings("MicroMsg.ChattingItem", "context item select failed, null dataTag") }
        }.mapNotNull { runCatching { it.getMethodInstance(loader) }.getOrNull() }
        HookPoints(create, select)
    }.onFailure { MoodLog.e("AIDE_MENU_LOCATE_FAILED 消息菜单定位失败", it) }
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

            // 先装点击处理：宁可菜单加不上，也不能出现点了没反应的死项
            for (method in handlers) hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val item = param.args[0] as? MenuItem ?: return
                    if (item.itemId != ITEM_ID) return
                    val target = targets.remove(item) ?: return
                    param.result = null
                    runCatching {
                        val view = target.view.get() ?: return@runCatching
                        val meta = target.reply
                        if (meta == null) {
                            toast(view, "取不到这条消息，请重新长按")
                            return@runCatching
                        }
                        // 再核一次：菜单弹出来之后列表可能刷新过，原来那条已经滑走了
                        val current = MessageSniffer.messageForView(view)
                        if (current == null || current.messageId != meta.messageId) {
                            toast(view, "消息已变化，请重新长按")
                            return@runCatching
                        }
                        push(view, current)
                    }.onFailure { MoodLog.e("AIDE_MENU_ACTION_FAILED 推送至AI启动失败", it) }
                    runCatching { finishSelection(param.thisObject) }
                        .onFailure { MoodLog.e("AIDE_MENU_CLEANUP_FAILED 消息菜单收尾失败", it) }
                }
            })

            for (method in builders) hooks += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val view = param.args[1] as? View ?: return@runCatching
                        val menu = param.args[0] ?: return@runCatching
                        if (menu is Menu && menu.findItem(ITEM_ID) != null) return@runCatching

                        // 只在我们认识的消息上出这一项。取不到就什么都不加，
                        // 避免在非消息场景（联系人卡片等）冒出个奇怪的选项
                        val meta = MessageSniffer.messageForView(view) ?: return@runCatching
                        if (!supported(meta)) return@runCatching

                        val add = menu.javaClass.methods.singleOrNull {
                            it.parameterTypes.contentEquals(
                                arrayOf(
                                    Int::class.javaPrimitiveType,
                                    CharSequence::class.java,
                                    Drawable::class.java,
                                ),
                            ) && MenuItem::class.java.isAssignableFrom(it.returnType)
                        } ?: error("消息菜单不支持带图标的扩展入口")
                        add.isAccessible = true
                        val dark = view.resources.configuration.uiMode and
                            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                            android.content.res.Configuration.UI_MODE_NIGHT_YES
                        val icon = SparkIcon(view.resources.displayMetrics.density, dark)
                        val item = add.invoke(menu, ITEM_ID, "推送至 AI", icon) as? MenuItem
                            ?: error("消息菜单未返回新增选项")
                        targets[item] = Target(WeakReference(view), meta)
                    }.onFailure { MoodLog.e("AIDE_MENU_BUILD_FAILED 无法添加推送菜单", it) }
                }
            })
            MoodLog.i("AIDE_MENU_READY 推送至AI菜单已连接：${builders.size}/${handlers.size}")
        }.onFailure {
            hooks.forEach { hook -> runCatching { hook.unhook() } }
            MoodLog.e("AIDE_MENU_HOOK_FAILED 推送至AI菜单未适配", it)
        }
    }

    /** 文字、引用、图片、语音都能进通知代办；其余（红包、转账、系统提示）不加菜单。 */
    private fun supported(meta: MessageMetadata): Boolean =
        meta.type == 3 || meta.type == 1 || meta.type == 34 || meta.quotedMessage() != null

    private fun toast(view: View, text: String) {
        runCatching { Toast.makeText(view.context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun push(view: View, meta: MessageMetadata) {
        val context = view.context.applicationContext
        scope.launch {
            val message = runCatching {
                if (meta.type == 3) fromPicture(context, meta, view) else fromText(context, meta)
            }.getOrElse { error ->
                MoodLog.e("AIDE_MENU_PUSH_FAILED", error)
                "推送失败：" + (error.message ?: error.javaClass.simpleName)
            }
            withContext(Dispatchers.Main) { toast(view, message) }
        }
    }

    private suspend fun fromPicture(
        context: android.content.Context,
        meta: MessageMetadata,
        view: View,
    ): String {
        // 图片压缩、多模态请求、落库全在 AidePicture 里，和通知那条线共用。
        // 把 view 一起带下去：微信本地图片多是加密 .dat，从屏幕上那张取更稳。
        val outcome = AidePicture.extract(
            context = context,
            imagePath = meta.imagePath,
            senderHint = meta.speaker(),
            talker = meta.talker,
            imageView = view,
        )
        return outcome.message
    }

    private suspend fun fromText(context: android.content.Context, meta: MessageMetadata): String {
        if (!AidePrefs.enabledForRequest()) return "通知代办没启用"
        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) return "还没配置回复模型"
        val types = AidePrefs.typesForRequest()
        if (types.isEmpty()) return "没有可用的类型"

        // 引用消息取的是被引用的原文，语音要先转写——analysisText 已经分好岔了
        val text = meta.plainText() ?: meta.quotedMessage()?.text?.trim()
        if (text.isNullOrBlank()) return "这条消息里没有可提取的文字"

        val now = System.currentTimeMillis()
        val notice = PendingNotice(
            id = now,
            sender = meta.speaker(),
            text = text,
            talker = meta.talker,
            createdAt = if (meta.createdAt > 0) meta.createdAt else now,
        )
        val drafts = ReplyHttpClient().extractBatch(
            settings, listOf(notice), types, AidePrefs.keyUsersForRequest(),
        )
        val draft = drafts.firstOrNull() ?: return "模型没给出可解析的结果"
        if (draft.typeKey.isBlank()) return "这条消息里没有要记的事"

        val item = MatterItem(
            id = now,
            typeKey = draft.typeKey,
            content = draft.content,
            emoji = draft.emoji,
            source = meta.speaker(),
            talker = meta.talker,
            rawText = text,
            createdAt = now,
        )
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                SettingsTransport.call(context, "aide_items_put", JSONArray().put(item.toJson()).toString(), null)
            }.getOrNull()?.getBoolean("saved") == true
        }
        if (!saved) return "保存失败"
        MoodLog.i("AIDE_MENU_SAVED type=" + draft.typeKey + " note=" + draft.content.take(40))
        return "已记下：" + draft.content.take(40)
    }

    private fun fields(type: Class<*>): List<Field> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }.filter { !Modifier.isStatic(it.modifiers) }
            .onEach { it.isAccessible = true }.toList()

    /** 微信自己的选择处理会在结束时调这两个回调；我们截了胡，得替它收尾，否则菜单会挂着不退。 */
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

    /** 现画的图标：去微信资源表里查模块的资源 id 不可靠，自己画一个更省事。 */
    private class SparkIcon(density: Float, dark: Boolean) : Drawable() {
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
            // 一个四角星：比对话气泡更能表达「AI 处理」
            canvas.drawPath(Path().apply {
                moveTo(12f, 3f)
                quadTo(13.4f, 10.6f, 21f, 12f)
                quadTo(13.4f, 13.4f, 12f, 21f)
                quadTo(10.6f, 13.4f, 3f, 12f)
                quadTo(10.6f, 10.6f, 12f, 3f)
                close()
            }, paint)
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
