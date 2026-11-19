package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import com.dogheadstrategist.chat.reply.ReplySettings
import org.json.JSONArray
import kotlin.random.Random
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通知摘要：用 AI 归纳替换微信原来的通知。
 *
 * 通知标题是发信人简称（AI 压缩过，带 emoji），正文是 AI 概括的一句话。
 */
object Communicate : StrategistModule {
    override val feature = StrategistFeature.COMMUNICATE

    /**
     * 测试：从自动起草存下来的本地会话里随机抽一个，走一遍和真实消息相同的 AI 总结再发通知。
     *
     * 和真实路径的差别只有通知 id：真实消息按会话区分，测试固定用会话名的哈希。
     *
     * @return 结果与一句给界面看的话
     */
    suspend fun testPush(context: Context): Pair<Boolean, String> = runCatching {
        val raw = SentryStore.records(context)
        val array = JSONArray(raw.ifBlank { "[]" })
        if (array.length() == 0) {
            return false to "本地还没有聊天记录，先让自动起草跑一会儿"
        }
        val session = array.optJSONObject(Random.nextInt(array.length()))
            ?: return false to "会话数据格式不对"
        val nick0 = session.optString("nick").ifBlank { "微信" }
        val (speaker0, body0) = lastMessage(session.optJSONArray("messages"))
        if (body0.isBlank()) {
            return false to "$nick0 的会话里没有文字消息"
        }
        // records 里的正文是「wxid:\n内容」，真名在 speaker 字段。重组成微信通知的
        // 样子再往下走，否则测出来的发信人是 wxid，和真机上看到的对不上
        var text0 = composeText(session.optString("talker"), speaker0, body0)
        // 有触发门槛时多抽几条，找一条够长的——抽到「好的」这种就没法验证归纳效果
        val minChars = CommunicatePrefs.minTriggerChars(context)
        var nick = nick0
        var text = text0
        if (minChars > 0 && text.length < minChars) {
            var found = false
            var attempts = 0
            while (attempts < 30 && !found) {
                attempts++
                val s = array.optJSONObject(Random.nextInt(array.length())) ?: continue
                val (spk, bd) = lastMessage(s.optJSONArray("messages"))
                if (bd.isEmpty() || bd.length < minChars) continue
                nick = s.optString("nick").ifBlank { "微信" }
                text = composeText(s.optString("talker"), spk, bd)
                found = true
            }
            if (!found) {
                return false to "抽了 30 条都不够 $minChars 字，把触发字数调低些"
            }
        }

        val maxChars = CommunicatePrefs.summaryMaxChars(context)
        val maxLeft = CommunicatePrefs.senderMaxChars(context)
        val durationMs = CommunicatePrefs.displaySec(context) * 1000L

        // 和真实路径一样调模型。没配模型时退回原文，并说明原因
        val settings = ReplySettings.load(context)
        if (settings == null || !settings.isConfigured) {
            val shown = PlainNotify.show(context, nick.take(maxLeft), short(text, maxChars), nick.hashCode(), durationMs, nick)
            return if (shown) {
                true to "没配模型，已用原文推送：$nick"
            } else {
                false to "推送失败，看日志 COMMUNICATE_PLAIN_FAILED"
            }
        }

        // 测试也走真实规则判群聊，不然测出来的效果和实际推岛不一致。
        // nick 本来就取自这份记录，一定能查到 talker，所以测试模式永远是精确判定
        val testTalker = SentryStore.findTalker(context, nick)
        val groupHint = if (testTalker.isNotBlank()) testTalker.endsWith("@chatroom") else null

        val timeoutMs = CommunicatePrefs.aiTimeoutSec(context) * 1000L
        val line = runCatching {
            withTimeoutOrNull(timeoutMs) {
                ReplyHttpClient().summarize(settings, nick, text, maxChars, maxLeft, groupHint)
            }
        }.getOrNull()

        val left = line?.left ?: nick.take(maxLeft)
        val right = line?.right ?: short(text, maxChars)
        val shown = PlainNotify.show(context, left, right, nick.hashCode(), durationMs, nick)
        if (!shown) {
            return false to "推送失败，看日志 COMMUNICATE_PLAIN_FAILED"
        }
        return if (line == null) {
            true to "模型没给出总结，已用原文推送：$left · $right"
        } else {
            true to "已推送：$left · $right"
        }
    }.getOrElse {
        false to ("测试异常：" + it.javaClass.simpleName)
    }

    /** 收掉通知。 */
    fun dismiss(context: Context) = PlainNotify.dismiss(context)

    /** 从后往前找第一条非空文字。跳过语音/图片/位置这类 XML 消息，它们不是人话。 */
    /**
     * 取最后一条文字消息，返回 (说话人, 正文)。
     *
     * records 里的 text 形如「wxid_xxx:\n内容」，那个前缀是微信自己拼的，
     * 真正的昵称在 speaker 字段里——直接拿 text 去拆会把 wxid 当成发信人。
     */
    private fun lastMessage(messages: JSONArray?): Pair<String, String> {
        if (messages == null) return "" to ""
        for (index in messages.length() - 1 downTo 0) {
            val item = messages.optJSONObject(index) ?: continue
            var text = item.optString("text").trim()
            if (text.isEmpty()) continue
            if (text.startsWith("<msg")) continue
            // 剥掉「xxx:\n」前缀，最多 40 字，超过的当正文里正常的冒号
            val cut = text.indexOf(":\n")
            if (cut > 0 && cut <= 40) text = text.substring(cut + 2).trim()
            return item.optString("speaker").trim() to text
        }
        return "" to ""
    }

    /** 拼成微信通知里的形态：群聊「张三: 内容」，单聊就是内容本身。 */
    private fun composeText(talker: String, speaker: String, body: String): String =
        if (talker.endsWith("@chatroom") && speaker.isNotBlank()) "$speaker: $body" else body

    /** 退回原文时的收短，避免一条长消息糊满整个通知。 */
    private fun short(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit - 1) + "…"
}
