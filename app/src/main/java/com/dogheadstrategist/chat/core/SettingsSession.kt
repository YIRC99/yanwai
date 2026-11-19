package com.dogheadstrategist.chat.core

// Memory only: credentials are not copied into WeChat's files or backups.
class RuntimeSettings(val revision: Long,
    val exploreMode: Boolean, val generation: String,
    val reply: com.dogheadstrategist.chat.reply.ReplySettings = com.dogheadstrategist.chat.reply.ReplySettings.empty(),
    val rolesRevision: Long = 0,
    /** 续话（内联补全）开关，随设置快照同步到微信进程。 */
    val relayEnabled: Boolean = false,
    /** 续话触发字数：输入达到这个长度才请求预测。 */
    val relayMinPrefix: Int = 4,
    /** 续话读多少条聊天记录作为上下文。 */
    val relayContextLimit: Int = 20,
    /** 续话一次拉取多少条候选。 */
    val relayCandidates: Int = 50,
    /** 各功能各自的思考模式开关。默认全关：思考 token 同时计入延迟与费用。 */
    val thinkingReply: Boolean = false,
    val thinkingTopics: Boolean = false,
    val thinkingRelay: Boolean = false,
    /** 关闭思考用的片段：模板 id（见 ThinkingMode.Template）。 */
    val thinkingTemplate: String = "none",
    /** 自定义片段内容，仅 template=custom 时使用。 */
    val thinkingCustom: String = "",
    /** 自动起草：收到通知时提前准备回复。 */
    val sentryEnabled: Boolean = false,
    val sentryCount: Int = 3,
    val sentryContext: Int = 20,
    val sentryCooldown: Int = 60,
    val sentryOnlyUnreplied: Boolean = true,
    /** 本地同步多少个会话的聊天记录，供 app 进程独立生成建议。 */
    val sentrySyncUsers: Int = 5,
    /** 白名单开关：开启后只有名单内联系人触发自动起草；名单外的会话连记录都不读。 */
    val sentryWhitelistEnabled: Boolean = false,
    /** 白名单条目 JSON，形如 [{"t":talker,"n":昵称}]。 */
    val sentryWhitelist: String = "",
    /** 备用模型的关闭片段；为空表示沿用主配置的片段。 */
    val thinkingTemplateBackup: String = "",
    val thinkingCustomBackup: String = "",
    /** 自动起草测试模式：所有聊天界面都挂示例卡片，不发真实请求。 */
    val sentryTestMode: Boolean = false,
    /** 自动起草建议保留时长（分钟），0 = 永不过期。 */
    val sentryCacheTtlMinutes: Int = 10,
    /** 我的风格：从我自己发出的消息里学说话方式，画像随快照进微信进程。 */
    val warReviewEnabled: Boolean = false,
    val warReviewProfile: String = "",
    /** 用户词库：他自己高频用的词。随快照进微信进程，供各功能生成时取用。 */
    val warReviewVocab: String = "",
    val warReviewIntervalHours: Int = 24,
    val warReviewMinSamples: Int = 80,
    /** 通知代办：从通知里提取重要事项。 */
    val aideEnabled: Boolean = false,
    /** 生效的类型清单 JSON。用户在设置页改了，下一轮快照就带过去。 */
    val aideTypes: String = "",
    /** 关键用户清单 JSON。只提取与这些人相关的事项；空串表示不过滤。 */
    val aideKeyUsers: String = "",
    /** 人物档案：为聊天对象自动累积画像。 */
    val intelEnabled: Boolean = false,
    /** 攒够多少条新消息才触发一次画像总结。 */
    val intelMinSamples: Int = 20,
    /** 时间兜底：距上次总结超过这么多小时，没攒够条数也整理。0 = 关闭。 */
    val intelIntervalHours: Int = 0,
    // ---- 思考模式：后补的四个功能开关 ----
    // 一律追加在末尾：RuntimeSettings 是位置参数构造，中间插入会让后面全部错位。
    val thinkingAide: Boolean = false,
    val thinkingIntel: Boolean = false,
    val thinkingCommunicate: Boolean = false,
    val thinkingWarReview: Boolean = false
) {
    val canGenerateReply get() = reply.isConfigured

    private val fingerprint: String by lazy {
        CacheKey.digest("reply-v1", generation, reply.endpoint, reply.apiKey, reply.model, rolesRevision.toString())
    }

    fun settingsFingerprint(): String = fingerprint
    fun sameSettings(other: RuntimeSettings) = settingsFingerprint() == other.settingsFingerprint()
}

class SettingsSession {
    @Volatile var current: RuntimeSettings? = null
        private set
    private var generation: String? = null
    private var pendingVerification = false
    private val retiredGenerations = mutableSetOf<String>()

    /** False asks the caller to verify a changed installation through the Provider. */
    @Synchronized fun accept(snapshot: RuntimeSettings?, fromProvider: Boolean = true): Boolean {
        // Transport failure is not a settings change. An explicit empty key is.
        if (snapshot == null) return true
        if (!fromProvider && snapshot.generation in retiredGenerations) return true
        if (!fromProvider && pendingVerification) return false
        if (generation != null && generation != snapshot.generation) {
            if (!fromProvider) {
                current = null // Do not use old credentials while a data reset is unverified.
                pendingVerification = true
                return false
            }
            retiredGenerations.add(requireNotNull(generation))
            current = null
        }
        generation = snapshot.generation
        pendingVerification = false
        retiredGenerations.remove(snapshot.generation)
        if (snapshot.rolesRevision < (current?.rolesRevision ?: 0L)) return true
        if (snapshot.revision >= (current?.revision ?: -1L)) {
            if (current?.sameSettings(snapshot) != true) current = null
            current = snapshot
        }
        return true
    }
}
