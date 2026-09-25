package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.core.MoodLog

/** Add controls to the existing header; never replace the chat's content or action bar. */
class HostUi(private val activity: Activity) {
    private val replyUi = ReplyHostUi(activity)
    private var control: Switch? = null
    private var syncing = false
    private var status = ""
    private var messages = emptyList<AnalysisInput>()
    private var talker: String? = null
    private var dialog: AlertDialog? = null
    private var title: TextView? = null
    private var oldTitleWidth = Int.MAX_VALUE
    private var oldEllipsize: TextUtils.TruncateAt? = null
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private var settingsWrapper: LinearLayout? = null
    private var settingsHost: View? = null
    private var settingsParams: ViewGroup.LayoutParams? = null

    fun showStatus(value: String, current: List<AnalysisInput>, currentTalker: String? = null) {
        restoreSettings()
        if (talker != currentTalker) dialog?.dismiss()
        talker = currentTalker
        replyUi.update(currentTalker)
        status = value
        messages = current
        ensureControl()
        syncing = true
        control?.apply {
            visibility = View.VISIBLE
            isChecked = ModulePrefs.isChatEnabled(talker)
            isEnabled = talker != null
            contentDescription = "当前聊天分析开关，本地记住选择；$value；长按打开分析与设置"
        }
        syncing = false
    }

    private fun ensureControl() {
        val decor = activity.window.decorView
        val all = descendants(decor)
        val chat = all.firstOrNull { it.javaClass.name.endsWith(".ChattingUILayout") && it.isShown }
        val scope = if (chat != null) descendants(chat) else all
        val header = scope.firstOrNull {
            it.isShown && it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
        } as? FrameLayout
        if (header != null && control?.parent === header && control?.isShown == true) return
        removeControl()
        if (header == null) return
        val toggle = Switch(activity).apply {
            text = "分析"
            textSize = 12f
            switchPadding = dp(3)
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) {
                    if (!MessageSniffer.setChatEnabled(talker, checked)) {
                        syncing = true
                        isChecked = ModulePrefs.isChatEnabled(talker)
                        syncing = false
                        Diagnostics.showFailure(activity, "开关未保存", "当前聊天已变化或本地保存失败，请重新进入聊天后重试。")
                    } else if (!checked) BubbleDecorator.clearAll()
                    MessageSniffer.refresh()
                }
            }
            setOnLongClickListener { showActions(); true }
        }
        control = toggle
        val headerPos = IntArray(2).also { header.getLocationOnScreen(it) }
        val menuLeft = descendants(header).filter { it.isShown && it.isClickable && it.width in 1..(header.width / 3) }
            .map { view -> IntArray(2).also { view.getLocationOnScreen(it) }[0] - headerPos[0] }
            .filter { it > header.width * 0.7 }.minOrNull()
        val menuSpace = menuLeft?.let { header.width - it + dp(4) } ?: dp(60)
        header.addView(toggle, FrameLayout.LayoutParams(-2, dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            rightMargin = menuSpace
        })
        toggle.post {
            if (control !== toggle || !toggle.isAttachedToWindow) return@post
            title = descendants(header).filterIsInstance<TextView>().firstOrNull {
                it !== toggle && it.isShown && it.text.isNotBlank() && it.width > dp(90)
            }
            title?.let {
                oldTitleWidth = it.maxWidth
                oldEllipsize = it.ellipsize
                it.maxWidth = (header.width - 2 * (toggle.width + menuSpace)).coerceAtLeast(dp(60))
                it.ellipsize = TextUtils.TruncateAt.END
            }
        }
    }

    private fun showActions() {
        if (dialog?.isShowing == true) return
        val selectedTalker = talker
        val selectedMessages = messages
        dialog = AlertDialog.Builder(activity).setTitle("言外 · $status")
            .setItems(arrayOf("分析本屏", "助手设置", "导出运行日志")) { _, which ->
                when (which) {
                    0 -> if (MessageSniffer.setChatEnabled(selectedTalker, true)) {
                        selectedMessages.forEach { SignalAnalyzer.retryFailure(it.key) }
                        MessageSniffer.refresh()
                    } else Diagnostics.showFailure(activity, "分析开关未保存", "当前聊天未识别、已变化或本地保存失败，请重新进入聊天后重试。")
                    1 -> openSettings()
                    2 -> Diagnostics.show(activity)
                }
            }
            .setNegativeButton("关闭", null).create().also { it.show() }
    }

    fun showSettings() {
        replyUi.hide()
        talker = null
        messages = emptyList()
        dialog?.dismiss()
        removeControl()
        if (settingsWrapper != null) return
        val host = content.getChildAt(0) ?: return
        settingsHost = host
        settingsParams = host.layoutParams
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        settingsWrapper = wrapper
        content.removeView(host)
        content.addView(wrapper, ViewGroup.LayoutParams(-1, -1))
        wrapper.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        wrapper.addView(TextView(activity).apply {
            text = "言外  ›\n已加载 · 长按导出运行日志"
            textSize = 14f
            minHeight = dp(52)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { openSettings() }
            setOnLongClickListener { Diagnostics.show(activity); true }
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val bottom = if (android.os.Build.VERSION.SDK_INT >= 30)
                    insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                else minOf(insets.systemWindowInsetBottom, insets.stableInsetBottom)
                view.setPadding(dp(16), dp(10), dp(16), dp(10) + bottom)
                insets
            }
            requestApplyInsets()
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openSettings() {
        runCatching {
            activity.startActivity(Intent().setComponent(ComponentName("dev.jev.wechatmood", "dev.jev.wechatmood.MainActivity")))
            MoodLog.i("SETTINGS_ACTIVITY_OPEN 请求已发送")
        }.onFailure {
            MoodLog.e("SETTINGS_ACTIVITY_OPEN_FAILED", it)
            Diagnostics.showFailure(activity, "无法从微信打开言外", "SETTINGS_ACTIVITY_OPEN_FAILED：${it.javaClass.simpleName} ${it.message}")
        }
    }
    fun suggestReply(focusMessageId: Long? = null) = replyUi.open(focusMessageId)
    fun hide() { replyUi.hide(); removeControl(); restoreSettings(); dialog?.dismiss(); messages = emptyList(); talker = null }
    fun dispose() { hide(); replyUi.dispose() }
    private fun removeControl() {
        control?.let { (it.parent as? ViewGroup)?.removeView(it) }
        control = null
        title?.let { it.maxWidth = oldTitleWidth; it.ellipsize = oldEllipsize }
        title = null
    }
    private fun restoreSettings() {
        val wrapper = settingsWrapper ?: return
        settingsHost?.let { host ->
            wrapper.removeView(host)
            content.removeView(wrapper)
            content.addView(host, 0, settingsParams)
        }
        settingsWrapper = null
        settingsHost = null
    }
    private fun descendants(root: View): List<View> {
        val result = mutableListOf<View>()
        fun walk(view: View, depth: Int) {
            if (depth > 40 || result.size > 4000 || view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return result
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
}
