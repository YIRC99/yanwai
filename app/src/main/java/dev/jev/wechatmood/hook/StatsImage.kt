package dev.jev.wechatmood.hook

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.jev.wechatmood.reply.GroupStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把统计和 AI 观察画成一张分享用的图片，保存到相册。 */
object StatsImage {
    private const val WIDTH = 1080
    private const val PAD = 72f
    /** 每 dp 的像素数：分享图和界面共用 360dp 宽度基准，圆角看起来才一致。 */
    private val DP = WIDTH / 360f
    // 固定深色配色，取值与 ReplyTheme 的深色一套相同；不跟随宿主主题。
    private val BG = Color.parseColor("#111B17")
    private val CARD = Color.parseColor("#1C2A24")
    private val INK = Color.parseColor("#E1EEE6")
    private val MUTED = Color.parseColor("#A6B9AF")
    private val ACCENT = Color.parseColor("#8CD7C0")

    fun render(stats: GroupStats, names: Map<String, String>, analysis: String?, title: String): Bitmap {
        val titlePaint = text(48f, INK, Typeface.BOLD)
        val subPaint = text(24f, MUTED)
        val numberPaint = text(56f, INK, Typeface.BOLD)
        val labelPaint = text(22f, MUTED)
        val rowPaint = text(28f, INK)
        val rowValuePaint = text(26f, MUTED)
        val bodyPaint = text(28f, INK).apply { isAntiAlias = true }
        val metaPaint = text(20f, MUTED)

        val bodyLines = analysis?.takeIf { it.isNotBlank() }?.let { wrap(it, bodyPaint, WIDTH - PAD * 2 - 48) } ?: emptyList()
        val height = (PAD + 60 + 40 + 130 + 40 + stats.topSenders.size * 64 + 40 +
            (if (bodyLines.isEmpty()) 0 else bodyLines.size * 44 + 90) + 60 + PAD).toInt().coerceAtLeast(900)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG)

        var y = PAD + 40
        canvas.drawText(title, PAD, y, titlePaint)
        y += 44
        canvas.drawText("最近 ${stats.total} 条消息 · ${stats.senders} 人发言", PAD, y, subPaint)
        y += 46

        // 三块数字
        val cells = listOf(
            stats.total.toString() to "条消息",
            stats.senders.toString() to "人发言",
            (stats.media + stats.voice).toString() to "图片语音",
            stats.activeHours.size.toString() to "个时段",
        )
        val cellWidth = (WIDTH - PAD * 2) / cells.size
        cells.forEachIndexed { index, (value, label) ->
            val cx = PAD + cellWidth * index + cellWidth / 2
            canvas.drawText(value, cx - numberPaint.measureText(value) / 2, y + 50, numberPaint)
            canvas.drawText(label, cx - labelPaint.measureText(label) / 2, y + 84, labelPaint)
        }
        y += 130

        if (stats.topSenders.isNotEmpty()) {
            canvas.drawText("最活跃", PAD, y, labelPaint)
            y += 36
            val max = stats.topSenders.first().second.coerceAtLeast(1)
            stats.topSenders.forEach { (name, count) ->
                val shown = names[name] ?: fallbackName(name)
                canvas.drawText(shown.take(14), PAD, y, rowPaint)
                val value = "$count 条"
                canvas.drawText(value, WIDTH - PAD - rowValuePaint.measureText(value), y, rowValuePaint)
                // 条形长度按最高值归一，直观看出谁说得最多。
                val barWidth = (WIDTH - PAD * 2) * (count.toFloat() / max)
                canvas.drawRoundRect(PAD, y + 12, PAD + barWidth, y + 22, 6 * DP, 6 * DP, fillPaint(ACCENT))
                y += 64
            }
            y += 12
        }

        if (bodyLines.isNotEmpty()) {
            canvas.drawRoundRect(PAD, y, WIDTH - PAD, y + bodyLines.size * 44 + 64, 14 * DP, 14 * DP, fillPaint(CARD))
            y += 46
            bodyLines.forEach { line ->
                canvas.drawText(line, PAD + 24, y, bodyPaint)
                y += 44
            }
            y += 24
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        canvas.drawText("言外 · $stamp", PAD, height - PAD / 2, metaPaint)
        return bitmap
    }

    fun save(context: Context, bitmap: Bitmap): Uri? = runCatching {
        val name = "yanwai-stats-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/言外")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        uri
    }.getOrNull()

    /** 名字不可见时用占位，避免图片里出现空行。 */
    private fun fallbackName(raw: String): String {
        val visible = raw.trim().any { ch ->
            !ch.isWhitespace() && !ch.isISOControl() && Character.getType(ch) != Character.FORMAT.toInt()
        }
        if (!visible) return "未获取昵称"
        return if (raw.length > 10) raw.take(10) + "…" else raw
    }

    private fun text(size: Float, color: Int, style: Int = Typeface.NORMAL) = Paint().apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(Typeface.DEFAULT, style)
        isAntiAlias = true
    }

    private fun fillPaint(color: Int) = Paint().apply {
        this.color = color
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    /** 按像素宽度折行，中文按字符断，英文按空格优先。 */
    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (ch in text) {
            if (ch == '\n') {
                lines += current.toString()
                current = StringBuilder()
                continue
            }
            if (paint.measureText(current.toString() + ch) > maxWidth) {
                lines += current.toString()
                current = StringBuilder()
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines.take(14)
    }
}
