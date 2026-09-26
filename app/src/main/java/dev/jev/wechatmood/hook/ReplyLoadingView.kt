package dev.jev.wechatmood.hook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout

/** Drawn locally: the host theme cannot turn the indeterminate indicator into an invisible line. */
internal class ReplyLoadingView(context: Context, theme: ReplyTheme) : LinearLayout(context) {
    private val label = theme.label("", 13f, theme.accent)
    private val ring = object : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = theme.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND }
        var angle = 0f
        override fun onDraw(canvas: Canvas) {
            val inset = paint.strokeWidth
            paint.color = theme.border
            canvas.drawArc(inset, inset, width - inset, height - inset, 0f, 360f, false, paint)
            paint.color = theme.accent
            canvas.drawArc(inset, inset, width - inset, height - inset, angle, 100f, false, paint)
        }
    }
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { ring.angle = it.animatedValue as Float; ring.invalidate() }
    }
    private var loading = false
    init {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        background = theme.shape(theme.soft, 12)
        addView(ring, LayoutParams(theme.dp(26), theme.dp(26)).apply { rightMargin = theme.dp(10) })
        addView(label, LayoutParams(0, -2, 1f))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        ring.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
    }
    fun showLoading(message: String?) {
        loading = message != null
        label.text = message.orEmpty()
        visibility = if (loading) VISIBLE else GONE
        syncAnimation()
    }
    private fun syncAnimation() {
        if (loading && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()) {
            if (!animator.isStarted) animator.start()
        } else animator.cancel()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) syncAnimation() else animator.cancel() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
}
