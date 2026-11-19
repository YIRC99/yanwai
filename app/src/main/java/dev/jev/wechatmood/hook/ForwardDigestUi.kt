package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: Dialog? = null
    private var text: TextView? = null
    private var footer: TextView? = null
    private var loading: ReplyLoadingView? = null
    private var result = ""

    /** 读取范围的说明，和统计窗口一样用一条辅助文字说清楚。 */
    private fun notice(): String = when {
        message.truncatedRaw -> "内容超过 512K，只读取了前面的部分"
        message.omitted > 0 -> "已省略 ${message.omitted} 条过长或过多的内容"
        message.nested > 0 -> "含 ${message.nested} 条嵌套聊天记录，已展开到第 3 层"
        else -> ""
    }

    fun show() {
        val dialog = kit.sheet().also { this.dialog = it }
        val root = kit.root()
        val column = kit.content(root)

        column.addView(kit.heading("合并转发摘要"))
        column.addView(kit.caption("共 ${message.items.size} 条消息 · 由你配置的回复模型整理")
            .apply { setPadding(0, theme.dp(4), 0, theme.dp(10)) })

        val body = kit.body("正在整理 ${message.items.size} 条消息…", ReplyMetrics.TEXT_ITEM).apply {
            setTextIsSelectable(true)
        }
        text = body
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()).apply {
            addView(body)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        val noticeText = notice()
        val note = kit.caption(noticeText).apply {
            if (noticeText.isBlank()) visibility = View.GONE
            setPadding(0, theme.dp(6), 0, 0)
        }
        footer = note
        column.addView(note, LinearLayout.LayoutParams(-1, -2))

        val busy = kit.loading().apply { showLoading("正在整理 ${message.items.size} 条消息…") }
        loading = busy
        column.addView(busy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })

        val copy = theme.action("复制", primary = true) {
            if (result.isNotBlank()) {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("聊天记录摘要", result))
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        column.addView(copy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
                        // 显示前再清洗一次：模型会把输入里的填充原样复述出来。
                        val clean = with(ForwardDigest) { partial.stripFiller() }
                        result = clean
                        text?.post { text?.text = clean }
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
        val shown = with(ForwardDigest) { content.stripFiller() }
        val failed = shown.startsWith("摘要失败")
        result = shown
        text?.text = shown
        text?.setTextColor(if (failed) theme.error else theme.ink)
        loading?.showLoading(null)
        loading?.visibility = View.GONE
        copy.isEnabled = true
        copy.alpha = 1f
        if (message.omitted > 0) footer?.text = "已省略 ${message.omitted} 条过长或过多的内容"
    }

    fun dismiss() {
        scope.cancel()
        dialog?.dismiss()
    }
}
