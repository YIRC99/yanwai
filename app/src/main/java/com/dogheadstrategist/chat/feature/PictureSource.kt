package com.dogheadstrategist.chat.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把微信聊天里的一张图压成可以直接塞进请求的 base64。
 *
 * 这个类跑在微信进程里（长按发生在那边），所以：
 * - 读的是微信自己的图片缓存目录，天然有权限，不需要额外申请存储权限；
 * - 所有异常都要在自己的边界内吞掉。在微信主进程里抛 OOM 会把微信一起带走，
 *   宁可这一次识别失败，也不能让用户的微信闪退。
 *
 * 为什么必须压缩而不是原图直传：
 *   微信原图常见 3~5MB，base64 还要再涨三分之一，很多网关的 body 上限是 10MB；
 *   更要命的是 decode 一张 8000×6000 的原图要占近 200MB 内存，在微信进程里
 *   这一步就等于一颗定时炸弹。缩到长边 1568 / JPEG q80 之后产物通常 200~400KB，
 *   文字类截图也还看得清。
 */
object PictureSource {

    /** 长边上限。1568 是视觉模型普遍能接受的细节量级，再大只会白烧 token。 */
    const val DEFAULT_MAX_EDGE = 1568

    /** JPEG 质量。80 是压缩率和文字清晰度的平衡点。 */
    const val DEFAULT_QUALITY = 80

    /** 超过这个高宽比就当成聊天记录长截图，按宽度缩放，否则字会被缩成一团。 */
    private const val LONG_SCREENSHOT_RATIO = 3.2f

    /** 长截图按宽度算时的目标宽度。 */
    private const val SCREENSHOT_WIDTH = 1080

    /** 缩放后的高度上限，纯粹是防 OOM 的最后一道闸。 */
    private const val MAX_TARGET_HEIGHT = 12000

    data class Encoded(
        /** 可直接拼进 data:image/jpeg;base64, 的部分。 */
        val base64: String,
        val originalBytes: Long,
        val encodedBytes: Int,
        val width: Int,
        val height: Int,
    )

    /**
     * 读文件 + 缩放 + 转 JPEG + base64。
     *
     * @throws IllegalArgumentException 文件不存在、为空，或是微信没解开的 .dat
     * @throws IllegalStateException 缩放后仍然过大（超长截图）
     */
    fun encode(
        path: String,
        maxEdge: Int = DEFAULT_MAX_EDGE,
        quality: Int = DEFAULT_QUALITY,
    ): Encoded {
        val file = File(path)
        if (!file.exists()) {
            // 图片没下载过、被系统清理、或者微信只留了 .dat 没解出明图
            throw IllegalArgumentException("图片文件不存在，可能还没下载或已被清理")
        }
        if (file.length() <= 0L) throw IllegalArgumentException("图片文件是空的")
        if (path.endsWith(".dat", ignoreCase = true)) {
            // 微信的 .dat 是 AES 加密的，自己解要处理 key 推导和多版本差异，
            // 投入产出比太差。这里直接说清原因，让用户先点开图片一次。
            throw IllegalArgumentException("这张图还没被微信解开，请先点开图片再长按")
        }

        val originalBytes = file.length()
        // 解码这一步最容易出两种事：解码器抛自己的异常、或者直接 OOM。
        // 两种都要在这里收口，绝不让 Throwable 冒到微信进程的调用栈上。
        val bitmap = try {
            decodeScaled(file, maxEdge)
        } catch (tooLong: IllegalStateException) {
            throw tooLong
        } catch (t: Throwable) {
            throw IllegalArgumentException("图片解码失败：${t.javaClass.simpleName}")
        }
        val encoded = encodeBitmap(bitmap, maxEdge, quality, originalBytes)
        bitmap.recycle()
        return encoded
    }

    /**
     * 从正在显示的 View 里取图。
     *
     * 比读文件可靠得多：微信本地图片多半是加密 .dat，或者压根没下载下来。
     * 但既然用户能长按到它，图一定已经解码并画在屏幕上了。直接拿显示中的
     * Bitmap，绕开路径、解密、下载状态这一整串不确定性。
     *
     * 拿到的这个 Bitmap 是微信自己的对象，界面正在用，**绝对不能 recycle**
     * ——回收了会黑图甚至闪退。所以先复制一份，后续处理全在副本上做。
     */
    fun encodeFromView(
        view: View,
        maxEdge: Int = DEFAULT_MAX_EDGE,
        quality: Int = DEFAULT_QUALITY,
    ): Encoded {
        val shown = findShownBitmap(view)
            ?: throw IllegalArgumentException("这张图还没加载出来，点开图片再长按试试")
        val copy = runCatching { shown.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: throw IllegalArgumentException("图片读取失败")
        return try {
            encodeBitmap(copy, maxEdge, quality, originalBytes = 0L)
        } finally {
            if (!copy.isRecycled) copy.recycle()
        }
    }

    /** 先看自己，再沿父链往上找，最后往下找。图片气泡的层级各版本不一样，三面都试。 */
    private fun findShownBitmap(view: View): Bitmap? {
        imageOf(view)?.let { return it }
        var parent: ViewParent? = view.parent
        while (parent is View) {
            imageOf(parent)?.let { return it }
            parent = parent.parent
        }
        return findBitmapBelow(view)
    }

    private fun findBitmapBelow(view: View): Bitmap? {
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index) ?: continue
            imageOf(child)?.let { return it }
            findBitmapBelow(child)?.let { return it }
        }
        return null
    }

    private fun imageOf(view: View): Bitmap? =
        (view as? ImageView)?.drawable?.let { (it as? BitmapDrawable)?.bitmap }
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }

    /** 压缩主体：文件和解码好的 Bitmap 两条入口共用。 */
    private fun encodeBitmap(bitmap: Bitmap, maxEdge: Int, quality: Int, originalBytes: Long): Encoded {
        val scaled = scaleDown(bitmap, maxEdge)
        val flattened = flatten(scaled)
        val bytes = compress(flattened, quality)
        val width = flattened.width
        val height = flattened.height
        if (flattened !== scaled) flattened.recycle()
        if (scaled !== bitmap) scaled.recycle()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return Encoded(base64, originalBytes, bytes.size, width, height)
    }

    /** 已经解码好的 Bitmap 没有 setTargetSize 可用，手动缩。缩放规则和解码时保持一致。 */
    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) throw IllegalArgumentException("图片尺寸异常")
        val scale = if (height.toFloat() / width > LONG_SCREENSHOT_RATIO) {
            minOf(1f, SCREENSHOT_WIDTH.toFloat() / width)
        } else {
            val longEdge = maxOf(width, height)
            if (longEdge > maxEdge) maxEdge.toFloat() / longEdge else 1f
        }
        if (scale >= 1f) return bitmap
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        if (targetHeight > MAX_TARGET_HEIGHT) {
            throw IllegalStateException("截图太长了（${targetHeight}px），请裁剪后再试")
        }
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    /** 按目标尺寸解码。minSdk 28 起 ImageDecoder 可用，不用再写 BitmapFactory 降级。 */
    private fun decodeScaled(file: File, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(file)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            if (width <= 0 || height <= 0) return@decodeBitmap

            val scale = if (height.toFloat() / width > LONG_SCREENSHOT_RATIO) {
                // 聊天记录长截图：按宽度定，保住字
                minOf(1f, SCREENSHOT_WIDTH.toFloat() / width)
            } else {
                val longEdge = maxOf(width, height)
                if (longEdge > maxEdge) maxEdge.toFloat() / longEdge else 1f
            }

            val targetWidth = (width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (height * scale).toInt().coerceAtLeast(1)
            if (targetHeight > MAX_TARGET_HEIGHT) {
                throw IllegalStateException("截图太长了（${targetHeight}px），请裁剪后再试")
            }
            decoder.setTargetSize(targetWidth, targetHeight)
            // SOFTWARE 避免 GPU 纹理开销，在别人的进程里省内存优先
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /**
     * 透明通道铺白底。
     *
     * PNG/WebP 带 alpha，直接压 JPEG 会得到黑底（JPEG 不支持透明），
     * 白底黑字才是截图该有的样子。没有透明通道就原样返回，省一次拷贝。
     */
    private fun flatten(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return result
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            throw IllegalStateException("图片压缩失败")
        }
        return out.toByteArray()
    }
}
