package dev.jev.wechatmood.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import java.util.WeakHashMap

/** Backdrop only is blurred. Text stays opaque; unsupported host rendering falls back to tint. */
// Injected into WeChat with its host context; module AppCompat themes/resources are not available.
@SuppressLint("AppCompatCustomView")
class FrostedAnalysisView(context: Context) : TextView(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val clip = Path()
    private val position = IntArray(2)
    private val rootPosition = IntArray(2)
    private var backdrop: Backdrop? = null
    private var attachedRoot: View? = null
    private val radius get() = 12f * resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val root = rootView
        attachedRoot = root
        backdrop = backdrops.getOrPut(root) { Backdrop(root) }.also { it.users++ }
    }

    override fun onDetachedFromWindow() {
        val root = attachedRoot
        backdrop?.let { if (--it.users == 0) { it.close(); if (root != null) backdrops.remove(root) } }
        backdrop = null; attachedRoot = null
        super.onDetachedFromWindow()
    }

    override fun draw(canvas: Canvas) {
        if (capturing) return // Exclude every analysis card from the shared backdrop, including its text.
        val checkpoint = canvas.save()
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        backdrop?.bitmap?.takeUnless { it.isRecycled }?.let { image ->
            val root = attachedRoot ?: return@let
            getLocationOnScreen(position); root.getLocationOnScreen(rootPosition)
            val left = (rootPosition[0] - position[0]).toFloat()
            val top = (rootPosition[1] - position[1]).toFloat()
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawBitmap(image, null, RectF(left, top, left + root.width, top + root.height), paint)
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat().coerceAtLeast(1f),
            0xC02B2D34.toInt(), 0xB8202229.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = 0x32E3E8F0; paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        bounds.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
        canvas.drawRoundRect(bounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(checkpoint)
        super.draw(canvas)
    }

    private class Backdrop(private val root: View) : ViewTreeObserver.OnPreDrawListener {
        var users = 0
        var bitmap: Bitmap? = null
        private var lastCapture = -200L
        private var disabled = false
        private val observer = root.viewTreeObserver
        init { observer.addOnPreDrawListener(this) }

        override fun onPreDraw(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (disabled || capturing || users == 0 || now - lastCapture < 180 || root.width == 0 || root.height == 0) return true
            lastCapture = now
            try {
                // One low-resolution capture per host window, shared by all visible cards.
                val scale = minOf(1f / 12, 160f / root.width, 280f / root.height)
                val w = (root.width * scale).toInt().coerceAtLeast(1)
                val h = (root.height * scale).toInt().coerceAtLeast(1)
                if (bitmap?.width != w || bitmap?.height != h) {
                    bitmap?.recycle(); bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                val image = requireNotNull(bitmap)
                image.eraseColor(Color.TRANSPARENT)
                val capture = Canvas(image)
                capture.scale(w.toFloat() / root.width, h.toFloat() / root.height)
                capturing = true
                root.draw(capture)
                soften(image)
            } catch (_: Exception) {
                disabled = true
                bitmap?.recycle(); bitmap = null
            } finally { capturing = false }
            return true
        }

        private fun soften(image: Bitmap) {
            val w = image.width; val h = image.height
            val pixels = IntArray(w * h)
            val output = IntArray(pixels.size)
            image.getPixels(pixels, 0, w, 0, 0, w, h)
            // Separable five-tap blur on the downsampled image, no RenderEffect on the text/view.
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dx in -2..2) {
                    val c = pixels[y * w + (x + dx).coerceIn(0, w - 1)]
                    a += Color.alpha(c); r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                }
                output[y * w + x] = Color.argb(a / 5, r / 5, g / 5, b / 5)
            }
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (dy in -2..2) {
                    val c = output[(y + dy).coerceIn(0, h - 1) * w + x]
                    a += Color.alpha(c); r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                }
                pixels[y * w + x] = Color.argb(a / 5, r / 5, g / 5, b / 5)
            }
            image.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        fun close() {
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            bitmap?.recycle(); bitmap = null
        }
    }

    companion object {
        private var capturing = false
        private val backdrops = WeakHashMap<View, Backdrop>()
    }
}
