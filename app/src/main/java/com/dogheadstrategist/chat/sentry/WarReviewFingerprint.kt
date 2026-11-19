package com.dogheadstrategist.chat.sentry

import android.content.Context
import android.net.Uri
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.WarReviewPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 把「AI 帮我写的话」记进指纹库。
 *
 * 狗头军师不直接发消息，它只是把文本写进输入框、由用户点发送，
 * 所以发出去之后数据库里和用户自己打的话没有任何区别。唯一能区分来源的时机，
 * 就是文本进入输入框的这一刻——在这里记账，之后采集时才能把它剔掉。
 *
 * 记账走 IPC 到 app 进程（指纹库在那边），放后台线程做，不阻塞 UI。
 * 低于 [WarReviewPrefs.FINGERPRINT_MIN_LENGTH] 的短句不记：撞车概率太高，
 * 宁可漏排也不能把用户自己说的「好的」当成 AI 写的扔掉。
 */
object WarReviewFingerprint {
    private const val SETTINGS_URI = "content://com.dogheadstrategist.chat.settings"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun remember(context: Context, text: String) {
        val clean = text.trim()
        if (clean.length < WarReviewPrefs.FINGERPRINT_MIN_LENGTH) return
        val app = context.applicationContext ?: context
        scope.launch {
            runCatching {
                app.contentResolver.call(Uri.parse(SETTINGS_URI), "war_review_fp", clean, null)
                MoodLog.i("WAR_REVIEW_FP_ADD len=${clean.length}")
            }.onFailure { MoodLog.w("WAR_REVIEW_FP_FAILED " + it.javaClass.simpleName) }
        }
    }
}
