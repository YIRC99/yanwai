package com.dogheadstrategist.chat.sentry

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.WarReviewPrefs
import java.util.concurrent.Executors

/**
 * 我的风格的实时采集入口：我自己每发一条文字消息，就把这一条推给 app 进程。
 *
 * 不走「定时扫库 + 游标」那套——用户要的是「从现在起我发的每一句」，
 * 事件驱动天然满足这个语义，也不需要区分哪条是开启前、哪条是开启后。
 *
 * 由 [com.dogheadstrategist.chat.hook.MessageSniffer] 在聊天列表绑定时调用。
 * 同一条消息会因为滚动、刷新反复绑定，去重交给 app 进程的 msgId 集合。
 */
object WarReviewCapture {
    private const val SETTINGS_URI = "content://com.dogheadstrategist.chat.settings"

    /** 单线程：消息可能连着发，串行送过去，避免并发写 prefs 互相覆盖。 */
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "war-review-capture") }

    fun capture(context: Context, messageId: Long, text: String) {
        if (messageId <= 0) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (!WarReviewPrefs.enabledForRequest()) return
        val app = context.applicationContext ?: context
        worker.execute {
            runCatching {
                app.contentResolver.call(
                    Uri.parse(SETTINGS_URI),
                    "war_review_sent",
                    clean,
                    Bundle().apply { putLong("id", messageId) }
                )
            }.onFailure {
                MoodLog.w("WAR_REVIEW_SENT_FAILED " + it.javaClass.simpleName)
            }
        }
    }
}
