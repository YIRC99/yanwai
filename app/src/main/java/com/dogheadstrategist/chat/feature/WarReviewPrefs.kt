package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.ModulePrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 标兵狗：从我自己发出的消息里学我的说话方式。
 *
 * 和其他设置一样，值落在 app 进程的 prefs，经 SettingsProvider 快照同步给微信进程。
 * 画像本身也走这条路——生成回复的是微信进程，它必须能读到这份画像。
 *
 * 游标用 msgId 而不是时间戳：微信库里的 createTime 有秒也有毫秒，
 * 直接比较容易错位；msgId 单调递增，跨会话也唯一。
 */
object WarReviewPrefs {
    const val KEY_ENABLED = "war_review_enabled"
    const val KEY_PROFILE = "war_review_profile"
    const val KEY_UPDATED_AT = "war_review_updated_at"
    const val KEY_CURSOR = "war_review_cursor"
    const val KEY_SAMPLE_COUNT = "war_review_sample_count"
    const val KEY_INTERVAL_HOURS = "war_review_interval_hours"
    const val KEY_MIN_SAMPLES = "war_review_min_samples"
    const val KEY_SUGGESTIONS = "war_review_suggestions"
    const val KEY_RECENT = "war_review_recent"

    /** 已采集、还没被整理进画像的样本。整理成功后清空。 */
    const val KEY_PENDING = "war_review_pending"

    /** 刚开启、还没把游标推到当前位置。微信进程看到它就跳过全部历史，从当下开始收。 */
    const val KEY_ARMED = "war_review_armed"

    /** 「立即整理」：app 进程置位，微信进程下一轮读到就绕过间隔和开关跑一次。 */
    const val KEY_FORCE = "war_review_force"

    /** 已经收过的消息 id，用来挡掉聊天列表反复绑定带来的重复推送。 */
    const val KEY_SEEN_IDS = "war_review_seen_ids"

    /** 累计排除了多少条 AI 帮我写的话。面板上要能看见这个数，否则无从判断过滤是否在工作。 */
    const val KEY_SKIPPED = "war_review_skipped"

    /**
     * 用户词库：从样本里挑出的、他自己反复在用的词。
     *
     * 和画像是两回事——画像是一段描述「怎么说话」的文字，词库是具体几个词。
     * 画像讲风格，词库补细节：光有画像，模型知道「你爱用短句」，
     * 但不知道你具体爱说「咋」还是「怎么」。
     */
    const val KEY_VOCAB = "war_review_vocab"

    const val DEFAULT_ENABLED = false

    /** 画像长度上限：注入 system prompt 时不能喧宾夺主。 */
    const val MAX_PROFILE_LENGTH = 800

    /** 攒够多少条我方消息才值得整理一次（下限）。 */
    const val MIN_SAMPLES_FLOOR = 40
    const val MIN_SAMPLES_CEIL = 400
    const val DEFAULT_MIN_SAMPLES = 80

    /** 一次最多喂给模型多少条，太多会撑爆上下文。 */
    const val MAX_SAMPLES = 240

    /** 词库最多留多少个词。再多就是噪声，也会把 prompt 撑长。 */
    const val MAX_VOCAB = 40

    /** 单个词条长度上限，避免模型吐出一整句话。 */
    const val MAX_VOCAB_WORD = 12

    /** 类别名长度上限，「表示同意」这种就够，防止模型写成一整句解释。 */
    const val MAX_VOCAB_CAT = 8

    /** 每条最多记几个「他不用的说法」。 */
    const val MAX_VOCAB_AVOID = 3

    /** 整理间隔（小时）的可选范围。 */
    const val MIN_INTERVAL_HOURS = 6
    const val MAX_INTERVAL_HOURS = 72
    const val DEFAULT_INTERVAL_HOURS = 24

    /** 单条样本截断长度。 */
    const val MAX_SAMPLE_LENGTH = 120

    /** 指纹库保留条数。 */
    private const val MAX_SUGGESTIONS = 500

    /** 去重集合的上限。只挡最近的重复，太久远的 id 被挤掉也无所谓。 */
    private const val MAX_SEEN_IDS = 2000

    /** 低于这个长度的建议不参与排除：短句撞车概率太高，宁可漏排。 */
    const val FINGERPRINT_MIN_LENGTH = 8

    /** 最近样本展示条数。 */
    const val RECENT_SHOW = 8

    // ---- 设置侧（app 进程）----
    fun enabled(context: Context): Boolean = readBoolean(context, KEY_ENABLED, DEFAULT_ENABLED)
    fun profile(context: Context): String = readString(context, KEY_PROFILE, "")
    /** 词库原始 JSON，给快照用。调用方自己解析。 */
    fun vocabJson(context: Context): String = readString(context, KEY_VOCAB, "[]")
    fun updatedAt(context: Context): Long = readLong(context, KEY_UPDATED_AT, 0L)
    fun cursor(context: Context): Long = readLong(context, KEY_CURSOR, 0L)
    fun intervalHours(context: Context): Int =
        readInt(context, KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
            .coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)
    fun minSamples(context: Context): Int =
        readInt(context, KEY_MIN_SAMPLES, DEFAULT_MIN_SAMPLES)
            .coerceIn(MIN_SAMPLES_FLOOR, MIN_SAMPLES_CEIL)

    fun setEnabled(context: Context, value: Boolean) {
        // 已有样本就不重置：Activity 重建（进程被系统回收后恢复）时，系统会恢复
        // SwitchMaterial 的 isChecked 并触发 onCheckedChanged，而此时 MainActivity
        // 里的 syncingSwitches 早已复位成 false，那道拦截挡不住。只有真正从空开始时才清。
        val keepSamples = value && runCatching { stats(context).third > 0 }.getOrDefault(false)
        save(context) {
            putBoolean(KEY_ENABLED, value)
            if (value) {
                // 每次开启都从当下开始。用户说的是「抓取从开启后开始」，
                // 那就不能因为上次残留的 cursor 继续往前翻历史。
                putBoolean(KEY_ARMED, true)
                if (!keepSamples) {
                    putString(KEY_PENDING, "[]")
                    putString(KEY_RECENT, "[]")
                    putInt(KEY_SAMPLE_COUNT, 0)
                    putInt(KEY_SKIPPED, 0)
                }
            }
        }
    }

    fun forceRequested(context: Context): Boolean = readBoolean(context, KEY_FORCE, false)
    fun armed(context: Context): Boolean = readBoolean(context, KEY_ARMED, false)
    fun setForceRequested(context: Context, value: Boolean) = save(context) { putBoolean(KEY_FORCE, value) }
    fun setIntervalHours(context: Context, value: Int) =
        save(context) { putInt(KEY_INTERVAL_HOURS, value.coerceIn(MIN_INTERVAL_HOURS, MAX_INTERVAL_HOURS)) }
    fun setMinSamples(context: Context, value: Int) =
        save(context) { putInt(KEY_MIN_SAMPLES, value.coerceIn(MIN_SAMPLES_FLOOR, MIN_SAMPLES_CEIL)) }

    fun setProfile(context: Context, text: String) = save(context) {
        putString(KEY_PROFILE, text.trim().take(MAX_PROFILE_LENGTH))
        putLong(KEY_UPDATED_AT, System.currentTimeMillis())
    }

    // ---- 用户词库 ----

    /**
     * 一条用词偏好。
     *
     * 词库存的不是「他爱说的一批词」，而是「同一类意思他选哪种说法」：
     * 表示同意时他说「没问题」，不说「好的」「可以」「ok」。
     * 带上类别，模型才知道什么场合该用哪个；光给词列表它只能猜。
     *
     * [cat] 为空的是旧版本留下的裸词，没有类别信息，按词本身去重。
     */
    data class VocabWord(
        /** 这类意思的名字，由模型从样本里自己归纳，2~4 字。空串表示旧版本留下的裸词。 */
        val cat: String,
        /** 他在这类意思里习惯用的说法。 */
        val word: String,
        /** 这类意思里常见、但他不用的说法。用来告诉模型「别这么写」。 */
        val avoid: List<String> = emptyList(),
        val at: Long,
    ) {
        /**
         * 渲染成注入 prompt 的那一行。
         *
         * 面板预览也走这里：面板上看到什么，模型就收到什么，
         * 两处各写一份格式迟早会走样。
         */
        fun render(): String = if (cat.isBlank()) {
            "「" + word + "」"
        } else buildString {
            append(cat)
            append(" → 「")
            append(word)
            append("」")
            if (avoid.isNotEmpty()) {
                append("，这一类里别用 ")
                append(avoid.joinToString("、"))
            }
        }
    }

    /** 词库全量，最近用过的排前面。 */
    fun vocab(context: Context): List<VocabWord> = runCatching {
        parseVocab(WarReviewStore.read(context).getString(KEY_VOCAB, "[]"))
            .sortedByDescending { it.at }
    }.getOrDefault(emptyList())

    /**
     * 把这轮整理的结果并进词库。
     *
     * AI 只报「这次新发现或改口的表达」，不重述整个词库——否则每次整理都要把
     * 已有内容全打印一遍，输出量随词库膨胀。去重按类别做：同一类意思只留最新的那条，
     * 比如他以前说「好的」，现在改说「没问题」，旧的就被顶掉，而不是两条并存。
     *
     * 超过 [MAX_VOCAB] 条时按最后出现时间淘汰，最久没用的先走。
     */
    fun addVocab(context: Context, fresh: List<VocabWord>) {
        val clean = fresh.mapNotNull { item ->
            val word = item.word.trim().take(MAX_VOCAB_WORD)
            if (word.isEmpty()) null
            else item.copy(
                cat = item.cat.trim().take(MAX_VOCAB_CAT),
                word = word,
                avoid = item.avoid.map { it.trim().take(MAX_VOCAB_WORD) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .take(MAX_VOCAB_AVOID),
            )
        }
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        val merged = LinkedHashMap<String, VocabWord>()
        vocab(context).forEach { merged[keyOf(it)] = it }
        clean.forEach { merged[keyOf(it)] = it.copy(at = now) }
        val kept = merged.values.sortedByDescending { it.at }.take(MAX_VOCAB)
        save(context) {
            val array = JSONArray()
            kept.forEach { array.put(it.toJson(it.at)) }
            putString(KEY_VOCAB, array.toString())
        }
    }

    private fun VocabWord.toJson(stamp: Long): JSONObject {
        val avoidArray = JSONArray()
        avoid.forEach { avoidArray.put(it) }
        return JSONObject().put("c", cat).put("w", word).put("a", avoidArray).put("t", stamp)
    }

    /** 同一类意思只能有一条：有类别按类别去重，旧版本没类别的裸词按词本身去重。 */
    private fun keyOf(item: VocabWord): String =
        if (item.cat.isNotBlank()) "c:" + item.cat else "w:" + item.word

    private fun parseVocab(raw: String): List<VocabWord> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val word = item.optString("w").trim()
            if (word.isEmpty()) null
            else {
                val avoidArray = item.optJSONArray("a")
                val avoid = if (avoidArray == null) emptyList() else
                    (0 until avoidArray.length()).mapNotNull {
                        avoidArray.optString(it).trim().takeIf { w -> w.isNotEmpty() }
                    }
                VocabWord(item.optString("c").trim(), word, avoid, item.optLong("t"))
            }
        }
    }.getOrDefault(emptyList())


    /** 清空已采集的样本，从头攒起。画像和指纹库都保留。 */
    fun clearSamples(context: Context) = save(context) {
        putString(KEY_PENDING, "[]")
        putString(KEY_RECENT, "[]")
        putString(KEY_SEEN_IDS, "[]")
        putInt(KEY_SAMPLE_COUNT, 0)
        putInt(KEY_SKIPPED, 0)
    }

    /** 画像与进度一起清掉；指纹库保留，避免刚清完就把旧建议当成本人发言。 */
    fun clearProfile(context: Context) = save(context) {
        remove(KEY_PROFILE)
        remove(KEY_UPDATED_AT)
        remove(KEY_CURSOR)
        remove(KEY_SAMPLE_COUNT)
        remove(KEY_RECENT)
        remove(KEY_VOCAB)
        // cursor 归零后必须重新 armed，否则下一轮会从 0 把全部历史捞一遍
        putBoolean(KEY_ARMED, true)
    }

    // ---- 建议指纹库：用来把 AI 帮我写的话从样本里剔掉 ----

    /**
     * 记一条 AI 生成过的建议。
     *
     * 只存 hash 不存原文：这份数据只是为了「排除」，没必要把生成的文本再落一份盘。
     * 狗头军师不直接发消息，它把文本写进输入框、由用户点发送，
     * 所以发出去之后数据库里和用户自己打的话没有区别，只能靠事前记账来区分。
     */
    fun rememberSuggestion(context: Context, text: String) {
        val clean = text.trim()
        if (clean.length < FINGERPRINT_MIN_LENGTH) return
        runCatching {
            val array = JSONArray(WarReviewStore.read(context).getString(KEY_SUGGESTIONS, "[]"))
            val fresh = JSONArray().put(fingerprint(clean))
            for (index in 0 until minOf(array.length(), MAX_SUGGESTIONS - 1)) {
                array.optString(index)?.takeIf { it.isNotBlank() }?.let { fresh.put(it) }
            }
            WarReviewStore.edit(context) { putString(KEY_SUGGESTIONS, fresh.toString()) }
        }
    }

    /** 这条消息是不是 AI 帮我写的。 */
    fun isAiWritten(context: Context, text: String): Boolean {
        val clean = text.trim()
        if (clean.length < FINGERPRINT_MIN_LENGTH) return false
        return runCatching {
            val array = JSONArray(WarReviewStore.read(context).getString(KEY_SUGGESTIONS, "[]"))
            // 只认整段相等：原样采纳 AI 的不算本人发言；
            // 只要改过、加过、删过，就算用户自己的表达，照常学进画像。
            val target = fingerprint(clean)
            (0 until array.length()).any { array.optString(it) == target }
        }.getOrDefault(false)
    }

    /**
     * 面板展示的样本：只给还没并进画像的那批。
     *
     * 读 KEY_PENDING 而不是 KEY_RECENT —— 后者是历史累计，整理完也不清，
     * 里面的句子早就被画像吸收过了，摆出来等于把画像里已有的东西再念一遍。
     */
    fun recentSamples(context: Context): List<Sample> = runCatching {
        val array = JSONArray(WarReviewStore.read(context).getString(KEY_PENDING, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Sample(item.optString("t"), item.optLong("at"))
        }
    }.getOrDefault(emptyList())

    fun recordSamples(context: Context, fresh: List<Sample>) {
        if (fresh.isEmpty()) return
        runCatching {
            val array = JSONArray(WarReviewStore.read(context).getString(KEY_RECENT, "[]"))
            val merged = JSONArray()
            fresh.forEach { merged.put(JSONObject().put("t", it.text.take(MAX_SAMPLE_LENGTH)).put("at", it.at)) }
            for (index in 0 until minOf(array.length(), RECENT_SHOW - fresh.size)) {
                array.optJSONObject(index)?.let { merged.put(it) }
            }
            WarReviewStore.edit(context) { putString(KEY_RECENT, merged.toString()) }
        }
    }

    data class Sample(val text: String, val at: Long)

    // ---- 请求侧（微信进程）：读快照 ----
    fun enabledForRequest(): Boolean = snapshot()?.warReviewEnabled ?: false
    fun profileForRequest(): String =
        snapshot()?.warReviewProfile.orEmpty().take(MAX_PROFILE_LENGTH)
    /**
     * 给各条狗生成时用的词库片段。
     *
     * 只取最近用过的 [VOCAB_IN_PROMPT] 个，不全量塞：词库是细节补充，
     * 铺满反而会挤掉对话本身的位置。空词库返回空串，调用方据此决定加不加这一段。
     */
    fun vocabForRequest(): String =
        parseVocab(snapshot()?.warReviewVocab.orEmpty())
            .sortedByDescending { it.at }
            .take(MAX_VOCAB)
            .joinToString("\n") { "- " + it.render() }

    /**
     * 画像 + 词库合成的一段风格约束，给各条狗直接拼进 system prompt。
     *
     * 画像讲整体风格，词库补具体用词——只有画像时模型知道「你爱用短句」，
     * 但不知道你具体说「咋」还是「怎么」。两者都没有时返回空串，调用方据此跳过整段。
     */
    fun styleForRequest(): String {
        val profile = profileForRequest()
        val vocab = vocabForRequest()
        if (profile.isBlank() && vocab.isBlank()) return ""
        return buildString {
            if (profile.isNotBlank()) append(profile)
            if (vocab.isNotBlank()) {
                if (profile.isNotBlank()) append("\n\n")
                append("我的用词偏好（同一类意思我习惯这么说，合适才用，不要硬塞）：\n")
                append(vocab)
            }
        }
    }

    /**
     * 整理时要告诉模型「库里已经有哪些词」，免得它把老词当新词再报一遍。
     *
     * 走 context 直接读 war_review.json，不走快照：调用方在 app 进程，
     * 而快照是给微信进程用的，可能还没推过去。
     */
    fun vocabForReview(context: Context): String =
        parseVocab(vocabJson(context))
            .sortedByDescending { it.at }
            .take(MAX_VOCAB)
            .joinToString("、") { item ->
                if (item.cat.isNotBlank()) "${item.cat}=${item.word}" else item.word
            }


    // ---- 跨进程：微信进程采集、app 进程过滤 ----
    //
    // 分工是被迫的：聊天库只有微信进程能读，而建议指纹库在 app 进程——
    // 微信进程没法直接判断「这条是不是 AI 帮我写的」。所以由微信进程把新消息
    // 整批送过来，这里过滤完再回传，一次调用解决，不逐条往返。

    /** 一次同步的结果。[needReview] 为真时微信进程才去调模型。 */
    data class SyncResult(
        val needReview: Boolean = false,
        val samplesJson: String = "[]",
        val oldProfile: String = "",
        val cursor: Long = 0L,
        val totalSamples: Int = 0,
        val skipped: Int = 0,
        val kept: Int = 0,
    )

    internal fun syncRemote(context: Context, batchJson: String, force: Boolean = false): SyncResult {
        if (context.packageName != MODULE) return SyncResult()
        val prefs = WarReviewStore.read(context)
        // 读不到磁盘上的旧样本就必须放弃这一轮。
        //
        // 下面的流程是「读快照 → 在内存里合并 → edit 写回」，而 edit 内部会
        // 重新读一次盘。如果这次 read 失败（IO 抖动/文件损坏），prefs 是空快照，
        // 合并结果里只剩这批新消息；而 edit 那次读盘可能成功，拿到的是有旧样本的
        // 真实数据——两边一不一致，写回就把攒着的待整理样本抹掉了。
        // 表现就是「进 app 冷启动一下，待整理的数字归零，画像和词库都还在」。
        if (!prefs.ok) {
            MoodLog.w("WAR_REVIEW_SYNC_SKIPPED read_failed")
            return SyncResult()
        }
        val batch = runCatching { JSONArray(batchJson) }.getOrDefault(JSONArray())

        var cursor = prefs.getLong(KEY_CURSOR, 0L)
        var total = prefs.getInt(KEY_SAMPLE_COUNT, 0)
        var skipped = 0
        var kept = 0
        val pending = runCatching {
            JSONArray(prefs.getString(KEY_PENDING, "[]"))
        }.getOrDefault(JSONArray())

        val merged = mutableListOf<String>()
        val keptTexts = mutableListOf<String>()

        // 聊天列表滚动、刷新都会重新绑定同一行，同一条消息会被推很多次
        val seenArray = runCatching {
            JSONArray(prefs.getString(KEY_SEEN_IDS, "[]"))
        }.getOrDefault(JSONArray())
        val seen = mutableSetOf<Long>()
        for (index in 0 until seenArray.length()) seen.add(seenArray.optLong(index))
        val newSeen = JSONArray()
        for (index in 0 until minOf(seenArray.length(), MAX_SEEN_IDS - 1)) {
            newSeen.put(seenArray.optLong(index))
        }
        for (index in 0 until pending.length()) {
            pending.optJSONObject(index)?.optString("t")?.takeIf { it.isNotBlank() }?.let { merged += it }
        }

        for (index in 0 until batch.length()) {
            val item = batch.optJSONObject(index) ?: continue
            val id = item.optLong("id")
            val text = item.optString("t").trim()
            if (id > cursor) cursor = id
            if (id > 0) {
                if (!seen.add(id)) continue
                newSeen.put(id)
            }
            if (text.isEmpty()) continue
            if (isAiWritten(context, text)) {
                skipped++
                continue
            }
            merged += text.take(MAX_SAMPLE_LENGTH)
            keptTexts += text.take(MAX_SAMPLE_LENGTH)
            kept++
            total++
        }

        // 只留最近 MAX_SAMPLES 条：更早的会被挤掉，画像靠的是近期说法
        val trimmed = merged.takeLast(MAX_SAMPLES)
        val pendingJson = JSONArray().also { array -> trimmed.forEach { array.put(JSONObject().put("t", it)) } }

        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        val interval = intervalHours(context) * 60L * 60L * 1000L
        // 手动触发时不受样本量和间隔限制：用户点了「立即整理」就是想现在看到结果
        val enough = force || trimmed.size >= minSamples(context)
        val due = force || System.currentTimeMillis() - updatedAt >= interval
        val needReview = force || (enough && due)

        // 面板上「最近采集到的话」：新的在前，只留 RECENT_SHOW 条
        val recentJson = JSONArray()
        keptTexts.takeLast(RECENT_SHOW).asReversed().forEach {
            recentJson.put(JSONObject().put("t", it))
        }
        val oldRecent = runCatching {
            JSONArray(prefs.getString(KEY_RECENT, "[]"))
        }.getOrDefault(JSONArray())
        for (index in 0 until minOf(oldRecent.length(), maxOf(0, RECENT_SHOW - recentJson.length()))) {
            oldRecent.optJSONObject(index)?.let { recentJson.put(it) }
        }

        // 用 commit 不用 apply：实测这台机器上 apply 的异步落盘没有兑现，
        // prefs 文件一直停在旧内容，游标推不动，每轮都从最早的消息重新抓。
        WarReviewStore.edit(context) {
            putString(KEY_SEEN_IDS, newSeen.toString())
            putString(KEY_PENDING, pendingJson.toString())
            putString(KEY_RECENT, recentJson.toString())
            putLong(KEY_CURSOR, cursor)
            putInt(KEY_SAMPLE_COUNT, total)
            putInt(KEY_SKIPPED, prefs.getInt(KEY_SKIPPED, 0) + skipped)
        }

        return SyncResult(
            needReview = needReview,
            samplesJson = pendingJson.toString(),
            oldProfile = prefs.getString(KEY_PROFILE, "").orEmpty(),
            cursor = cursor,
            totalSamples = total,
            skipped = skipped,
            kept = kept,
        )
    }

    /** 微信进程整理完画像后写回；[profile] 为空表示这次没整理，只更新进度。 */
    internal fun saveRemote(context: Context, profile: String) {
        if (context.packageName != MODULE) return
        runCatching {
            WarReviewStore.edit(context) {
                if (profile.isNotBlank()) {
                    putString(KEY_PROFILE, profile.trim().take(MAX_PROFILE_LENGTH))
                    putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                    remove(KEY_PENDING)
                }
            }
        }
    }

    /**
     * 面板要看的几个数。
     *
     * 第三个是当前待整理的条数，不是累计收录数：累计数只增不减，
     * 和画像准不准没有对应关系，而且它统计的样本大多已经被吸收掉了。
     */
    fun stats(context: Context): Triple<Int, Int, Int> = runCatching {
        val prefs = WarReviewStore.read(context)
        val suggestions = JSONArray(prefs.getString(KEY_SUGGESTIONS, "[]")).length()
        val pending = JSONArray(prefs.getString(KEY_PENDING, "[]")).length()
        Triple(suggestions, prefs.getInt(KEY_SKIPPED, 0), pending)
    }.getOrDefault(Triple(0, 0, 0))

    private fun snapshot() = runCatching { ModulePrefs.settings() }.getOrNull()

    private fun fingerprint(text: String): String =
        com.dogheadstrategist.chat.core.CacheKey.digest("war-review-v1", text)

    private const val MODULE = "com.dogheadstrategist.chat"

    private fun readBoolean(context: Context, key: String, fallback: Boolean): Boolean =
        runCatching { WarReviewStore.read(context).getBoolean(key, fallback) }.getOrDefault(fallback)

    private fun readInt(context: Context, key: String, fallback: Int): Int =
        runCatching { WarReviewStore.read(context).getInt(key, fallback) }.getOrDefault(fallback)

    private fun readLong(context: Context, key: String, fallback: Long): Long =
        runCatching { WarReviewStore.read(context).getLong(key, fallback) }.getOrDefault(fallback)

    private fun readString(context: Context, key: String, fallback: String): String =
        runCatching { WarReviewStore.read(context).getString(key, fallback) }.getOrDefault(fallback)

    private fun save(context: Context, edit: WarReviewStore.Editor.() -> Unit): Boolean {
        WarReviewStore.edit(context, edit)
        return true
    }
}
