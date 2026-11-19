package com.dogheadstrategist.chat.sentry

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.dogheadstrategist.chat.feature.AideDigest
import com.dogheadstrategist.chat.feature.AidePrefs
import com.dogheadstrategist.chat.feature.AiStats
import com.dogheadstrategist.chat.feature.Communicate
import com.dogheadstrategist.chat.feature.CommunicatePrefs
import com.dogheadstrategist.chat.feature.PlainNotify
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.SentryPrefs
import com.dogheadstrategist.chat.feature.SentryQueue
import com.dogheadstrategist.chat.feature.SentryStore
import com.dogheadstrategist.chat.reply.CommunicateProtocol
import com.dogheadstrategist.chat.reply.ReplyContext
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import com.dogheadstrategist.chat.reply.ReplyMessage
import com.dogheadstrategist.chat.reply.ReplySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动起草的通知入口（app 进程）。
 *
 * 除了把通知投进队列，这里还多做一件事：**就地生成建议**。
 *
 * 原因很实际——微信被 NoActive 之类的工具冻结时，跑在微信进程里那套 agent 一行代码都
 * 不执行，光把通知塞进队列等于石沉大海。而 app 进程不在冻结名单里，配合微信解冻期间
 * 同步过来的聊天记录副本，就能自己把建议生成好，等于绕开了冻结。
 *
 * 拿不到记录副本的会话（很久没聊过、或是陌生人）仍然走原来的队列路径，等微信进程解冻
 * 后处理；再不行还有「打开聊天时补生成」兜底。
 *
 * 去重是必要的：微信更新同一条通知（比如多条消息合并成「2条消息」）会重复回调，
 * 若不去重会把同一会话反复塞进队列，也会重复调用模型。
 */
class SentryNotificationService : NotificationListenerService() {
    private val recent = HashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 进程可能是被杀后重启的，队列里积压的条目要靠这里接上
        runCatching { AideDigest.onServiceConnected(this) }
            .onFailure { MoodLog.w("AIDE_TICKER_START_FAILED " + it.javaClass.simpleName) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val source = sbn ?: return
        if (source.packageName != PACKAGE) return

        val notification = source.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return


        // 微信自身的系统提示（如「微信正在运行」）没有会话语义，直接跳过。
        if (title == "微信" || title == PACKAGE) return

        if (isDuplicate(title, text)) return

        // 通知摘要：完全独立于自动起草——只看自己的开关，不看自动起草的启用开关和白名单。
        if (CommunicatePrefs.enabled(this)) {
            // 微信把发信人头像放在 largeIcon 里。同一屏可能好几个人同时来消息，
            // 光看文字标题认人比看图慢，所以把这个头像跟着通知一起带过去。
            // 取值放在这里而不是协程里：StatusBarNotification 处理完就被系统回收了。
            val avatar = notificationAvatar(this, extras)
            pushCommunicate(title, text, source.key, avatar)
        }

        // 通知代办：只入队，不在这里调模型。
        // 攒着定期批量提取，比每条都发一次请求省得多，也不会和通知摘要抢同一次调用。
        if (AidePrefs.enabled(this)) {
            AideDigest.onNotice(this, title, text)
        }

        // 人物档案：扫一遍已落库的聊天记录，把新消息累积到各对象的待总结队列。
        // 大多数时候 pending 还没到阈值，这里只是几次 JSON 读写，很便宜。
        // 独立于自动起草开关：关掉自动起草不该连带停掉画像积累。
        if (com.dogheadstrategist.chat.feature.IntelligencePrefs.enabled(this)) {
            scope.launch {
                runCatching { com.dogheadstrategist.chat.feature.Intelligence.observe(this@SentryNotificationService) }
                    .onFailure { MoodLog.w("INTEL_OBSERVE_FAILED " + it.javaClass.simpleName) }
            }
        }

        // ===== 以下全部属于自动起草，与通知摘要无关 =====
        if (!SentryPrefs.enabled(this)) return

        // 白名单：名单外的联系人在这里就返回，既不进队列也不触发模型请求。
        if (SentryPrefs.whitelistEnabled(this)) {
            val whitelist = SentryPrefs.whitelist(this)
            if (whitelist.isNotEmpty() && !SentryPrefs.allows(whitelist, "", title)) {
                MoodLog.i("SENTRY_NOT_IN_WHITELIST nick=" + title.take(12))
                return
            }
        }

        SentryQueue.push(this, title, text)
        MoodLog.i("SENTRY_NOTICE nick=${title.take(16)} len=${text.length}")
        scope.launch {
            runCatching { generateLocally(title) }
                .onFailure { MoodLog.w("SENTRY_LOCAL_FAILED " + it.javaClass.simpleName) }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    /**
     * 通知摘要：调模型把消息压成一句话，替换微信那条通知。
     *
     * 拿不到模型配置、或者调用失败时退回原文——岛上有字总比没反应好。
     * 触发字数放在调模型之前判，短句直接不打扰，省一次请求。
     */
    /**
     * 从微信那条通知里取发信人头像。
     *
     * 坑在这里：Android 7 起 `EXTRA_LARGE_ICON` 里存的是 `Icon`，不是 `Bitmap`。
     * 直接用 `getParcelable<Bitmap>` 取会抛 ClassCastException，被 runCatching 一吞
     * 就变成 null，界面上永远退回微信应用图标——看起来像「没做头像功能」。
     */
    private fun notificationAvatar(context: Context, extras: android.os.Bundle): Bitmap? {
        @Suppress("DEPRECATION")
        val icon = runCatching { extras.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON) }.getOrNull()
            ?: runCatching { extras.getParcelable<Icon>(Notification.EXTRA_LARGE_ICON_BIG) }.getOrNull()
        if (icon != null) {
            return runCatching { icon.loadDrawable(context)?.let { drawableToBitmap(it) } }.getOrNull()
        }
        // 极少数应用仍直接塞 Bitmap，兜一下
        @Suppress("DEPRECATION")
        return runCatching { extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON) }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) drawable.bitmap?.let { return it }
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * 去掉末尾字体不支持的 emoji。
     *
     * AI 偶尔会挑系统字体里没有的新版 emoji（🫠 🪿 🩷 这类），
     * 通知上只能画成黑框加问号，比不带 emoji 还难看。
     * 系统连最普通的 emoji 都画不出时不做处理，免得把能显示的也一起删掉。
     */
    private fun trimUnsupportedEmoji(text: String): String {
        if (text.isEmpty()) return text
        val paint = emojiPaint
        // hasGlyph 只查字体链里有没有该码点的字形，字体带个占位字形就会返回 true，
        // 于是「判断支持、实际画成黑框」。所以范围硬约束优先：U+1F900 以上的新版
        // emoji（🫠 🪿 🩷 这类）一律不信，只认 Unicode 9 之前的基础 emoji。
        val probe = runCatching { paint.hasGlyph("\uD83D\uDE00") }.getOrDefault(false)
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val count = Character.charCount(cp)
            if (isEmojiLike(cp)) {
                // 连续的 emoji 序列当作一个整体：含 ZWJ 连接符、变体选择符、肤色修饰
                var j = i + count
                while (j < text.length) {
                    val next = text.codePointAt(j)
                    if (!isEmojiLike(next)) break
                    j += Character.charCount(next)
                }
                val seq = text.substring(i, j)
                var inSafeRange = true
                var k = 0
                while (k < seq.length) {
                    val c = seq.codePointAt(k)
                    if (c >= 0x1F900) { inSafeRange = false; break }
                    k += Character.charCount(c)
                }
                // 探测不可用时只靠范围；可用时再叠加一次字形判断
                if (inSafeRange && (!probe || paint.hasGlyph(seq))) sb.append(seq)
                i = j
            } else {
                sb.appendCodePoint(cp)
                i += count
            }
        }
        return sb.toString().trim()
    }

    private fun isEmojiLike(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF ||
            cp in 0x2190..0x21FF || cp in 0x2B00..0x2BFF ||
            cp == 0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF

    private fun pushCommunicate(sender: String, text: String, key: String, avatar: Bitmap?) {
        // 群聊还是单聊，只以 talker 为准：微信进程同步过来的近期会话里有 nick→talker，
        // 群聊的 talker 以 @chatroom 结尾。
        // 查不到时一律按单聊处理，不再按正文冒号前缀猜——单聊里「http://」「时间: ...」
        // 的冒号会被拆成假前缀，猜错的代价比判不出群聊更大。
        val talker = SentryStore.findTalker(this, sender)
        val groupHint = if (talker.isNotBlank()) talker.endsWith("@chatroom") else null

        val (speaker, body) = CommunicateProtocol.splitSpeaker(text)
        // 只有确认是群聊才剥前缀。拿不到 talker 时也按单聊处理：
        // 单聊里「http://」「时间: ...」会被拆成假前缀，把正文削掉一截。
        val effectiveSpeaker = if (groupHint == true) speaker else ""
        val effectiveBody = if (groupHint == true) body else text
        // 本地也要有这个判断：标题上加「群·」标记用得上
        val isGroup = groupHint ?: false

        // 字数门槛按真正的正文算，前缀不该把短句抬过线
        val minChars = CommunicatePrefs.minTriggerChars(this)
        if (minChars > 0 && effectiveBody.length < minChars) return
        val maxChars = CommunicatePrefs.summaryMaxChars(this)
        // 群聊标题要腾出「群·」两个字的位，AI 生成和回退兜底都按这个预算来，
        // 否则标题会被系统从尾巴截断
        val maxLeftRaw = CommunicatePrefs.senderMaxChars(this)
        val maxLeft = if (isGroup) (maxLeftRaw - 2).coerceAtLeast(2) else maxLeftRaw
        // 每个会话一个通知 id：不同人的消息各自占一条，不会互相覆盖
        val notifyId = sender.hashCode()
        val timeoutMs = CommunicatePrefs.aiTimeoutSec(this) * 1000L
        val durationMs = CommunicatePrefs.displaySec(this) * 1000L
        scope.launch {
            val line = runCatching {
                val settings = replySettings()
                if (settings == null || !settings.isConfigured) return@runCatching null
                // AI 回来才弹通知。等太久就直接用原文，不让通知一直缺席
                withTimeoutOrNull(timeoutMs) {
                    ReplyHttpClient().summarize(settings, sender, text, maxChars, maxLeft, groupHint)
                }
            }.getOrNull()
            // 模型没给出结果时退回原文：群聊用说话人，单聊用通知标题
            val baseLeft = trimUnsupportedEmoji(
                line?.left ?: (effectiveSpeaker.ifBlank { sender }).take(maxLeft)
            )
            // 同一屏上可能同时挂着好几条，光看头像分不出哪个是群、哪个是单聊。
            // 群聊在标题前面标一个「群·」，扫一眼字就知道，不用先认头像。
            val left = if (isGroup) "群·$baseLeft" else baseLeft
            val right = trimUnsupportedEmoji(line?.right ?: effectiveBody)
            // 等 AI 这几秒里，用户可能已经点开微信读完了。通知没了就别再打扰。
            // 拿不到当前通知列表时（服务刚连上等）按「还在」处理，宁可多发也不漏
            val active = runCatching { activeNotifications }.getOrNull()
            val stillThere = active == null || active.any { it.key == key }
            if (!stillThere) {
                MoodLog.i("COMMUNICATE_SKIP_READ nick=" + sender.take(12))
                return@launch
            }
            // 微信那条不动，我们额外发一条。它到点自己消失，不碰别人的通知
            if (!PlainNotify.show(
                    this@SentryNotificationService, left, right, notifyId, durationMs, sender, avatar,
                )
            ) {
                MoodLog.w("COMMUNICATE_PLAIN_FAILED nick=" + sender.take(12))
            }
        }
    }

    /** 用同步过来的记录副本就地生成；拿不到副本就安静退出，交给微信进程走原路径。 */
    private suspend fun generateLocally(nick: String) {
        val session = findSession(nick) ?: run {
            MoodLog.i("SENTRY_LOCAL_NO_RECORD nick=" + nick.take(12))
            return
        }
        val last = session.messages.lastOrNull() ?: return
        if (last.send > 0) {
            // 副本最后一条是我发的。但这可能是因为副本还没同步到对方刚发来的消息——
            // 记录是定期抄过来的，通知往往先到。只有「刚发不久」才算真的已经回过，
            // 否则会把每一条新消息都误判成已回复，预生成永远不触发。
            val age = System.currentTimeMillis() - last.ts
            if (age < STALE_RECORD_MS) {
                MoodLog.i("SENTRY_LOCAL_ALREADY_REPLIED talker=" + session.talker.take(12) + " age=" + age / 1000 + "s")
                return
            }
            MoodLog.i("SENTRY_LOCAL_STALE_RECORD talker=" + session.talker.take(12) +
                " age=" + age / 1000 + "s，副本可能滞后，继续生成")
        }
        val settings = replySettings() ?: return
        if (!settings.isConfigured) return

        val messages = session.messages.map { ReplyMessage(0, it.speaker, it.ts, it.text.take(400)) }
        val page = ReplyContext(session.talker, messages)
        val suggestion = ReplyHttpClient().generate(settings, page, "", "", "", kind = AiStats.Kind.SENTRY)
        val picked = suggestion.parts.take(SentryPrefs.count(this))
        if (picked.isEmpty()) return
        // 带上触发这条建议的消息时间，用来挡住「旧消息后生成完覆盖新消息」的乱序
        val anchor = session.messages.lastOrNull()
        mergePool(session.talker, picked, anchor?.ts ?: System.currentTimeMillis(), anchor?.mid ?: 0L)
        MoodLog.i("SENTRY_LOCAL_READY talker=" + session.talker.take(12) + " count=" + picked.size)
    }

    private data class Record(val speaker: String, val send: Int, val text: String, val ts: Long,
                              val mid: Long = 0)
    private data class Session(val talker: String, val nick: String, val messages: List<Record>)

    /**
     * 通知标题里的昵称常带「[表情]」这类标记，而同步过来的记录里存的是干净名字，
     * 直接字符串比较会永远匹配失败，导致后台预生成形同虚设。先归一化再比。
     *
     * 具体规则（含真实 emoji 的剥离）统一放在 ReplyDatabaseHistory.normalizeNick，
     * 两边共用一份，避免再出现「修了一处漏了另一处」。
     */
    private fun normalizeNick(raw: String): String =
        com.dogheadstrategist.chat.hook.ReplyDatabaseHistory.normalizeNick(raw)

    private fun nickMatches(notice: String, stored: String): Boolean {
        if (notice == stored) return true
        val a = normalizeNick(notice)
        val b = normalizeNick(stored)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // 一边是备注名、一边是微信昵称时，包含关系也认
        return a.contains(b) || b.contains(a)
    }

    private fun findSession(nick: String): Session? {
        val raw = SentryStore.records(this)
        if (raw.isBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            var found: Session? = null
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (!nickMatches(item.optString("nick"), nick)) continue
                val list = item.optJSONArray("messages") ?: continue
                val records = (0 until list.length()).mapNotNull { position ->
                    val message = list.optJSONObject(position) ?: return@mapNotNull null
                    // 缺省按「对方发的」处理：字段缺失时判成我方发送会直接跳过生成
                    Record(message.optString("speaker"), message.optInt("send", 0),
                        message.optString("text"),
                        com.dogheadstrategist.chat.hook.MessageMetadata.toMillis(message.optLong("ts", 0L)),
                        message.optLong("mid", 0L))
                }
                if (records.size >= 2) found = Session(item.optString("talker"), nick, records)
                break
            }
            found
        }.getOrNull()
    }

    private fun replySettings(): ReplySettings? = ReplySettings.load(this)

    /**
     * 把新建议并入建议池，微信进程显示卡片时来取。
     *
     * 同一个会话永远只保留「最后一次触发」的结果：多条消息各自发起生成时，
     * 完成顺序不一定是消息顺序，旧消息的慢请求若直接覆盖，卡片就会显示已经过时的建议。
     */
    private fun mergePool(talker: String, suggestions: List<String>, triggerTs: Long, anchorId: Long) {
        runCatching {
            val existing = runCatching { JSONArray(SentryStore.suggestions(this).ifBlank { "[]" }) }
                .getOrDefault(JSONArray())
            val merged = JSONArray()
            for (index in 0 until existing.length()) {
                val item = existing.optJSONObject(index) ?: continue
                if (item.optString("talker") == talker) {
                    // 已有的是更晚的消息触发的，别用这次的旧结果盖掉
                    if (item.optLong("triggerTs", 0L) > triggerTs) {
                        MoodLog.i("SENTRY_STALE_SKIP talker=" + talker.take(12))
                        return@runCatching
                    }
                } else {
                    merged.put(item)
                }
            }
            merged.put(JSONObject().apply {
                put("talker", talker)
                put("at", System.currentTimeMillis())
                put("triggerTs", triggerTs)
                // 锚点：这条建议围绕的那条消息。微信进程按它校验界面是否还在原处。
                put("mid", anchorId)
                put("suggestions", JSONArray(suggestions))
            })
            SentryStore.putSuggestions(this, merged.toString())
        }
    }

    private fun isDuplicate(title: String, text: String): Boolean {
        val key = "$title\u0000$text"
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > DEDUPE_WINDOW_MS }
        val last = recent[key]
        recent[key] = now
        return last != null && now - last < DEDUPE_WINDOW_MS
    }

    private companion object {
        const val PACKAGE = "com.tencent.mm"
        const val DEDUPE_WINDOW_MS = 45_000L
        /** 副本里「我发的最后一条」超过这个年龄，就认为副本还没同步到新消息。 */
        const val STALE_RECORD_MS = 120_000L
        /** 查字形覆盖用，跨通知复用；构造一次即可，无需每条通知新建。 */
        // hasGlyph() 依赖 textSize 与 typeface：裸 Paint() 的 textSize 是 0，判断会失真
        private val emojiPaint by lazy {
            android.graphics.Paint().apply {
                textSize = 32f
                typeface = android.graphics.Typeface.DEFAULT
            }
        }
    }
}
