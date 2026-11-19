package com.dogheadstrategist.chat.sentry

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.feature.WarReviewPrefs
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 我的风格：定期把我发过的话提炼成一段「我怎么说话」的画像。
 *
 * 采集不在这里——发消息的那一刻由 [WarReviewCapture] 实时推给 app 进程，
 * 所以这个类只管一件事：攒够了没有、该不该调模型整理一次。
 *
 * 这样就不存在「扫历史」的问题：库里有什么、什么时候的，都跟它无关，
 * 它看到的只有用户开启之后、亲手发出去的每一句。
 */
object WarReviewAgent {
    private const val MODULE = "com.tencent.mm"
    private const val SETTINGS_URI = "content://com.dogheadstrategist.chat.settings"

    /** 两次检查之间至少隔这么久。tick 每 2.5 秒一轮，不能每轮都问。 */
    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L

    private var lastCheckAt = 0L
    private val running = AtomicBoolean(false)

    /** 由 [SentryAgent] 的常驻循环每轮调用，内部自己限流。 */
    suspend fun maybeRun(context: Context) {
        if (context.packageName != MODULE) return
        val uri = Uri.parse(SETTINGS_URI)

        // state 顺带把「立即整理」标志读走并清掉，所以每轮都要问一次
        val state = runCatching {
            context.contentResolver.call(uri, "war_review_state", null, null)
        }.getOrNull() ?: return
        val force = state.getBoolean("force", false)

        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        if (!force && !WarReviewPrefs.enabledForRequest()) return
        if (!running.compareAndSet(false, true)) return
        try {
            runOnce(context, uri, force)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            MoodLog.w("WAR_REVIEW_FAILED " + error.javaClass.simpleName + " " + (error.message ?: ""))
        } finally {
            running.set(false)
        }
    }

    private suspend fun runOnce(context: Context, uri: Uri, force: Boolean) {
        // 空 batch：只问「现在的待整理样本够不够」，不新增任何采集
        val result = runCatching {
            context.contentResolver.call(uri, "war_review_sync", "[]",
                Bundle().apply { putBoolean("force", force) })
        }.getOrNull() ?: return
        if (!result.getBoolean("need", false)) return

        val samples = runCatching {
            val array = JSONArray(result.getString("samples").orEmpty())
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.optString("t")?.takeIf { t -> t.isNotBlank() } }
        }.getOrDefault(emptyList())
        if (samples.isEmpty()) {
            MoodLog.w("WAR_REVIEW_NO_SAMPLES")
            return
        }

        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) {
            MoodLog.w("WAR_REVIEW_SKIP_NOT_CONFIGURED")
            return
        }

        val reviewed = ReplyHttpClient().review(
            settings,
            samples,
            result.getString("profile").orEmpty(),
            result.getString("vocab").orEmpty(),
        )
        if (reviewed.profile.isBlank()) return
        // 保存失败必须留痕：以前这里静默吞掉，紧接着还会打 WAR_REVIEW_DONE，
        // 于是「画像没写进去」看起来跟成功一模一样。
        runCatching { context.contentResolver.call(uri, "war_review_save", reviewed.profile, null) }
            .onFailure { MoodLog.w("WAR_REVIEW_SAVE_FAILED " + it.javaClass.simpleName + " " + (it.message ?: "")) }
        // 词库走增量：只有这次新发现的词才回传，已有的不重发
        if (reviewed.newWords.isNotEmpty()) {
            val array = JSONArray()
            reviewed.newWords.forEach {
                val avoidArray = JSONArray()
                it.avoid.forEach { word -> avoidArray.put(word) }
                array.put(
                    JSONObject().put("cat", it.cat).put("word", it.word).put("avoid", avoidArray)
                )
            }
            runCatching { context.contentResolver.call(uri, "war_review_vocab", array.toString(), null) }
        }
        MoodLog.i("WAR_REVIEW_DONE len=${reviewed.profile.length} newWords=${reviewed.newWords.size} samples=${samples.size}")
    }
}
