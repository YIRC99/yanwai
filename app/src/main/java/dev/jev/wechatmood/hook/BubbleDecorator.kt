package dev.jev.wechatmood.hook

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.MoodStore
import dev.jev.wechatmood.core.AnalysisInput
import java.util.IdentityHashMap

/** Append a sibling below the real text bubble, without replacing a host row or ViewHolder. */
object BubbleDecorator {
    private data class Card(
        val key: String, val view: TextView, val parent: ViewGroup,
        val anchor: View, val assignedId: Int?, val detach: View.OnAttachStateChangeListener,
    )
    private val cards = IdentityHashMap<View, Card>()
    private val unsupported = mutableSetOf<String>()

    fun show(row: View, message: AnalysisInput?): Boolean {
        if (message == null || !ModulePrefs.enabled || !ModulePrefs.showBadge) { clear(row); return false }
        val key = message.key
        var state = cards[row]
        if (state != null && (state.key != key || state.view.parent !== state.parent)) {
            clear(row)
            state = null
        }
        if (state == null) {
            state = attach(row, key) ?: return false
            cards[row] = state
        }
        val value = MoodStore.get(key)?.detail ?: SignalAnalyzer.failure(key)?.let {
            "Jev · 分析失败\n$it\n点击此卡重试"
        } ?: if (ModulePrefs.canAnalyze) "Jev · 正在看语气、前文和潜台词…" else "Jev · 模型未配置"
        if (state.view.text.toString() != value) state.view.text = value
        return true
    }

    private fun attach(row: View, key: String): Card? {
        val root = row as? ViewGroup ?: return null
        val anchor = findBubble(root) ?: return null
        val rowPos = IntArray(2).also { root.getLocationOnScreen(it) }
        val anchorPos = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val left = (anchorPos[0] - rowPos[0]).coerceAtLeast(0)
        val width = minOf(dp(row, 300), root.width - left - dp(row, 16))
        if (width < dp(row, 100)) return null
        val dark = row.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val card = TextView(row.context).apply {
            id = View.generateViewId()
            textSize = 12f
            setPadding(dp(row, 8), dp(row, 6), dp(row, 8), dp(row, 6))
            setTextColor(if (dark) 0xFFE2E2E7.toInt() else 0xFF34343A.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(row, 4).toFloat()
                setColor(if (dark) 0xFF26262B.toInt() else 0xFFDDDEE2.toInt())
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                if (SignalAnalyzer.failure(key) != null) {
                    SignalAnalyzer.retryFailure(key)
                    MessageSniffer.refresh()
                }
            }
        }
        var branch: View = anchor
        var parent = branch.parent as? ViewGroup
        var target: ViewGroup? = null
        var assignedId: Int? = null
        while (parent != null && isInside(parent, root)) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                val parentPos = IntArray(2).also { parent.getLocationOnScreen(it) }
                val lp = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = (anchorPos[0] - parentPos[0] - parent.paddingLeft).coerceAtLeast(0)
                    topMargin = dp(row, 3)
                    bottomMargin = dp(row, 6)
                }
                // Append only: do not shift the indexes of the host's original children.
                parent.addView(card, lp)
                target = parent
                break
            }
            if (parent === root) break
            branch = parent
            parent = branch.parent as? ViewGroup
        }
        if (target == null && root is RelativeLayout && branch.parent === root &&
            root.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            val branchParams = branch.layoutParams as? RelativeLayout.LayoutParams ?: return null
            if (branchParams.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0) return null
            if (branch.id == View.NO_ID) {
                assignedId = View.generateViewId()
                branch.id = assignedId
            }
            val lp = RelativeLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.BELOW, branch.id)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                leftMargin = left
                topMargin = dp(row, 3)
                bottomMargin = dp(row, 6)
            }
            root.addView(card, lp)
            target = root
        }
        if (target == null) {
            val signature = "${root.javaClass.name}/${anchor.parent?.javaClass?.name}"
            if (unsupported.add(signature)) MoodLog.w("暂不绘制未知气泡布局：$signature")
            return null
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { clear(v) }
        }
        row.addOnAttachStateChangeListener(detach)
        return Card(key, card, target, branch, assignedId, detach)
    }

    private fun findBubble(root: ViewGroup): View? {
        val holder = root.tag
        if (holder != null) {
            val method = generateSequence(holder.javaClass as Class<*>) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "getMainContainerView" && it.parameterCount == 0 }
            val main = runCatching { method?.isAccessible = true; method?.invoke(holder) as? View }.getOrNull()
            if (main != null && main !== root && main.isShown && isInside(main, root)) return main
        }
        fun find(view: View, depth: Int): View? {
            if (depth > 24 || view.visibility != View.VISIBLE) return null
            if (view.javaClass.name.endsWith(".MMNeat7extView")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    fun clear(row: View) {
        val state = cards.remove(row) ?: return
        row.removeOnAttachStateChangeListener(state.detach)
        (state.view.parent as? ViewGroup)?.removeView(state.view)
        if (state.assignedId != null && state.anchor.id == state.assignedId) state.anchor.id = View.NO_ID
    }
    fun clearAll() { cards.keys.toList().forEach(::clear) }
    fun prune() { cards.keys.filter { !it.isAttachedToWindow }.forEach(::clear) }
    private fun dp(view: View, n: Int) = (n * view.resources.displayMetrics.density).toInt()
}
