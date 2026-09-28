package dev.jev.wechatmood.ui

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.jev.wechatmood.R

/** All settings feedback pairs readable text with a semantic foreground and surface. */
object SettingsStatus {
    fun show(view: TextView, text: String, color: Int = R.color.status_neutral) {
        view.text = text
        view.setTextColor(ContextCompat.getColor(view.context, color))
        val background = when (color) {
            R.color.status_success -> R.color.status_success_bg
            R.color.status_warning -> R.color.status_warning_bg
            R.color.status_error -> R.color.status_error_bg
            R.color.status_info -> R.color.status_info_bg
            else -> R.color.status_neutral_bg
        }
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(view.context, background))
    }
}
