package dev.jev.wechatmood.hook

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import dev.jev.wechatmood.analysis.JevProtocol
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.CardDisplaySettings
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.MoodStore

/** One host-window popup; never inserts children into WeChat's recycled message rows. */
object BubbleDecorator {
    private class Card(
        val key: String,
        val root: View,
        val view: TextView,
        val scroll: ScrollView,
        val popup: PopupWindow,
        val width: Int,
        var x: Int,
        var y: Int,
        val detach: View.OnAttachStateChangeListener,
    )
    private val presentation = AnalysisPopupState()
    private var card: Card? = null

    fun request(key: String) {
        if (card?.key != key) disposeWindow()
        presentation.request(key)
    }

    fun show(row: View, message: AnalysisInput?): Boolean {
        if (message == null) return false
        if (!ModulePrefs.shouldDisplay(message)) {
            if (card?.key == message.key) clearAll()
            return false
        }
        val key = message.key
        val mood = MoodStore.get(key) ?: SignalAnalyzer.partialMood(key)
        val display = ModulePrefs.analysisSettings()?.cardDisplay ?: CardDisplaySettings()
        if (mood != null && AnalysisCardContent.lines(mood, display).isEmpty()) {
            if (card?.key == key) clearAll()
            return true
        }
        // A closed or background result is handled, not an unsupported host layout.
        if (!presentation.offer(key)) return true
        if (!row.isAttachedToWindow) return false
        val root = row.rootView
        val state = card?.takeIf { it.key == key && it.root === root } ?: run {
            disposeWindow()
            attach(root, key)?.also { card = it } ?: return false
        }
        val value = mood?.let {
            AnalysisCardText.format(it, row.resources.displayMetrics.density,
                state.width - state.view.paddingLeft - state.view.paddingRight,
                state.view.paint, display)
        } ?: SignalAnalyzer.failure(key)?.let {
            "${JevProtocol.header}\n分析失败：$it\n点击内容重试"
        } ?: "${JevProtocol.header}\n" + if (ModulePrefs.canAnalyze(message))
            SignalAnalyzer.progress(key) ?: if (message.voice != null || message.context.any { it.voice != null }) "正在准备语音…" else "正在分析…"
            else if (!message.backgroundReady) "正在读取对方背景；读取失败时会重试"
            else "模型未配置或设置未连接"
        if (state.view.text.toString() != value.toString()) {
            state.view.text = value
            resize(state)
        }
        return true
    }

    private fun attach(root: View, key: String): Card? {
        val context = root.context
        val width = minOf(dp(root, 380), root.width - dp(root, 32))
        if (width < dp(root, 100) || root.height <= 0) return null
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(AnalysisPopupPalette.BACKGROUND)
                setCornerRadius(dp(root, 18).toFloat())
                setStroke(dp(root, 1), AnalysisPopupPalette.BORDER)
            }
        }
        val header = LinearLayout(context).apply {
            setGravity(Gravity.CENTER_VERTICAL)
            setPadding(dp(root, 14), 0, dp(root, 4), 0)
        }
        val title = TextView(context).apply {
            text = "言外 · 意图解读"
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(AnalysisPopupPalette.FOREGROUND)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            contentDescription = "意图解读，拖动标题可移动悬浮窗"
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(root, 48), 1f))
        val close = TextView(context).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(AnalysisPopupPalette.FOREGROUND)
            contentDescription = "关闭意图悬浮窗"
            isFocusable = true
            setOnClickListener { clearAll() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(root, 48), dp(root, 48)))
        box.addView(header)
        val text = TextView(context).apply {
            textSize = 14f
            setTextColor(AnalysisPopupPalette.FOREGROUND)
            setPadding(dp(root, 14), dp(root, 8), dp(root, 14), dp(root, 14))
            setLineSpacing(dp(root, 3).toFloat(), 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                if (SignalAnalyzer.failure(key) != null || MoodStore.get(key)?.intentFailed == true) {
                    SignalAnalyzer.retryFailure(key)
                    MessageSniffer.refresh()
                }
            }
        }
        val scroll = ScrollView(context).apply {
            addView(text, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(scroll, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        val popup = PopupWindow(box, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = false
            elevation = dp(root, 12).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { clearAll() }
        }
        val state = Card(key, root, text, scroll, popup, width,
            ((root.width - width) / 2).coerceAtLeast(0), minOf(dp(root, 80), root.height / 4), detach)
        var startX = 0f
        var startY = 0f
        var oldX = 0
        var oldY = 0
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    oldX = state.x; oldY = state.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    state.x = (oldX + (event.rawX - startX).toInt()).coerceIn(0, (root.width - width).coerceAtLeast(0))
                    state.y = (oldY + (event.rawY - startY).toInt()).coerceIn(0, (root.height - box.height).coerceAtLeast(0))
                    popup.update(state.x, state.y, -1, -1)
                    true
                }
                MotionEvent.ACTION_UP -> { view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        popup.setOnDismissListener {
            root.removeOnAttachStateChangeListener(detach)
            if (card === state) { card = null; presentation.dismiss() }
        }
        return try {
            popup.showAtLocation(root, Gravity.TOP or Gravity.LEFT, state.x, state.y)
            root.addOnAttachStateChangeListener(detach)
            state
        } catch (error: RuntimeException) {
            popup.setOnDismissListener(null)
            popup.dismiss()
            MoodLog.w("意图悬浮窗暂不可用：${error.javaClass.simpleName}")
            null
        }
    }

    private fun resize(state: Card) {
        val maxHeight = (state.root.height / 2).coerceAtLeast(1)
        state.view.measure(View.MeasureSpec.makeMeasureSpec(state.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        state.scroll.layoutParams = (state.scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = minOf(state.view.measuredHeight, maxHeight)
        }
        state.popup.update(state.width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Row recycling must not dismiss a window owned by the chat rather than that row. */
    fun clear(@Suppress("UNUSED_PARAMETER") row: View) = Unit

    fun clearAll() {
        presentation.dismiss()
        disposeWindow()
    }

    fun prune() {
        if (card?.root?.isAttachedToWindow == false) clearAll()
    }

    private fun disposeWindow() {
        val old = card ?: return
        card = null
        old.root.removeOnAttachStateChangeListener(old.detach)
        old.popup.setOnDismissListener(null)
        old.popup.dismiss()
    }

    private fun dp(view: View, n: Int) = (n * view.resources.displayMetrics.density).toInt()
}
