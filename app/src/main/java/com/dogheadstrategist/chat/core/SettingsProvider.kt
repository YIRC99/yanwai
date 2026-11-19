package com.dogheadstrategist.chat.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONArray
import java.util.UUID

/** Settings bridge restricted to this app and WeChat, where the model requests run. */
class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean { context?.let(MoodLog::init); return true }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        dispatch(requireNotNull(context), Binder.getCallingUid(), method, arg, extras)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object {
        fun dispatch(ctx: Context, caller: Int, method: String, arg: String?, extras: Bundle?): Bundle {
            val own = caller == Process.myUid()
            val wechat = ctx.packageManager.getPackagesForUid(caller)?.contains("com.tencent.mm") == true
            if (!own && !wechat) {
                MoodLog.w("PROVIDER_CALL_DENIED uid=$caller method=$method")
                throw SecurityException("Caller is not allowed: uid=$caller")
            }
            return when (method) {
                "reply_limit_get", "reply_limit_put" -> {
                    val prefs = ctx.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
                    val store = com.dogheadstrategist.chat.reply.ReplyLimitPreferences(caller,
                        { key -> runCatching { if (prefs.contains(key)) prefs.getInt(key, 100) else null }.getOrNull() },
                        { key, value -> check(prefs.edit().putInt(key, value).commit()) })
                    val account = requireNotNull(arg)
                    if (method == "reply_limit_get") Bundle().apply { putInt("limit", store.load(account)) }
                    else {
                        store.save(account, requireNotNull(extras).getInt("limit", 0))
                        Bundle().apply { putBoolean("saved", true) }
                    }
                }
                "reply_role_put", "reply_identity_get", "reply_identity_put", "contact_background_get", "contact_background_put", "reply_bound_talker_tags", "reply_background_for_talker", "reply_profile_for_talker", "reply_identity_labels", "reply_role_clear" -> {
                    val result = ReplyIdentityProvider.call(ctx, caller, method, arg, extras)
                    if (method == "reply_role_put") publish(ctx)
                    if (method.endsWith("_put")) {
                        // A refresh notification failure must not turn a completed disk write into a save failure.
                        runCatching { ctx.contentResolver.notifyChange(ReplyIdentityProvider.CHANGES_URI, null) }
                    }
                    result
                }
                "sentry_pull" -> Bundle().apply {
                    putString("pending", com.dogheadstrategist.chat.feature.SentryQueue.pending(ctx))
                    putLong("revision", com.dogheadstrategist.chat.feature.SentryQueue.revision(ctx))
                }
                "sentry_ack" -> {
                    com.dogheadstrategist.chat.feature.SentryQueue.ack(ctx, arg?.toLongOrNull() ?: -1L)
                    Bundle()
                }
                // 通知代办：微信进程提取到要事，推过来由 app 进程落盘。
                // 文件在 app 的 filesDir 下，微信进程够不着，必须借这一跳。
                "aide_items_put" -> {
                    val saved = com.dogheadstrategist.chat.feature.MatterStore.putRemote(ctx, arg.orEmpty())
                    Bundle().apply { putBoolean("saved", saved) }
                }
                // 微信进程解冻时把最近会话的记录抄一份过来，app 进程据此在后台独立生成
                "sentry_records_put" -> {
                    com.dogheadstrategist.chat.feature.SentryStore.putRecords(ctx, arg.orEmpty())
                    Bundle().apply { putBoolean("saved", true) }
                }
                "sentry_records_get" -> Bundle().apply {
                    putString("records", com.dogheadstrategist.chat.feature.SentryStore.records(ctx))
                }
                // 会话索引（talker + 昵称，无内容）：白名单在 app 进程挑选联系人时用
                "sentry_sessions_put" -> {
                    com.dogheadstrategist.chat.feature.SentryStore.putSessions(ctx, arg.orEmpty())
                    Bundle().apply { putBoolean("saved", true) }
                }
                // 微信进程里的 AI 调用统计写不回来，委托这里落到 app 进程的 prefs
                "ai_stats_record" -> {
                    com.dogheadstrategist.chat.feature.AiStats.recordRemote(ctx, arg.orEmpty(),
                        extras?.getLong("duration", 0L) ?: 0L,
                        extras?.getInt("prompt", 0) ?: 0,
                        extras?.getInt("completion", 0) ?: 0,
                        extras?.getBoolean("ok", false) ?: false,
                        extras?.getLong("first", 0L) ?: 0L)
                    Bundle().apply { putBoolean("saved", true) }
                }
                // 我的风格刚开启：微信进程查到的最大 msgId 回填，作为采集起点
                "war_review_cursor" -> {
                    ctx.getSharedPreferences(ModulePrefs.FILE_NAME, 0).edit()
                        .putLong(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_CURSOR,
                            arg?.toLongOrNull() ?: 0L)
                        .putBoolean(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_ARMED, false)
                        .apply()
                    Bundle().apply { putBoolean("ok", true) }
                }
                // 我的风格实时采集：我每发一条文字消息，微信进程就把这一条推过来
                "war_review_sent" -> {
                    val id = extras?.getLong("id", 0L) ?: 0L
                    val batch = org.json.JSONArray()
                        .put(org.json.JSONObject().put("id", id).put("t", arg.orEmpty()))
                    com.dogheadstrategist.chat.feature.WarReviewPrefs
                        .syncRemote(ctx, batch.toString(), false)
                    Bundle().apply { putBoolean("ok", true) }
                }
                // 我的风格记账：微信进程把「刚写进输入框的建议」报过来，进指纹库
                "war_review_fp" -> {
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.rememberSuggestion(ctx, arg.orEmpty())
                    Bundle().apply { putBoolean("saved", true) }
                }
                // 我的风格的采集游标：微信进程要先知道从哪条往后读，才能只取新消息
                "war_review_state" -> Bundle().apply {
                    // 我的风格的数据不在 SharedPreferences 里（那套在这台机器上不落盘），
                    // 统一走 WarReviewPrefs，它内部用 JSON 文件。
                    val wr = com.dogheadstrategist.chat.feature.WarReviewPrefs
                    putLong("cursor", wr.cursor(ctx))
                    // 「立即整理」是一次性的：读走就清掉，否则会一直强制
                    val force = wr.forceRequested(ctx)
                    putBoolean("force", force)
                    putBoolean("armed", wr.armed(ctx))
                    if (force) wr.setForceRequested(ctx, false)
                }
                // 我的风格：微信进程采集到新消息后整批送过来过滤——指纹库在 app 进程，
                // 微信进程读不到；一次调用解决，不逐条往返。
                "war_review_sync" -> {
                    val result = com.dogheadstrategist.chat.feature.WarReviewPrefs
                        .syncRemote(ctx, arg.orEmpty(), extras?.getBoolean("force", false) ?: false)
                    Bundle().apply {
                        putBoolean("need", result.needReview)
                        putString("samples", result.samplesJson)
                        putString("profile", result.oldProfile)
                        putString("vocab", com.dogheadstrategist.chat.feature.WarReviewPrefs.vocabForReview(ctx))
                        putLong("cursor", result.cursor)
                        putInt("total", result.totalSamples)
                        putInt("skipped", result.skipped)
                        putInt("kept", result.kept)
                    }
                }
                // 画像在微信进程算，写回 app 进程的 prefs（顺便触发一次快照同步）
                "war_review_save" -> {
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.saveRemote(ctx, arg.orEmpty())
                    // 画像写在 war_review.json，不走 SharedPreferences，
                    // 所以 save() 里的自动 publish 不会触发，得手动推一次
                    publish(ctx)
                    Bundle().apply { putBoolean("saved", true) }
                }
                // 词库：微信进程整理完只把新词送过来，去重和淘汰在 app 进程做
                // 词库：微信进程整理完只把新增/改口的条目送过来，去重和淘汰在 app 进程做。
                // 每条是 {cat, word, avoid[]}——类别由模型自己从样本里归纳，不是固定清单。
                "war_review_vocab" -> {
                    val words = runCatching {
                        val array = JSONArray(arg.orEmpty())
                        (0 until array.length()).mapNotNull { index ->
                            val item = array.optJSONObject(index) ?: return@mapNotNull null
                            val word = item.optString("word").trim()
                            if (word.isEmpty()) null else {
                                val avoidArray = item.optJSONArray("avoid")
                                val avoid = if (avoidArray == null) emptyList() else
                                    (0 until avoidArray.length()).mapNotNull {
                                        avoidArray.optString(it).trim().takeIf { w -> w.isNotEmpty() }
                                    }
                                com.dogheadstrategist.chat.feature.WarReviewPrefs.VocabWord(
                                    cat = item.optString("cat").trim(),
                                    word = word,
                                    avoid = avoid,
                                    at = System.currentTimeMillis(),
                                )
                            }
                        }
                    }.getOrDefault(emptyList())
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.addVocab(ctx, words)
                    publish(ctx)
                    Bundle().apply { putBoolean("saved", true) }
                }
                "sentry_sessions_get" -> Bundle().apply {
                    putString("sessions", com.dogheadstrategist.chat.feature.SentryStore.sessions(ctx))
                }
                // app 进程生成好的建议放这里，微信进程显示时来取
                "sentry_suggestions_put" -> {
                    com.dogheadstrategist.chat.feature.SentryStore.putSuggestions(ctx, arg.orEmpty())
                    Bundle().apply { putBoolean("saved", true) }
                }
                "sentry_suggestions_get" -> Bundle().apply {
                    putString("suggestions", com.dogheadstrategist.chat.feature.SentryStore.suggestions(ctx))
                }
                "config" -> snapshot(ctx)
                "report" -> {
                    ctx.getSharedPreferences(RUNTIME_FILE, 0).edit()
                        .putString("status", arg.orEmpty().take(200))
                        .putLong("last_seen", System.currentTimeMillis()).apply {
                            extras?.getString("host_log")?.let {
                                putString("host_log", MoodLog.sanitize(it).takeLast(48 * 1024))
                                putLong("host_log_at", System.currentTimeMillis())
                            }
                        }.apply()
                    Bundle()
                }
                else -> throw IllegalArgumentException("Unknown method")
            }
        }

        val URI: Uri = Uri.parse("content://com.dogheadstrategist.chat.settings")
        const val RUNTIME_FILE = "wechat_runtime"
        const val KEY_REVISION = "settings_revision"
        const val KEY_GENERATION = "settings_generation"

        @Synchronized fun snapshot(context: Context): Bundle {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
            val generation = prefs.getString(KEY_GENERATION, null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString(KEY_GENERATION, it).commit())
            }
            return Bundle().apply {
                putString(KEY_GENERATION, generation)
                putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L))
                // 角色库取消后没有可变的目录版本，恒 0 让微信侧的「角色已变化」检查自然失效
                putLong(ReplyIdentityProvider.KEY_ROLE_REVISION, 0L)
                putBoolean(ModulePrefs.KEY_EXPLORE, prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false))
                listOf(com.dogheadstrategist.chat.reply.ReplySettings.KEY_ENDPOINT,
                    com.dogheadstrategist.chat.reply.ReplySettings.KEY_API_KEY,
                    com.dogheadstrategist.chat.reply.ReplySettings.KEY_MODEL,
                    com.dogheadstrategist.chat.reply.ReplySettings.KEY_BACKUP_ENDPOINT,
                    com.dogheadstrategist.chat.reply.ReplySettings.KEY_BACKUP_API_KEY,
                    com.dogheadstrategist.chat.reply.ReplySettings.KEY_BACKUP_MODEL).forEach { putString(it, prefs.getString(it, "")) }
                putLong(com.dogheadstrategist.chat.reply.ReplySettings.KEY_FAILOVER_MS,
                    prefs.getLong(com.dogheadstrategist.chat.reply.ReplySettings.KEY_FAILOVER_MS,
                        com.dogheadstrategist.chat.reply.ReplySettings.DEFAULT_FAILOVER_MS))
                putLong(com.dogheadstrategist.chat.reply.ReplySettings.KEY_FINAL_MS,
                    prefs.getLong(com.dogheadstrategist.chat.reply.ReplySettings.KEY_FINAL_MS,
                        com.dogheadstrategist.chat.reply.ReplySettings.DEFAULT_FINAL_MS))
                putInt(com.dogheadstrategist.chat.reply.ReplySettings.KEY_MAX_TOKENS,
                    prefs.getInt(com.dogheadstrategist.chat.reply.ReplySettings.KEY_MAX_TOKENS,
                        com.dogheadstrategist.chat.reply.ReplySettings.DEFAULT_MAX_TOKENS))
                // 我的风格的配置存在独立的 war_review.json 里，不在 SharedPreferences。
                // 之前这里读的是 prefs，两套存储没有交集，画像和开关从来没同步到微信进程过。
                putBoolean(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_ENABLED,
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.enabled(context))
                putString(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_PROFILE,
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.profile(context))
                putString(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_VOCAB,
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.vocabJson(context))
                putInt(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_INTERVAL_HOURS,
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.intervalHours(context))
                putInt(com.dogheadstrategist.chat.feature.WarReviewPrefs.KEY_MIN_SAMPLES,
                    com.dogheadstrategist.chat.feature.WarReviewPrefs.minSamples(context))
                // 通知代办：类型清单存在 aide_types.json，不在 SharedPreferences 里，
                // 和我的风格一样必须绕开 prefs 直接读文件，否则微信进程永远拿不到用户自定义的类型。
                putBoolean(com.dogheadstrategist.chat.feature.AidePrefs.KEY_ENABLED,
                    com.dogheadstrategist.chat.feature.AidePrefs.enabled(context))
                putString(com.dogheadstrategist.chat.feature.AidePrefs.KEY_TYPES,
                    com.dogheadstrategist.chat.feature.MatterStore.typesJson(context))
                // 关键用户清单：图片识别和文字提取都要用它过滤，微信进程同样读不到文件
                putString(com.dogheadstrategist.chat.feature.AidePrefs.KEY_KEY_USERS,
                    com.dogheadstrategist.chat.feature.AidePrefs.keyUsersJson(context))
                // 人物档案：微信进程侧要能判断「该不该总结」「攒够没有」
                putBoolean(com.dogheadstrategist.chat.feature.IntelligencePrefs.KEY_ENABLED,
                    com.dogheadstrategist.chat.feature.IntelligencePrefs.enabled(context))
                putInt(com.dogheadstrategist.chat.feature.IntelligencePrefs.KEY_MIN_SAMPLES,
                    com.dogheadstrategist.chat.feature.IntelligencePrefs.minSamples(context))
                putInt(com.dogheadstrategist.chat.feature.IntelligencePrefs.KEY_INTERVAL_HOURS,
                    com.dogheadstrategist.chat.feature.IntelligencePrefs.intervalHours(context))
                putBoolean(com.dogheadstrategist.chat.reply.ReplySettings.KEY_CONSENT,
                    prefs.getBoolean(com.dogheadstrategist.chat.reply.ReplySettings.KEY_CONSENT, false))
                putBoolean(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_ENABLED,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_ENABLED, false))
                putInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_MIN_PREFIX,
                    prefs.getInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_MIN_PREFIX,
                        com.dogheadstrategist.chat.feature.RelayPrefs.DEFAULT_MIN_PREFIX))
                putInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_CONTEXT_LIMIT,
                    prefs.getInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_CONTEXT_LIMIT,
                        com.dogheadstrategist.chat.feature.RelayPrefs.DEFAULT_CONTEXT_LIMIT))
                putInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_CANDIDATES,
                    prefs.getInt(com.dogheadstrategist.chat.feature.RelayPrefs.KEY_CANDIDATES,
                        com.dogheadstrategist.chat.feature.RelayPrefs.DEFAULT_CANDIDATES))
                putInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_CACHE_TTL_MINUTES,
                    prefs.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_CACHE_TTL_MINUTES, 10))
                putBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_TEST_MODE,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_TEST_MODE, false))
                putString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TEMPLATE_BACKUP,
                    prefs.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TEMPLATE_BACKUP, ""))
                putString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_CUSTOM_BACKUP,
                    prefs.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_CUSTOM_BACKUP, ""))
                putBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_REPLY,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_REPLY, false))
                putBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TOPICS,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TOPICS, false))
                putBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_RELAY,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_RELAY, false))
                // 后补的四个功能开关：通知代办 / 人物档案 / 通知摘要 / 我的风格
                putBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_AIDE,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_AIDE, false))
                putBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_INTEL,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_INTEL, false))
                putBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_COMMUNICATE,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_COMMUNICATE, false))
                putBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_WAR_REVIEW,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_WAR_REVIEW, false))
                putString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TEMPLATE,
                    prefs.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_TEMPLATE,
                        com.dogheadstrategist.chat.feature.ThinkingPrefs.DEFAULT_TEMPLATE))
                putString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_CUSTOM,
                    prefs.getString(com.dogheadstrategist.chat.feature.ThinkingPrefs.KEY_CUSTOM, ""))
                putBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_ENABLED, prefs.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_ENABLED, false))
                putInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_COUNT, prefs.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_COUNT, com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_COUNT))
                putInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_CONTEXT, prefs.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_CONTEXT, com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_CONTEXT))
                putInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_COOLDOWN, prefs.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_COOLDOWN, com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_COOLDOWN))
                putBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_ONLY_UNREPLIED, prefs.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_ONLY_UNREPLIED, true))
                putInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_SYNC_USERS, prefs.getInt(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_SYNC_USERS, com.dogheadstrategist.chat.feature.SentryPrefs.DEFAULT_SYNC_USERS))
                putBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_WHITELIST_ENABLED,
                    prefs.getBoolean(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_WHITELIST_ENABLED, false))
                putString(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_WHITELIST,
                    prefs.getString(com.dogheadstrategist.chat.feature.SentryPrefs.KEY_WHITELIST, ""))
            }
        }

        @Synchronized fun save(context: Context, edit: SharedPreferences.Editor.() -> Unit): Boolean {
            val prefs = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0)
            val saved = prefs.edit().apply(edit)
                .putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L) + 1L).commit()
            MoodLog.i("SETTINGS_DISK_SAVE saved=$saved revision=${prefs.getLong(KEY_REVISION, 0L)}")
            if (saved) publish(context)
            return saved
        }

        fun publish(context: Context) {
            // A transport failure must not misreport a successful disk write as a failed save.
            runCatching { SettingsSync.publish(context, snapshot(context)) }
                .onFailure { MoodLog.e("SYNC_PUBLISH_FAILED 设置已落盘但同步失败", it) }
        }
    }
}
