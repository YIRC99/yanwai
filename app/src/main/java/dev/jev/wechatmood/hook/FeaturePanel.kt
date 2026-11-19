package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch

/**
 * 「附加」入口弹出的功能面板：帮回、话题、统计、档案，最后是自动分析开关。
 * 纯原生控件，避免和宿主资源表冲突；零件统一取自 PanelKit。
 */
internal class FeaturePanel(
    private val activity: Activity,
    private val talker: String?,
    private val topicsAvailable: Boolean,
    private val factsAvailable: Boolean,
    private val analysisEnabled: Boolean,
    private val onReply: () -> Unit,
    private val onTopics: () -> Unit,
    private val onStats: () -> Unit,
    private val onFacts: () -> Unit,
    private val onAnalysisChanged: (Boolean) -> Unit,
) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private var dialog: Dialog? = null

    fun show() {
        val dialog = Dialog(activity).also { this.dialog = it }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(16), theme.dp(20), theme.dp(20))
            background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
        }

        body.addView(kit.heading("附加"))
        body.addView(kit.caption(
            if (talker == null) "当前聊天尚未识别，部分功能不可用" else "针对当前聊天"
        ).apply { setPadding(0, theme.dp(4), 0, theme.dp(12)) })

        if (topicsAvailable) {
            body.addView(item("帮我回", "参考最近聊天，生成 3–5 条候选", onReply))
            body.addView(item("找找话题", "聊不下去时给几个新方向", onTopics))
        }
        body.addView(item("群聊统计", "消息量、活跃成员与时段", onStats))
        if (factsAvailable) body.addView(item("关系档案", "整理过的事实，可删除", onFacts))

        body.addView(kit.divider(), LinearLayout.LayoutParams(-1, 1).apply { topMargin = theme.dp(12) })

        val switch = Switch(activity).apply {
            isChecked = analysisEnabled
            text = "自动分析"
            textSize = ReplyMetrics.TEXT_BODY
            setTextColor(theme.ink)
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(0, theme.dp(10), 0, theme.dp(4))
            setOnCheckedChangeListener { _, value -> onAnalysisChanged(value) }
        }
        body.addView(switch, LinearLayout.LayoutParams(-1, -2))

        body.addView(kit.caption("开启后，收到的文字与语音会在气泡下方显示情绪与解读"))

        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    /** 功能项：主标题 + 说明，右侧一个浅色箭头，整行可点。 */
    private fun item(title: String, subtitle: String, action: () -> Unit): View {
        // 先收起面板，再执行动作，否则统计窗口会叠在它上面。
        val row = kit.itemRow(title, subtitle) {
            dialog?.dismiss()
            action()
        }
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = theme.dp(8) }
        return row
    }
}
