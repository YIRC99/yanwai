package dev.jev.wechatmood.hook

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog

/** Adds one compact row inside the expanded + panel. Original tiles and their listeners are retained. */
internal class ReplyPlusEntry(private val activity: Activity, private val open: () -> Unit) {
    private var panel: ViewGroup? = null
    private var wrapper: LinearLayout? = null
    private var original: View? = null
    private var originalParams: ViewGroup.LayoutParams? = null

    fun update(footer: View?) {
        val found = footer?.let(::descendants)?.filterIsInstance<ViewGroup>()?.singleOrNull {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.AppPanel" && it.isShown
        } ?: return
        if (panel === found && wrapper?.parent === found) return
        clear()
        // Match the measured host structure, not obfuscated IDs or tile positions.
        val content = found.getChildAt(0) as? LinearLayout ?: return
        if (found.childCount != 1 || descendants(content).none { it.javaClass.name.endsWith(".MMFlipper") }) return
        if (found.height < (180 * activity.resources.displayMetrics.density).toInt()) return
        val theme = ReplyTheme(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(16), 0, theme.dp(12), 0)
            background = theme.action("", quiet = true) {}.background
            isClickable = true; isFocusable = true
            contentDescription = "帮我回，打开言外回复建议"
            setOnClickListener {
                runCatching(open).onFailure { MoodLog.w("REPLY_PLUS_OPEN_FAILED ${it.javaClass.simpleName}") }
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
        row.addView(theme.label("帮我回", 14f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = theme.dp(10) })
        row.addView(theme.label("言外  ›", 12f, theme.muted))
        val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val params = content.layoutParams
        panel = found; original = content; originalParams = params; wrapper = host
        runCatching {
            found.removeView(content)
            host.addView(row, LinearLayout.LayoutParams(-1, theme.dp(48)))
            host.addView(View(activity).apply { setBackgroundColor(theme.border) }, LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            host.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            // The old root may have WRAP_CONTENT height. A weighted child needs the panel's full bounds.
            found.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            MoodLog.i("REPLY_PLUS_ENTRY_ATTACHED 已加入加号面板")
        }.onFailure { clear(); MoodLog.w("REPLY_PLUS_ENTRY_FAILED ${it.javaClass.simpleName}") }
    }

    fun clear() {
        val target = panel; val host = wrapper; val content = original
        if (target != null && content != null && (host?.parent === target || target.childCount == 0)) {
            (content.parent as? ViewGroup)?.removeView(content)
            if (host?.parent === target) target.removeView(host)
            target.addView(content, originalParams)
        }
        panel = null; wrapper = null; original = null; originalParams = null
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
