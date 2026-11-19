package com.dogheadstrategist.chat.sentry

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.SentryPrefs
import com.dogheadstrategist.chat.hook.MessageSniffer
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 聊天界面里的自动起草建议卡。
 *
 * 主方案是注入：把卡片叠在消息列表的父容器里、贴右下角。这样它和消息列表同层，
 * 观感就是「我准备要发的那条消息」，跟着列表自然定位，不会有悬浮窗那层隔阂。
 *
 * 代价是依赖微信的布局结构（列表父容器得是 FrameLayout）。既然目标环境不打算升级
 * 微信，这个依赖是稳定可接受的；真遇到结构不匹配的页面，再降级成系统悬浮窗兜底，
 * 保证功能不至于完全没有。
 *
 * 位置选右下角是因为触发条件是对面发了消息——那正是「我发出的消息」所在的一侧，
 * 此刻必然空着，既不遮挡对方消息，视觉上也顺。
 */
object SentryCard {
    private val attachments = WeakHashMap<Activity, Attachment>()

    /**
     * 消息列表的原始底部留白。放在 object 上而不是 Attachment 里：
     * 退出再进会重建 Attachment，若各存各的，就会把上一轮已经加大的值当成基准反复叠加。
     */

    fun attach(activity: Activity) {
        if (activity.isFinishing) return
        if (attachments.containsKey(activity)) return
        runCatching {
            val attachment = Attachment(activity)
            attachments[activity] = attachment
            attachment.start()
            MoodLog.i("SENTRY_CARD_ATTACHED page=" + activity.javaClass.simpleName)
        }.onFailure { MoodLog.w("SENTRY_CARD_ATTACH_FAILED " + it.javaClass.simpleName) }
    }

    fun detach(activity: Activity) {
        runCatching { attachments.remove(activity)?.stop() }
    }

    /** 测试模式用的占位建议：文案本身就说明它是假的，避免和真实结果混淆。 */
    private val TEST_SUGGESTIONS = listOf(
        "测试建议 1：看到这条说明卡片显示正常",
        "测试建议 2：点一下可以复制到输入框",
        "测试建议 3：占位文字，不是模型生成",
    )

    private class Attachment(private val activity: Activity) {
        private val handler = Handler(Looper.getMainLooper())
        private var container: LinearLayout? = null
        private var host: ViewGroup? = null
        private var asOverlay = false
        private var wm: WindowManager? = null
        private var params: WindowManager.LayoutParams? = null
        private var overlayDenied = false
        /** 当前渲染的是哪几条建议，内容变了才重绘。 */
        private var shownItems: List<String> = emptyList()
        /** 卡片当前是否处于「已显示」状态，用来判断该不该播入场动画。 */
        private var shown = false
        /** 当前显示的是不是「生成中」占位气泡。 */
        private var placeholderShown = false
        /** 占位气泡三点跳动的动画，卡片收起时要停掉。 */
        private var dotsAnimator: ValueAnimator? = null

        /** 卡片当前是否处于显示态，用于只在 GONE→VISIBLE 时播一次入场动画。 */
        /** 上次记录「读不到锚点」的时间，用于限频。 */
        private var running = false

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val ready = runCatching { refresh() }.getOrDefault(false)
                // 刚进聊天页时输入区常常还没渲染出来，这时用短间隔快速重试，
                // 免得建议明明已经在池子里，却要干等一个完整周期才显示。
                handler.postDelayed(this, if (ready) REFRESH_MS else FAST_RETRY_MS)
            }
        }

        fun start() {
            running = true
            // 新页面刚挂上，上一轮的会话标识一律作废
            handler.post(tick)
        }

        fun stop() {
            running = false
            handler.removeCallbacksAndMessages(null)
            runCatching {
                val card = container ?: return@runCatching
                if (asOverlay) wm?.removeView(card) else host?.removeView(card)
            }
            container = null
            host = null
            wm = null
            params = null
        }

        /** @return true 表示界面已就绪（输入区与会话都拿到了），可按常规间隔轮询。 */
        private fun refresh(): Boolean {
            if (activity.isFinishing) {
                stop()
                return true
            }
            val footer = findFooter()
            if (footer == null) {
                hideCard()
                // 不是聊天页（朋友圈、通讯录、设置…）都会走到这里。
                // 用快速间隔持续试探：微信的内嵌聊天界面出现时不触发 Activity 生命周期，
                // 只能靠这里发现，间隔放长会让卡片出现明显延迟。
                return false
            }
            val testMode = SentryPrefs.testModeForRequest()
            var items: List<String> = emptyList()
            var generating = false
            if (testMode) {
                // 测试模式：不看真实建议，任何会话都挂示例卡片，用来验证位置、样式与交互。
                items = TEST_SUGGESTIONS
                MoodLog.i("SENTRY_TEST_MODE_SHOW")
            } else {
                // 按会话取建议。切到别的会话时 talker 自然变了，卡片跟着换，
                // 不需要额外检测会话切换，也不会把旧会话的建议挂到新会话上。
                val talker = currentTalker()
                if (!talker.isNullOrBlank()) {
                    runCatching { SentryAgent.suggestionsForTalker(talker) }.getOrNull()?.let {
                        items = it
                    }
                    // 还没有建议但模型正在跑：先摆一个占位气泡。
                    // 模型要几秒才回，这段时间界面全无反应，卡片随后突然出现会很突兀；
                    // 有占位在，用户知道"在处理"，真建议到了也只是原地替换。
                    if (items.isEmpty()) {
                        generating = runCatching { SentryAgent.isGenerating(talker) }.getOrDefault(false)
                    }
                }
            }
            if (items.isEmpty() && !generating) {
                MoodLog.i("SENTRY_CARD_NO_MATCH")
                hideCard()
                // 没有现成建议就什么都不做：打开聊天时微信已经解冻，
                // 用户想生成直接点「帮我回复」面板，比等模型几秒更快，也不必为此多花一次请求。
                return true
            }
            // 用户已经回复过了，这条建议就作废（测试模式下不拦，否则大多数窗口根本测不到）
            if (!testMode && items.isNotEmpty()) {
                val talker = currentTalker()
                if (!talker.isNullOrBlank() &&
                    runCatching { SentryAgent.answeredSince(talker) }.getOrDefault(false)) {
                    SentryAgent.clear(talker)
                    MoodLog.i("SENTRY_CARD_DISMISSED_REPLIED talker=" + talker.take(12))
                    hideCard()
                    return true
                }
            }
            val card = ensureCard() ?: return true
            if (items.isEmpty()) {
                // 生成中：只有从「无占位」切过来才重建，避免每轮 poll 重启动画。
                if (!placeholderShown) {
                    renderPlaceholder(card)
                    placeholderShown = true
                    shownItems = emptyList()
                    MoodLog.i("SENTRY_CARD_PLACEHOLDER")
                }
            } else if (placeholderShown || shownItems != items) {
                render(card, items)
                shownItems = items
                placeholderShown = false
                if (shown) {
                    // 卡片已经在显示、只是内容换了：轻微淡入，避免文字硬切。
                    card.alpha = 0.55f
                    card.animate().alpha(1f).setDuration(160L).start()
                }
                MoodLog.i("SENTRY_CARD_SHOW n=" + items.size)
            }
            // 像一条真正的消息那样：只有看到会话末尾时才出现。往上翻历史时收起，
            // 否则它会一直贴在视口底部，把正在看的内容盖住。
            val list = findMessageList()
            val atBottom = asOverlay || list == null || !list.canScrollVertically(1)
            if (atBottom) showCard(card) else hideCard()
            if (asOverlay) reposition(card, footer)
            return true
        }

        /** 优先注入到消息列表里；结构对不上时降级为悬浮窗。 */
        private fun ensureCard(): LinearLayout? {
            container?.let { card -> if (card.parent != null) return card }
            return inject() ?: overlay()
        }

        private fun inject(): LinearLayout? {
            val list = findMessageList() ?: return null
            val parent = list.parent as? ViewGroup ?: return null
            if (parent !is FrameLayout) {
                MoodLog.i("SENTRY_CARD_NO_HOST parent=" + parent.javaClass.simpleName)
                return null
            }
            val card = newCard()
            val layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                rightMargin = dp(10)
                // 让开最后一条消息：一条普通气泡约 40dp，加上列表底部内边距，
                // 50dp 能盖住绝大多数情况，又不会把卡片推到半空
                bottomMargin = dp(50)
            }
            return runCatching {
                parent.addView(card, layoutParams)
                container = card
                host = parent
                asOverlay = false
                MoodLog.i("SENTRY_CARD_MOUNTED host=FrameLayout")
                card
            }.onFailure { MoodLog.w("SENTRY_CARD_MOUNT_FAILED " + it.javaClass.simpleName) }.getOrNull()
        }

        private fun overlay(): LinearLayout? {
            if (overlayDenied) return null
            val manager = activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return null
            val card = newCard()
            val layoutParams = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            return runCatching {
                manager.addView(card, layoutParams)
                wm = manager
                params = layoutParams
                container = card
                asOverlay = true
                MoodLog.i("SENTRY_CARD_OVERLAY_FALLBACK")
                card
            }.onFailure {
                overlayDenied = true
                MoodLog.w("SENTRY_CARD_OVERLAY_DENIED " + it.javaClass.simpleName)
            }.getOrNull()
        }

        private fun newCard() = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            visibility = View.GONE
        }

        /** 仅悬浮窗模式需要手动定位：贴输入框上沿、屏幕右侧。 */
        private fun reposition(card: LinearLayout, footer: View) {
            val manager = wm ?: return
            val layoutParams = params ?: return
            val width = if (card.width > 0) card.width else card.measuredWidth
            val height = if (card.height > 0) card.height else card.measuredHeight
            if (width <= 0 || height <= 0) {
                card.post { runCatching { reposition(card, footer) } }
                return
            }
            val location = IntArray(2)
            footer.getLocationOnScreen(location)
            val metrics = activity.resources.displayMetrics
            layoutParams.x = (metrics.widthPixels - width - dp(10)).coerceAtLeast(0)
            layoutParams.y = (location[1] - height - dp(8)).coerceAtLeast(0)
            runCatching { manager.updateViewLayout(card, layoutParams) }
        }

        /**
         * 「正在生成」占位：一个和正常气泡同色的小胶囊，里面三个点依次亮起。
         *
         * 不写文字是有意的——占位停留时间只有几秒，任何文案都来不及读，
         * 反而会和真正到达的建议抢注意力。三个点在动，就足够表达"还在处理"。
         */
        private fun renderPlaceholder(card: LinearLayout) {
            stopDots()
            card.removeAllViews()
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(0xE6FFF7E6.toInt())
                    setStroke(dp(1), 0xB3FFD591.toInt())
                }
            }
            val dots = List(3) { index ->
                View(activity).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0xFFB08A3E.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                        if (index > 0) leftMargin = dp(5)
                    }
                    alpha = 0.3f
                }
            }
            dots.forEach { row.addView(it) }
            card.addView(row)
            dotsAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { animator ->
                    val t = animator.animatedFraction
                    dots.forEachIndexed { index, dot ->
                        // 每个点错开 17% 相位，形成从左到右依次亮起的波浪。
                        val phase = ((t - index * 0.17f) + 1f) % 1f
                        val wave = 1f - abs(phase * 2f - 1f)
                        dot.alpha = 0.3f + 0.7f * wave
                    }
                }
                start()
            }
        }

        private fun stopDots() {
            dotsAnimator?.cancel()
            dotsAnimator = null
        }

        /**
         * 卡片出现时淡入 + 轻微上移。
         *
         * 建议是模型跑几秒后才生成的，直接 setVisibility(VISIBLE) 会在用户毫无预期时
         * 突然"蹦"出来。220ms 的减速淡入把这一下摊开，观感上更像"浮上来"而不是"弹出来"。
         * 只在 GONE → VISIBLE 时播一次，避免每轮 poll 都重播导致闪烁。
         */
        private fun showCard(card: View) {
            if (shown && card.visibility == View.VISIBLE) return
            card.animate().cancel()
            card.visibility = View.VISIBLE
            card.alpha = 0f
            card.translationY = dp(10).toFloat()
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(DecelerateInterpolator())
                .start()
            shown = true
        }

        private fun hideCard() {
            val card = container ?: return
            card.animate().cancel()
            stopDots()
            card.visibility = View.GONE
            card.alpha = 1f
            card.translationY = 0f
            shown = false
            placeholderShown = false
        }

        private fun render(card: LinearLayout, items: List<String>) {
            stopDots()
            card.removeAllViews()
            items.forEachIndexed { index, text -> card.addView(bubble(index, text)) }
        }

        private fun bubble(index: Int, text: String) = TextView(activity).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.parseColor("#5C3A00"))
            // 宽度是 WRAP_CONTENT，一行很长时会一路撑到屏幕边缘。
            // 限一个上限，让它在合理位置就换行，读起来也更像一条消息。
            // 收窄到 0.62 屏宽：卡片直接浮在聊天记录上方，越窄压住的内容越少。
            maxWidth = (activity.resources.displayMetrics.widthPixels * BUBBLE_MAX_RATIO).toInt()
            setPadding(dp(12), dp(9), dp(12), dp(9))
            // 不再给聊天列表补底部留白，改为靠半透明降低遮挡感：
            // 底下的消息能透上来，即使被盖住也还看得见大致轮廓。
            // 不再给聊天列表补底部留白，改为靠半透明降低遮挡感：
            // 底下的消息能透上来，即使被盖住也还看得见大致轮廓。
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE6FFF7E6.toInt())
                setStroke(dp(1), 0xB3FFD591.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = if (index == 0) 0 else dp(6) }
            setOnClickListener { fill(text) }
        }

        /** 填进微信输入框，走 commitText，微信会当作正常输入并点亮发送按钮。 */
        private fun fill(text: String) {
            runCatching {
                val editor = findEditor() ?: return@runCatching
                editor.requestFocus()
                editor.selectAll()
                val connection = editor.onCreateInputConnection(EditorInfo().apply { inputType = editor.inputType })
                if (connection?.commitText(text, 1) != true) {
                    editor.setText(text)
                    editor.setSelection(editor.text.length)
                }
                MoodLog.i("SENTRY_FILLED len=" + text.length)
                WarReviewFingerprint.remember(activity, text)
                // 填入即视为采用，把这个会话的建议清掉，别在下一轮刷新时又冒出来
                currentTalker()?.let { SentryAgent.clear(it) }
                hideCard()
            }.onFailure { MoodLog.w("SENTRY_FILL_FAILED " + it.javaClass.simpleName) }
        }

        /**
         * 消息列表从 ChatFooter 往上找：微信聊天页和主界面共用一个 Activity，
         * 直接扫「任意可见列表」会抓到会话列表。
         */
        private fun findMessageList(): View? {
            val footer = findFooter() ?: return null
            var node: View = footer
            var depth = 0
            while (depth < 4) {
                val parent = node.parent as? ViewGroup ?: break
                // 取面积最大的那个：聊天列表是页面上最大的滚动容器，
                // 用 firstOrNull 容易抓到表情面板里的小 RecyclerView，padding 设过去毫无效果。
                val list = walk(parent).filter { view ->
                    view !== footer && view.isShown && view.width > 0 &&
                        (view.javaClass.name.endsWith("RecyclerView") || view.javaClass.name.endsWith("ListView"))
                }.maxByOrNull { it.width.toLong() * it.height }
                if (list != null) return list
                node = parent
                depth++
            }
            return null
        }

        private fun findEditor(): EditText? {
            val footer = findFooter() ?: return null
            return walk(footer).filterIsInstance<EditText>().filter { it.isShown && it.isEnabled }
                .let { list -> list.singleOrNull() ?: list.firstOrNull() }
        }

        private fun findFooter(): View? = runCatching {
            val root = activity.window?.decorView ?: return null
            walk(root).firstOrNull { it.javaClass.name == CHAT_FOOTER && it.isShown }
        }.getOrNull()

        /**
         * 每次都真实读取会话标识，不做跨帧缓存。
         *
         * 原先有 300ms 缓存用来省一次视图树遍历，但它会把上一个会话的值带进新页面：
         * 从 A 退出后立刻进 B，缓存还没过期就仍返回 A，于是 A 的建议被渲染到 B 的页面上，
         * 过一会儿才发现不对、闪一下换成 B 的。遍历一次只有毫秒级，换掉它值得。
         */
        private fun currentTalker(): String? =
            runCatching { MessageSniffer.currentReplyTalker() }.getOrNull()

        private fun walk(root: View): List<View> {
            val result = ArrayList<View>()
            val stack = ArrayDeque<View>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val view = stack.removeLast()
                result += view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) stack.addLast(view.getChildAt(index))
                }
            }
            return result
        }

        private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

        private companion object {
            const val CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
            const val REFRESH_MS = 2000L
            /** 撤回关闭时不限制条数，取一个足够大的值覆盖可见范围。 */
            /** 建议气泡最大占屏宽比例；超出就换行，避免单行横跨整屏。 */
            const val BUBBLE_MAX_RATIO = 0.62f
            /** 界面还没就绪时的重试间隔，进聊天页那一两秒最关键。 */
            const val FAST_RETRY_MS = 250L
        }
    }
}
