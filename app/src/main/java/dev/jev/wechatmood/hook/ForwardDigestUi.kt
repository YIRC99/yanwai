package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.reply.ForwardDigest
import dev.jev.wechatmood.reply.ForwardDigestProtocol
import dev.jev.wechatmood.reply.ReplyHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 长按合并转发 → 摘要：解析 XML，交给配置的回复模型出一段要点。 */
internal class ForwardDigestUi(private val activity: Activity, private val message: ForwardDigest.Message) {
    private val theme = ReplyTheme(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: Dialog? = null
    private var text: TextView? = null
    private var footer: TextView? = null
    private var result = ""

    fun show() {
        val body = TextView(activity).apply {
            textSize = 14f
            setTextColor(theme.ink)
            setLineSpacing(theme.dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
            text = "正在整理 ${message.items.size} 条消息…"
            setPadding(theme.dp(20), theme.dp(12), theme.dp(20), theme.dp(12))
        }
        text = body
        val scroll = ScrollView(activity).apply { addView(body) }
        val status = TextView(activity).apply {
            textSize = 11f
            setTextColor(theme.muted)
            text = if (message.omitted > 0) "已省略 ${message.omitted} 条过长或过多的内容"
            else if (message.nested > 0) "含 ${message.nested} 条嵌套聊天记录，只展开了一层"
            else ""
            setPadding(theme.dp(20), 0, theme.dp(20), theme.dp(4))
        }
        footer = status
        val copy = TextView(activity).apply {
            text = "复制"
            gravity = Gravity.CENTER
            setTextColor(theme.onAccent)
            textSize = 14f
            background = GradientDrawable().apply {
                cornerRadius = theme.dp(10).toFloat()
                setColor(theme.accent)
            }
            setPadding(0, theme.dp(10), 0, theme.dp(10))
            isEnabled = false
            alpha = 0.5f
            setOnClickListener {
                if (result.isBlank()) return@setOnClickListener
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("聊天记录摘要", result))
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.surface)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(status)
            addView(copy, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(theme.dp(20), 0, theme.dp(20), theme.dp(16))
            })
        }
        val dialog = Dialog(activity).also { this.dialog = it }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            cornerRadius = theme.dp(16).toFloat()
            setColor(theme.surface)
        })
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            (activity.resources.displayMetrics.heightPixels * 0.7f).toInt())
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        request(copy)
    }

    private fun request(copy: TextView) {
        scope.launch {
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) {
                finish("请先在言外的「回复建议」里配置模型", copy)
                return@launch
            }
            runCatching {
                ReplyHttpClient().request(settings, ForwardDigestProtocol.payload(settings, message),
                    ForwardDigestProtocol::parse) { partial ->
                    if (partial.isNotBlank()) {
                        result = partial
                        text?.post { text?.text = partial }
                    }
                }
            }.onSuccess { content ->
                finish(content, copy)
            }.onFailure { error ->
                dev.jev.wechatmood.core.MoodLog.e("FORWARD_DIGEST_FAILED", error)
                finish("摘要失败：" + (error.message ?: "未知错误").take(80), copy)
            }
        }
    }

    private fun finish(content: String, copy: TextView) {
        result = content
        text?.text = content
        copy.isEnabled = true
        copy.alpha = 1f
        footer?.text = if (message.omitted > 0) "已省略 ${message.omitted} 条过长或过多的内容" else (footer?.text ?: "")
    }

    fun dismiss() {
        scope.cancel()
        dialog?.dismiss()
    }
}
