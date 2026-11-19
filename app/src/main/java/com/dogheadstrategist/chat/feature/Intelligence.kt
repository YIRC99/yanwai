package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.CacheKey
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.SettingsTransport
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import com.dogheadstrategist.chat.reply.ReplySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 人物档案：为每个聊天对象累积画像。
 *
 * 和角色卡的区别在于「谁在写、什么时候写」：
 *
 * - 角色卡是用户在微信里点按钮，读**当前打开的聊天**，模型写一份背景，用户手动保存。
 *   一次性的，不打开窗口就没有。
 * - 这里是从自动起草已经落库的聊天记录副本里扫，**不需要打开任何窗口**，
 *   攒够一批就自动更新一次，而且每次都带着原画像做增量。
 *
 * 画像的用途是让回复和话题生成知道「对面是谁」——说话风格、关系远近、在意什么。
 * 所以这里刻意不写好恶评价，只写观察得到的事实。
 */
object Intelligence : StrategistModule {
    override val feature = StrategistFeature.INTELLIGENCE

    /**
     * 单次扫描最多触发几次总结。
     *
     * 一次性把几十个会话全总结一遍会连着发几十个请求，既慢又容易把模型打到限流。
     * 每次最多处理两个，剩下的下一轮自然会轮到。
     */
    private const val MAX_SUMMARIZE_PER_PASS = 2

    /** 每个会话每次最多取多少条消息当样本。再多只是稀释重点，而且 records 本身也不长。 */
    private const val SAMPLES_PER_SESSION = 20

    /**
     * 同一个对象一次扫描最多连着消化几批。
     *
     * 攒了几百条的对象要是一轮只消化一批，会连着好几轮通知才追上，
     * 中间用户看到的画像一直是旧的。给个上限是因为批数太多会连着发请求。
     */
    private const val MAX_BATCHES_PER_TALKER = 3

    /**
     * 最近一次后台扫描拿到的角色卡摘要。
     *
     * 界面要显示「绑了角色卡但还没开始分析的人」，而列举条目是在主线程做的——
     * 那里不能走 Binder。所以后台扫描时顺手记一份，界面只读这个内存值。
     */
    @Volatile private var cachedTags: Set<String> = emptySet()

    /**
     * talker 摘要 → 用户设的身份名（如「老婆」），同样只为避开 UI 线程的 Binder。
     *
     * 和 [cachedTags] 一次取回：两者都只随「用户在微信里改了角色卡」变化，
     * 分开取只会让扫描多跑一趟跨进程。
     */
    @Volatile private var cachedLabels: Map<String, String> = emptyMap()

    /**
     * 扫一遍本地聊天记录副本，累积到各对象的待总结队列，并在攒够时触发总结。
     *
     * 会被通知服务在每次收到微信消息后调用，所以这里必须足够便宜：
     * 没开开关直接返回；开着的会话通常 pending 还没到阈值，也就只是几次 JSON 读写。
     */
    suspend fun observe(context: Context) {
        if (!IntelligencePrefs.enabled(context)) return
        // 谁绑了角色卡。取不到就这一轮什么都不攒——宁可空转，也不要退回「谁都攒」的旧行为。
        val boundTags = withContext(Dispatchers.IO) { readBoundTalkerTags(context) }
        // 取失败时保留上次的记忆，别让界面上的列表也跟着一起消失
        if (boundTags == null) return
        cachedTags = boundTags
        // 名字取不到不影响攒素材，失败就沿用上一份
        withContext(Dispatchers.IO) { readDisplayLabels(context) }?.let { cachedLabels = it }
        if (boundTags.isEmpty()) return
        val sessions = runCatching { parseSessions(context, boundTags) }.getOrElse {
            MoodLog.w("INTEL_PARSE_FAILED " + it.javaClass.simpleName)
            return
        }
        if (sessions.isEmpty()) return

        for (session in sessions) {
            IntelligenceStore.appendPending(
                context, session.talker, session.nick, session.samples,
            )
        }

        val threshold = IntelligencePrefs.minSamples(context)
        val intervalMs = IntelligencePrefs.intervalHours(context) * 3_600_000L
        val now = System.currentTimeMillis()
        var summarized = 0
        for (session in sessions) {
            if (summarized >= MAX_SUMMARIZE_PER_PASS) break
            // 一次只消化一批；攒得特别多就连续多跑几批，否则要等好几轮通知才能追上
            var batches = 0
            while (batches < MAX_BATCHES_PER_TALKER &&
                shouldSummarize(context, session.talker, threshold, intervalMs, now)
            ) {
                if (!summarizeOnce(context, session.talker, session.nick)) break
                batches++
            }
            if (batches > 0) summarized++
        }
    }

    /**
     * 条数和时间谁先满足走谁。
     *
     * 时间这一条必须挂在「有内容」上：一张卡如果一条新消息都没有，隔多久都不该触发，
     * 否则只会拿空样本把同一份画像反复重写一遍，纯浪费 token。
     *
     * 从没总结过的卡（updatedAt = 0）只要有 pending 就算到期——pending 本身已经证明
     * 有内容可看，再空等一个周期没有意义。
     */
    private fun shouldSummarize(
        context: Context,
        talker: String,
        threshold: Int,
        intervalMs: Long,
        now: Long,
    ): Boolean {
        val pending = IntelligenceStore.pendingCount(context, talker)
        if (pending <= 0) return false
        if (pending >= threshold) return true
        if (intervalMs <= 0L) return false
        val last = IntelligenceStore.updatedAt(context, talker)
        return last <= 0L || now - last >= intervalMs
    }

    /**
     * 对单个对象做一次增量总结。
     *
     * 返回 true 表示真的跑了一次模型调用（不管成功失败），false 表示条件不满足没跑。
     *
     * 失败时**不清空 pending**：模型偶尔会返回不合规的 JSON，把样本留着，
     * 下一轮还能再试一次，而不是让这段时间的观察白丢。
     */
    suspend fun summarizeOnce(context: Context, talker: String, nick: String): Boolean {
        val settings = runCatching { ReplySettings.load(context) }.getOrNull()
        if (settings == null || !settings.isConfigured) return false

        // 只读不取：模型失败时 pending 必须原封不动留在原地，
        // 否则那批消息既没进画像、指纹又被记进 seen，就再也总结不到了。
        val samples = IntelligenceStore.peekPending(context, talker)
        if (samples.isEmpty()) return false

        // 还没有画像时，拿角色卡里用户自己写的背景当初稿：模型在这个基础上补细节，
        // 而不是从零另写一份，跟用户设的定位打架。
        val oldProfile = IntelligenceStore.profile(context, talker).ifBlank { seed(context, talker) }
        val displayName = nick.ifBlank { talker }
        val limit = IntelligencePrefs.maxChars(context)

        val fresh = runCatching {
            ReplyHttpClient().updateContactProfile(settings, oldProfile, samples, displayName, limit)
        }.onFailure {
            MoodLog.w("INTEL_SUMMARIZE_FAILED " + it.javaClass.simpleName + " " + (it.message ?: ""))
        }.getOrNull()

        // 拿不到结果就保留 pending，下一轮再试
        if (fresh.isNullOrBlank()) return true

        val trimmed = if (fresh.length > limit) fresh.take(limit) else fresh
        IntelligenceStore.setProfile(context, talker, trimmed, System.currentTimeMillis())
        // 只清掉真正喂给模型的那一批，pending 里若还有更新的消息要留着
        IntelligenceStore.clearPending(context, talker, samples)
        IntelligenceStore.recordSamples(context, talker, samples.size)
        return true
    }

    /** 读某个对象的画像；没有返回空串。 */
    fun profile(context: Context, talker: String): String =
        IntelligenceStore.profile(context, talker)

    /**
     * 所有值得展示的对象：已有画像的、正在攒的，以及已经绑了角色卡但还没开始分析的人。
     *
     * 最后一个来源让「绑完角色卡回来就能看到这个人」成立。用的是后台扫描缓存的摘要，
     * 这里跑在 UI 线程，不能碰 Binder。缓存还没建立时只显示已有的，下一轮扫描会自动补上。
     */
    fun entries(context: Context): List<IntelligenceStore.Entry> {
        val known = IntelligenceStore.entries(context)
        if (cachedTags.isEmpty()) return known.map { it.withLabel(cachedLabels) }
        val covered = known.mapTo(HashSet()) { CacheKey.digest("reply-talker-v1", it.talker) }
        val extra = ArrayList<IntelligenceStore.Entry>()
        runCatching { JSONArray(SentryStore.sessions(context)) }.getOrNull()?.let { array ->
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val talker = obj.optString("t").trim()
                if (talker.isBlank()) continue
                val tag = CacheKey.digest("reply-talker-v1", talker)
                if (tag !in cachedTags || !covered.add(tag)) continue
                extra.add(IntelligenceStore.Entry(talker, obj.optString("n").ifBlank { talker }, "", 0, 0, 0L))
            }
        }
        return (known + extra).map { it.withLabel(cachedLabels) }.sortedByDescending { it.updatedAt }
    }

    /**
     * 重新取一遍「谁是谁」的名字。
     *
     * [entries] 跑在 UI 线程读不到 Binder，所以名字是后台缓存。用户可能刚在微信里
     * 改完身份就切过来看，进页面时主动刷一次，比等下一次微信通知更符合预期。
     */
    suspend fun refreshLabels(context: Context) {
        withContext(Dispatchers.IO) { readDisplayLabels(context) }?.let { cachedLabels = it }
    }

    fun clear(context: Context, talker: String) = IntelligenceStore.clear(context, talker)

    fun clearAll(context: Context) = IntelligenceStore.clearAll(context)

    // ===================== records 解析 =====================

    private class Session(
        val talker: String,
        val nick: String,
        val samples: List<IntelMessage>,
    )

    /**
     * 把 records 摊平成会话列表。
     *
     * records 结构：`[{"talker":"xxx","nick":"群名","messages":[{"speaker":"","text":""}]}]`。
     * 这里只取「后进的、有正文的」消息——records 每次同步都是整段覆盖，
     * 前面的内容上一轮已经攒过了，重复取只会让 pending 白涨。
     */
    private fun parseSessions(context: Context, boundTags: Set<String>): List<Session> {
        val raw = SentryStore.records(context)
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Session>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val talker = obj.optString("talker").trim()
            if (talker.isBlank()) continue
            // 只攒已经标了角色卡的联系人。判断凭 talker 摘要，不看昵称——
            // 改备注不影响，群聊因为绑不了角色卡天然被排除。
            if (CacheKey.digest("reply-talker-v1", talker) !in boundTags) continue
            val nick = obj.optString("nick").ifBlank { talker }
            val messages = obj.optJSONArray("messages") ?: continue
            val samples = ArrayList<IntelMessage>()
            // 从后往前取，凑够 SAMPLES_PER_SESSION 就停：近的消息更能代表当前状态
            for (msgIndex in messages.length() - 1 downTo 0) {
                if (samples.size >= SAMPLES_PER_SESSION) break
                val msg = messages.optJSONObject(msgIndex) ?: continue
                val text = msg.optString("text").trim()
                if (text.isBlank()) continue
                samples.add(
                    IntelMessage(
                        speaker = msg.optString("speaker").ifBlank { nick },
                        text = text,
                        // send=true 是我发的，画像要的是对方，协议里会标注出来
                        send = msg.optBoolean("send"),
                        mid = msg.optString("mid"),
                    )
                )
            }
            if (samples.isEmpty()) continue
            // 取的时候是倒序的，翻回来保持时间顺序给模型看
            samples.reverse()
            out.add(Session(talker, nick, samples))
        }
        return out
    }

    /**
     * 角色卡里用户自己写的背景，作为画像的初稿。
     *
     * 只读不回写：角色卡有乐观锁，后台自动更新会让用户下次在微信里保存失败。
     * 取不到返回空串，模型会从样本从头写——这是兜底，不是常态。
     *
     * 注意必须在后台线程调用：这条路径会走 Binder，主线程上会直接抛异常。
     */
    fun seed(context: Context, talker: String): String = runCatching {
        SettingsTransport.call(context, "reply_background_for_talker",
            CacheKey.digest("reply-talker-v1", talker), null)?.getString("text")
    }.getOrNull().orEmpty()

    /**
     * 绑了角色卡的联系人的 talker 摘要。
     *
     * 摘要只跟 talker 有关、不含登录账号，所以 app 进程自己算得出来。
     * 取不到就当空集：宁可这一轮不攒，也不要退回「谁都攒」。
     */
    private fun IntelligenceStore.Entry.withLabel(labels: Map<String, String>): IntelligenceStore.Entry =
        if (labels.isEmpty()) this
        else copy(label = labels[CacheKey.digest("reply-talker-v1", talker)].orEmpty())

    private fun readDisplayLabels(context: Context): Map<String, String>? = runCatching {
        val bundle = SettingsTransport.call(context, "reply_identity_labels", null, null) ?: return@runCatching emptyMap()
        val tags = bundle.getStringArrayList("tags").orEmpty()
        val values = bundle.getStringArrayList("labels").orEmpty()
        tags.zip(values).toMap()
    }.getOrNull()

    private fun readBoundTalkerTags(context: Context): Set<String>? = runCatching {
        SettingsTransport.call(context, "reply_bound_talker_tags", null, null)
            ?.getStringArrayList("tags")?.toHashSet() ?: emptySet()
    }.getOrNull()
}
