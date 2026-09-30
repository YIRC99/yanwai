package dev.jev.wechatmood.hook

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog

/** Own row beside ChatFooterBottom; never wraps or reparents the native AppPanel. */
internal class ReplyPlusRow(private val activity: Activity, private val open: () -> Boolean) {
    private var footer: View? = null
    private var observer: ViewTreeObserver? = null
    private var row: View? = null
    private var panel: View? = null
    private var blockedPanel: View? = null
    private var originalPanelHeight = 0
    private var refreshing = false
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

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            syncRow()
        } catch (error: Exception) {
            blockedPanel = panel
            removeRow()
            MoodLog.w("REPLY_PLUS_ROW_FAILED ${error.javaClass.simpleName}")
        } finally {
            refreshing = false
        }
    }

    private fun syncRow() {
        val root = footer?.takeIf { it.isAttachedToWindow && it.isShown }
        val expanded = root?.let { visiblePanels(it).singleOrNull() }
        if (expanded == null) {
            removeRow()
            blockedPanel = null
            return
        }
        if (expanded === blockedPanel) return
        val bottom = expanded.parent as? ViewGroup
        val host = bottom?.parent as? LinearLayout
        // Unknown/fixed-height containers keep the header entry; do not squeeze native controls.
        if (bottom?.javaClass?.name != "com.tencent.mm.pluginsdk.ui.chat.ChatFooterBottom" ||
            host == null || host.orientation != LinearLayout.VERTICAL ||
            host.layoutParams?.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            removeRow()
            return
        }
        val existing = row
        if (existing != null && panel === expanded && existing.parent === host &&
            host.indexOfChild(existing) + 1 == host.indexOfChild(bottom)) {
            // If a host ancestor clips the added row or shrinks the panel, withdraw until it closes.
            val visible = Rect()
            val nativeVisible = Rect()
            if (existing.height > 0 && (expanded.height < originalPanelHeight ||
                    !existing.getLocalVisibleRect(visible) || visible.height() < existing.height ||
                    !expanded.getLocalVisibleRect(nativeVisible) || nativeVisible.height() < expanded.height)) {
                blockedPanel = expanded
                removeRow()
            }
            return
        }
        removeRow()
        val theme = ReplyTheme(activity)
        val entry = theme.action("帮我回 · 找话题   ›", quiet = true) {
            if (footer === root && panel === expanded && expanded.isShown && expanded.isAttachedToWindow) {
                runCatching { open() }.onFailure { MoodLog.w("REPLY_PLUS_OPEN_FAILED ${it.javaClass.simpleName}") }
            }
        }
        row = entry
        panel = expanded
        originalPanelHeight = expanded.height
        host.addView(entry, host.indexOfChild(bottom), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun visiblePanels(root: View): List<View> {
        val result = mutableListOf<View>()
        var visited = 0
        fun visit(view: View, depth: Int) {
            if (depth > 30 || ++visited > 512 || !view.isShown) return
            if (view.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.AppPanel" &&
                view.isAttachedToWindow && view.width > 0 && view.height > 0) result += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index), depth + 1)
        }
        visit(root, 0)
        return result
    }

    private fun removeRow() {
        row?.let { row ->
            row.setOnClickListener(null)
            (row.parent as? ViewGroup)?.removeView(row)
        }
        row = null
        panel = null
        originalPanelHeight = 0
    }

    fun clear() {
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observer = null
        footer?.removeOnAttachStateChangeListener(attachListener)
        footer = null
        blockedPanel = null
        removeRow()
    }
}
