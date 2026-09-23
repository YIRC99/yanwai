package dev.jev.wechatmood.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.TextView
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodStore

/** Each adapter row owns its card; never insert children into the ListView itself. */
class MoodRow(context: Context) : LinearLayout(context) {
    var generation = 0
    var original: View? = null
    val card = TextView(context).apply {
        textSize = 12f
        setPadding(dp(10), dp(8), dp(10), dp(8))
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setTextColor(if (dark) 0xFFE4E4E8.toInt() else 0xFF3B3B40.toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (dark) 0xFF303034.toInt() else 0xFFE2E2E5.toInt())
        }
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }
    init {
        orientation = VERTICAL
        layoutParams = AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        isClickable = false
        isFocusable = false
    }
    fun bind(view: View) {
        generation++
        removeAllViews()
        (view.parent as? ViewGroup)?.removeView(view)
        original = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.visibility = View.GONE
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(56); marginEnd = dp(24); topMargin = dp(2); bottomMargin = dp(8)
        })
    }
    fun takeOriginal(): View? {
        generation++
        val view = original
        if (view != null) removeView(view)
        original = null
        card.visibility = View.GONE
        return view
    }
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

object BubbleDecorator {
    fun watch(row: MoodRow, key: String) = poll(row, key, row.generation, 0)
    private fun poll(row: MoodRow, key: String, generation: Int, attempt: Int) {
        if (row.generation != generation || attempt >= 180) return
        ModulePrefs.reload()
        if (!ModulePrefs.enabled || !ModulePrefs.showBadge) {
            row.card.visibility = View.GONE
            return
        }
        val mood = MoodStore.get(key)
        if (mood != null) {
            row.card.text = mood.detail
            row.card.visibility = View.VISIBLE
            return
        }
        row.postDelayed({
            if (row.isAttachedToWindow) poll(row, key, generation, attempt + 1)
        }, 500)
    }
}