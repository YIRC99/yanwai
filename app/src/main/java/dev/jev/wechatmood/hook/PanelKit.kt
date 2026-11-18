package dev.jev.wechatmood.hook

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 状态文字的语义色，避免把失败混进正文里。 */
internal enum class PanelTone { NEUTRAL, SUCCESS, WARNING, ERROR }

/**
 * 微信内面板的共用零件：标题、说明、卡片、条目、胶囊按钮、底部选择器。
 *
 * 现有界面（附加面板、帮我回抽屉、分析卡）的数值本来就是一套，
 * 但每个新窗口都在各自手写，圆角和触控高度很快就分叉了。
 * 统一从这里取，新加的窗口自然和旧界面一致。
 */
internal class PanelKit(private val context: Context, val theme: ReplyTheme = ReplyTheme(context)) {

    /** 面板根：surface 底 + 面板圆角 + 细边框，和附加面板同款。 */
    fun root(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
    }

    /** 面板内边距，沿用 20dp 页边距。 */
    fun content(root: LinearLayout): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(theme.dp(20), theme.dp(16), theme.dp(20), theme.dp(16))
        root.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    fun heading(text: String): TextView = theme.label(text, ReplyMetrics.TEXT_TITLE, theme.ink, bold = true)

    fun caption(text: String): TextView = theme.label(text, ReplyMetrics.TEXT_CAPTION, theme.muted)

    /** 分区小标题：沿用分析卡的浅青加粗标签，让新窗口也有同一个识别度。 */
    fun sectionLabel(text: String): TextView =
        theme.label(text, ReplyMetrics.TEXT_CAPTION, theme.accent, bold = true).apply {
            setPadding(0, theme.dp(14), 0, theme.dp(4))
        }

    fun body(text: String, size: Float = ReplyMetrics.TEXT_BODY): TextView =
        theme.label(text, size, theme.ink).apply { setLineSpacing(theme.dp(4).toFloat(), 1f) }

    fun divider(): View = View(context).apply { setBackgroundColor(theme.border) }

    fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
    }

    /** 条目行：主标题 + 说明 + 右箭头，整行可点，高度不低于 48dp。 */
    fun itemRow(title: String, subtitle: String?, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(theme.label(title, ReplyMetrics.TEXT_ITEM, theme.ink))
        if (!subtitle.isNullOrBlank()) {
            texts.addView(theme.label(subtitle, ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
                setPadding(0, theme.dp(2), 0, 0)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(theme.label("›", 20f, theme.muted).apply { setPadding(theme.dp(8), 0, 0, 0) })
        return row
    }

    /** 胶囊筛选按钮：和主按钮同高度，不再压到 34dp。 */
    fun pill(label: String, onClick: () -> Unit): TextView =
        theme.action(label, quiet = true, onClick = onClick).apply {
            textSize = ReplyMetrics.TEXT_CAPTION
            minHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(theme.dp(8), theme.dp(4), theme.dp(8), theme.dp(4))
        }

    /** 状态行：文字永远是唯一的解释来源，颜色只是辅助。 */
    fun status(text: String, tone: PanelTone = PanelTone.NEUTRAL): TextView {
        val color = when (tone) {
            PanelTone.NEUTRAL -> theme.muted
            PanelTone.SUCCESS -> theme.success
            PanelTone.WARNING -> theme.warning
            PanelTone.ERROR -> theme.error
        }
        return theme.label(text, ReplyMetrics.TEXT_CAPTION, color).apply {
            setPadding(0, theme.dp(4), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    /** 加载行：26dp 圆环 + 文案，和抽屉里的加载反馈同一套。 */
    fun loading(): ReplyLoadingView = ReplyLoadingView(context, theme)

    /** 底部面板外壳：透明底、贴底、0.32 遮罩。 */
    fun sheet(): Dialog = Dialog(context).apply {
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
    }

    /** 选项行：48dp、卡片底、选中用主色描边。 */
    fun optionRow(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        val fill = if (selected) theme.soft else theme.card
        val stroke = if (selected) theme.accent else theme.border
        return theme.label(label, ReplyMetrics.TEXT_ITEM, if (selected) theme.accent else theme.ink).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(theme.dp(14), theme.dp(10), theme.dp(14), theme.dp(10))
            isClickable = true; isFocusable = true
            background = RippleDrawable(ColorStateList.valueOf((theme.accent and 0x00ffffff) or 0x22000000),
                theme.shape(fill, ReplyMetrics.RADIUS_CARD, stroke), theme.shape(theme.ink, ReplyMetrics.RADIUS_CARD))
            setOnClickListener { onClick() }
        }
    }

    /** 底部选择器，替代宿主 AlertDialog：圆角、触控高、深浅色都跟着面板走。 */
    fun picker(title: String, options: List<String>, selected: Int = -1, onPick: (Int) -> Unit) {
        val dialog = sheet()
        val root = root()
        val body = content(root)
        body.addView(heading(title))
        options.forEachIndexed { index, option ->
            body.addView(optionRow(option, index == selected) {
                dialog.dismiss()
                onPick(index)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        }
        body.addView(theme.action("取消", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    /** 只读详情，替代 AlertDialog 的 setMessage。 */
    fun info(title: String, message: String) {
        val dialog = sheet()
        val root = root()
        val column = content(root)
        column.addView(heading(title))
        val scroll = PanelMaxScroll(context, (context.resources.displayMetrics.heightPixels * 0.45f).toInt()).apply {
            addView(body(message).apply { setTextIsSelectable(true) })
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}

/** 内容少时只占实际高度，超过上限才滚动。 */
internal class PanelMaxScroll(context: Context, private val limit: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }
}
