package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodStore

/** A separate strip reserves its own space, leaving host rows and their recycling untouched. */
class HostUi(private val activity: Activity) {
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private val host = content.getChildAt(0)
    private val hostParams = host?.layoutParams
    private val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val entry = TextView(activity).apply {
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(if (dark) 0xFFE4E4E8.toInt() else 0xFF303034.toInt())
        setBackgroundColor(if (dark) 0xFF29292D.toInt() else 0xFFF1F1F3.toInt())
        isClickable = true
        isFocusable = true
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val navigation = if (android.os.Build.VERSION.SDK_INT >= 30)
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            else minOf(insets.systemWindowInsetBottom, insets.stableInsetBottom)
            view.setPadding(dp(16), dp(10), dp(16), dp(10) + navigation)
            insets
        }
    }
    private var messages = emptyList<VisibleMessage>()
    private var status = ""
    private var dialog: AlertDialog? = null
    private var detail: TextView? = null
    private var settingsMode = false
    init {
        if (host != null) {
            content.removeView(host)
            content.addView(wrapper, ViewGroup.LayoutParams(-1, -1))
            wrapper.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
            wrapper.addView(entry, LinearLayout.LayoutParams(-1, -2))
            entry.requestApplyInsets()
        }
        entry.setOnClickListener {
            if (settingsMode || !ModulePrefs.enabled || !ModulePrefs.bridgeAvailable) openSettings()
            else showDetails()
        }
    }

    fun showSettings() {
        settingsMode = true
        messages = emptyList()
        entry.text = "微信情绪助手  ›\n已加载 · 仅分析纯文本"
        entry.visibility = View.VISIBLE
    }

    fun showStatus(value: String, current: List<VisibleMessage>) {
        settingsMode = false
        messages = current
        status = value
        if (entry.text.toString() != value) entry.text = value
        entry.visibility = View.VISIBLE
        updateDetails()
    }

    fun hide() { entry.visibility = View.GONE; messages = emptyList(); dialog?.dismiss() }

    private fun showDetails() {
        if (dialog?.isShowing == true) return
        detail = TextView(activity).apply {
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(activity).apply { addView(detail) }
        dialog = AlertDialog.Builder(activity).setTitle("当前屏幕的纯文本分析")
            .setView(scroll).setPositiveButton("知道了", null)
            .setNeutralButton("助手设置") { _, _ -> openSettings() }
            .create().also { it.setOnDismissListener { detail = null; dialog = null }; it.show() }
        updateDetails()
    }

    private fun updateDetails() {
        val target = detail ?: return
        val text = if (!ModulePrefs.showBadge) "结果显示已关闭，可在助手设置中开启。" else if (messages.isEmpty()) status else
            messages.mapIndexed { index, message ->
                val result = MoodStore.get(message.key)?.detail ?: SignalAnalyzer.failure(message.key)?.let {
                    "$it\n30 秒后自动重试"
                } ?: "正在分析…"
                "${index + 1}. ${message.text.take(160)}\n$result"
            }.joinToString("\n\n") + "\n\n仅供参考，不会自动回复。语音、文件、图片、视频等已跳过。"
        if (target.text.toString() != text) target.text = text
    }

    private fun openSettings() {
        runCatching {
            activity.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity")))
        }.onFailure { Toast.makeText(activity, "无法打开助手，请从桌面打开微信情绪助手", Toast.LENGTH_LONG).show() }
    }

    fun dispose() {
        dialog?.dismiss()
        if (host != null && wrapper.parent === content) {
            wrapper.removeView(host)
            content.removeView(wrapper)
            content.addView(host, 0, hostParams)
        }
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
