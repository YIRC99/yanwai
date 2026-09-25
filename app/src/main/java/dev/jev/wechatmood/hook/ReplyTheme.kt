package dev.jev.wechatmood.hook

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.TextView

/** Host-safe native styling; no module resource IDs or host theme-dependent input backgrounds. */
internal class ReplyTheme(private val context: Context) {
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val surface = color("#F3F6F4", "#111B17")
    val card = color("#FCFDFC", "#1C2A24")
    val ink = color("#182B24", "#E1EEE6")
    val muted = color("#586B62", "#A6B9AF")
    val accent = color("#20685C", "#8CD7C0")
    val onAccent = color("#F3F6F4", "#12362D")
    val border = color("#D6E0DB", "#34493E")
    val soft = color("#E6F0EB", "#233B30")
    val error = color("#A32F39", "#FFB1B7")
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(light: String, night: String) = Color.parseColor(if (dark) night else light)
    fun shape(fill: Int, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(fill)
        if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
    }
    fun label(value: String, size: Float = 14f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(2).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    fun action(value: String, primary: Boolean = false, quiet: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = value; textSize = 14f; gravity = Gravity.CENTER; minHeight = dp(48)
            val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
            setTextColor(ColorStateList(states, intArrayOf(muted, if (primary) onAccent else accent)))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true; isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf((accent and 0x00ffffff) or 0x22000000),
                shape(if (primary) accent else if (quiet) Color.TRANSPARENT else card, 12, if (primary || quiet) null else border).apply {
                    setColor(ColorStateList(states, intArrayOf(if (quiet) Color.TRANSPARENT else soft,
                        if (primary) accent else if (quiet) Color.TRANSPARENT else card)))
                },
                shape(ink, 12))
            setOnClickListener { onClick() }
        }
}
