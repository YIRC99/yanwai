package com.dogheadstrategist.chat.feature


import android.app.NotificationChannel
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dogheadstrategist.chat.OpenChatReceiver
import com.dogheadstrategist.chat.R

/**
 * 通知摘要的普通通知：顶掉微信那条，用我们的标题和正文。
 *
 * 为什么不用超级岛：
 * 岛对内容宽度、图标、点击都有额外限制，而且 SystemUI 的解析规则随版本变。
 * 普通通知是稳定的，微信自己也是这么发消息的。
 */
object PlainNotify {
    private const val CHANNEL_ID = "communicate_plain"
    private const val CHANNEL_NAME = "通知摘要"
    private const val NOTIFY_ID = 0x0C10

    /**
     * [icon] 是发信人头像，取自微信那条通知的 largeIcon。
     *
     * 头像很关键：同一屏上可能有好几个人同时来消息，只靠文字标题认人比看图慢。
     * 传空时退回微信自己的应用图标，而不是本 App 的——通知出现在用户眼前时
     * 代表的是「微信来消息了」，挂狗头军师的图标会让人以为是另一个应用在推东西。
     */
    fun show(
        context: Context,
        title: String,
        body: String,
        id: Int = NOTIFY_ID,
        durationMs: Long = 0L,
        openNick: String = "",
        icon: Bitmap? = null,
    ): Boolean = runCatching {
        ensureChannel(context)
        // 用 Receiver 而不是 Activity：点通知时不要在任务栈里插一层狗头军师
        val target = if (openNick.isNotBlank()) {
            Intent(context, OpenChatReceiver::class.java).apply {
                putExtra(OpenChatReceiver.EXTRA_NICK, openNick)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = target?.let {
            if (openNick.isNotBlank()) {
                PendingIntent.getBroadcast(
                    context,
                    id,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getActivity(
                    context,
                    id,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }
        // smallIcon 必须是我们自己的：系统要求通知的小图标属于发布它的应用，
        // 用微信的包名资源会抛异常。所以小图标留狗头军师，大图放头像。
        val large = icon ?: wechatIcon(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .apply { if (large != null) setLargeIcon(large) }
            .setContentTitle(title.trim().ifBlank { "微信" })
            .setContentText(body.trim())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.trim()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .apply {
                if (pending != null) setContentIntent(pending)
                // 到点自己消失，不用手动划。0 表示一直留着
                if (durationMs > 0) setTimeoutAfter(durationMs)
            }
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
        true
    }.getOrDefault(false)

    fun dismiss(context: Context) = runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFY_ID)
    }

    /**
     * 微信自己的应用图标，用作没有头像时的兜底。
     *
     * 取不到（微信没装、包名变了）就返回 null，此时不设 largeIcon，
     * 通知会只显示小图标，也比挂错应用图标强。
     */
    private fun wechatIcon(context: Context): Bitmap? = runCatching {
        val drawable = context.packageManager.getApplicationIcon("com.tencent.mm")
        drawableToBitmap(drawable)
    }.getOrNull()

    /** Drawable 转 Bitmap。图标可能没有固有尺寸（VectorDrawable），按标准 96dp 兜底。 */
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            // 渠道只在首次创建时生效，以后改名系统不理会。名字对不上就删掉重建，
            // 否则系统设置里会一直挂着旧名。代价是该渠道的用户设置被重置。
            if (existing.name?.toString() == CHANNEL_NAME) return
            manager.deleteNotificationChannel(CHANNEL_ID)
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "用 AI 归纳替换微信的消息通知"
                setShowBadge(true)
            },
        )
    }
}
