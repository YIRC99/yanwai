package dev.jev.wechatmood.hook

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog

/** Restores the in-panel mounting from 1a8f1f1; original tiles/listeners are retained. */
internal class ReplyPlusRow(private val activity: Activity, private val open: () -> Boolean) {
    private var panel: ViewGroup? = null
    private var wrapper: LinearLayout? = null
    private var original: View? = null
    private var originalParams: ViewGroup.LayoutParams? = null

    private var footer: View? = null
    private var observer: ViewTreeObserver? = null
    private var row: View? = null
    private var blockedPanel: ViewGroup? = null
    private var refreshing = false
    private var lastStatus: String? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { clear() }
    }

    fun update(currentFooter: View?) {
        if (footer !== currentFooter) {
            clear()
            footer = currentFooter
            currentFooter?.takeIf { it.isAttachedToWindow }?.let {
                observer = it.viewTreeObserver.also { tree -> tree.addOnGlobalLayoutListener(layoutListener) }
                it.addOnAttachStateChangeListener(attachListener)
            }
        }
        refresh()
    }

    private fun status(value: String) {
        if (lastStatus != value) { lastStatus = value; MoodLog.i("REPLY_PLUS_ROW_$value") }
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        try { syncRow() }
        catch (error: Exception) {
            blockedPanel = panel
            runCatching { restoreContent() }
            MoodLog.w("REPLY_PLUS_ROW_FAILED " + error.javaClass.simpleName)
        } finally { refreshing = false }
    }

    private fun syncRow() {
        val found = footer?.takeIf { it.isAttachedToWindow && it.isShown }?.let(::descendants)?.filterIsInstance<ViewGroup>()?.singleOrNull {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.AppPanel" && it.isShown
        }
        if (found == null) {
            restoreContent(); blockedPanel = null; status("HIDDEN"); return
        }
        if (found === blockedPanel) return
        if (panel === found && wrapper?.parent === found && original?.parent === wrapper && found.childCount == 1) return
        restoreContent()
        // Match the measured host structure, not obfuscated IDs or tile positions.
        val content = found.getChildAt(0) as? LinearLayout
        if (found.childCount != 1 || content == null || descendants(content).none { it.javaClass.name.endsWith(".MMFlipper") }) { status("SKIPPED structure"); return }
        if (found.height < (180 * activity.resources.displayMetrics.density).toInt()) { status("WAITING panel_measure"); return }
        val theme = ReplyTheme(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(16), 0, theme.dp(12), 0)
            background = theme.action("", quiet = true) {}.background
            isClickable = true; isFocusable = true
            contentDescription = "帮我回，打开言外回复建议"
            setOnClickListener {
                if (panel === found && found.isShown && found.isAttachedToWindow) runCatching(open).onFailure { MoodLog.w("REPLY_PLUS_OPEN_FAILED ${it.javaClass.simpleName}") }
            }
        }
        val icon = object : View(activity) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val unit = width / 32f
                canvas.save(); canvas.scale(unit, unit)
                paint.color = theme.accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f
                canvas.drawRoundRect(RectF(6f, 6f, 26f, 23f), 5f, 5f, paint)
                canvas.drawPath(Path().apply { moveTo(11f, 23f); lineTo(10f, 27f); lineTo(16f, 23f) }, paint)
                canvas.drawLine(11f, 12f, 21f, 12f, paint); canvas.drawLine(11f, 17f, 18f, 17f, paint)
                canvas.restore()
            }
        }.apply { background = theme.shape(theme.soft, 10); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        row.addView(icon, LinearLayout.LayoutParams(theme.dp(32), theme.dp(32)))
        row.addView(theme.label("帮我回 · 找话题", 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = theme.dp(10) })
        row.addView(theme.label("言外  ›", 12f, theme.muted))
        val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val params = content.layoutParams
        panel = found; original = content; originalParams = params; wrapper = host; this.row = row
        runCatching {
            found.removeView(content)
            host.addView(row, LinearLayout.LayoutParams(-1, theme.dp(48)))
            host.addView(View(activity).apply { setBackgroundColor(theme.border) }, LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            host.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            // The old root may have WRAP_CONTENT height. A weighted child needs the panel's full bounds.
            found.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            status("ATTACHED")
        }.onFailure { blockedPanel = found; restoreContent(); MoodLog.w("REPLY_PLUS_ROW_FAILED ${it.javaClass.simpleName}") }
    }

    fun clear() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observer = null
        footer?.removeOnAttachStateChangeListener(attachListener)
        footer = null; blockedPanel = null; lastStatus = null
        restoreContent()
    }

    private fun restoreContent() {
        row?.setOnClickListener(null)
        val target = panel; val host = wrapper; val content = original
        if (target != null && host != null && content != null &&
            (content.parent === host || content.parent == null) &&
            ((host.parent === target && target.childCount == 1) || target.childCount == 0)) {
            (content.parent as? ViewGroup)?.removeView(content)
            if (host.parent === target) target.removeView(host)
            target.addView(content, originalParams)
        } else if (target != null && host?.parent === target) {
            target.removeView(host)
        }
        panel = null; wrapper = null; original = null; originalParams = null; row = null
    }

    private fun descendants(root: View): List<View> = buildList {
        fun visit(view: View, depth: Int) {
            if (depth > 30 || size >= 1500) return
            add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
    }
}
