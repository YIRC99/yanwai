package dev.jev.wechatmood.reply

import dev.jev.wechatmood.hook.MessageMetadata
import java.util.concurrent.CancellationException

fun interface ReplyHistoryQuery {
    fun query(sql: String, args: Array<String>): List<MessageMetadata>
}

/** Read only the selected conversation; an exact live-page anchor rejects stale account handles. */
object ReplyHistoryReader {
    private const val FIELDS = "msgId, type, isSend, content, talker, createTime, imgPath, msgSvrId"
    private const val ANCHOR_SQL = "SELECT $FIELDS FROM message WHERE talker = ? AND msgId = ? LIMIT 1"
    /**
     * 旧写法对每行做 length(trim(content))，在几百人的群里等于全表扫一遍再排序，
     * 实测取 100 条要三十多秒。改成从锚点 msgId 往前按主键倒序取，空白内容交给
     * ReplyContext.collect 过滤，走索引就快得多。
     */
    private fun historySql(limit: Int) = "SELECT $FIELDS FROM message WHERE talker = ? AND msgId <= CAST(? AS INTEGER) AND type IN (1, 34, 49, 822083633) " +
        "AND isSend IN (0, 1) ORDER BY msgId DESC LIMIT ${limit + 1}"

    fun read(loaded: ReplyContext, sources: List<ReplyHistoryQuery>, limit: Int = ReplyContext.MAX_MESSAGES,
        checkActive: () -> Unit = {}): ReplyContext {
        require(limit in 1..ReplyContext.MAX_MESSAGES)
        fun fallback(reason: String) = loaded.copy(messages = loaded.messages.takeLast(limit),
            trimmed = loaded.trimmed || loaded.messages.size > limit, requestedMessages = limit,
            source = ReplyContextSource.LOADED_PAGE, historyFailure = reason)
        val anchor = loaded.historyAnchor
        val textAnchor = loaded.messages.lastOrNull { it.id > 0 && it.time > 0 }
        val anchorId = anchor?.id ?: textAnchor?.id ?: return fallback("无法核对历史记录，仅参考页面消息")
        if (sources.isEmpty()) return fallback("历史读取尚未就绪，仅参考页面消息；重进聊天或重启微信后重试")
        for (source in sources) {
            checkActive()
            try {
                val found = source.query(ANCHOR_SQL, arrayOf(loaded.talker, anchorId.toString()))
                if (found.size != 1 || found.single().talker != loaded.talker) continue
                val matches = anchor?.matches(found.single())
                    ?: (ReplyContext.collect(loaded.talker, found).messages.singleOrNull() == textAnchor)
                if (!matches) continue
                checkActive()
                val records = source.query(historySql(limit), arrayOf(loaded.talker, anchorId.toString()))
                checkActive()
                if (records.isEmpty() || records.any { it.talker != loaded.talker || it.type !in setOf(1, 34, 49, 822083633) || it.isSend !in 0..1 }) continue
                // 只记类型分布，不含任何聊天内容，用来核对引用消息在库里到底是什么 type。
                dev.jev.wechatmood.core.MoodLog.i("REPLY_HISTORY_TYPES " + records.groupingBy { it.type }.eachCount())
                val result = ReplyContext.collect(loaded.talker, records.sortedWith(compareBy({ it.createdAt }, { it.messageId })), limit)
                // A stale/mismatched table must not replace newer evidence already visible on screen.
                if (result.messages.isEmpty() || (textAnchor != null && result.messages.last().time < textAnchor.time)) continue
                return result.copy(source = ReplyContextSource.LOCAL_HISTORY, latestLoadedId = loaded.latestLoadedId)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* No raw SQL, account identifier or chat content in diagnostics. */ }
        }
        return fallback("历史读取失败或未匹配当前聊天，仅参考页面消息")
    }
}
