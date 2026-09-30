package dev.jev.wechatmood.hook

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog

/** Restores the in-panel mounting from 1a8f1f1; original tiles/listeners are retained. */
internal class ReplyPlusRow(private val activity: Activity, private val createAnalysisControl: () -> View, private val open: () -> Boolean) {
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
            setPadding(theme.dp(8), 0, theme.dp(8), 0)
        }
        row.addView(createAnalysisControl(), LinearLayout.LayoutParams(-2, -1))
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(theme.action("帮我回 · 找话题", quiet = true) {
            if (panel === found && found.isShown && found.isAttachedToWindow) {
                runCatching(open).onFailure { MoodLog.w("REPLY_PLUS_OPEN_FAILED " + it.javaClass.simpleName) }
            }
        }, LinearLayout.LayoutParams(-2, -1))
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
