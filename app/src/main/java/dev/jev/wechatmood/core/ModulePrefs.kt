package dev.jev.wechatmood.core

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object ModulePrefs {
    const val FILE_NAME = "wechatmood_config"
    const val KEY_EXPLORE = "explore_mode"
    const val KEY_API_KEY = "api_key"
    const val KEY_API_BASE = "api_base"
    const val KEY_API_PROVIDER = "api_provider"
    const val KEY_API_MODEL = "api_model"
    @Volatile private var context: Context? = null
    @Volatile private var conversations: ConversationSwitches? = null
    private val manualAnalysis = ManualAnalysis()
    private val analysisSnapshots = AnalysisSnapshots()
    private val session = SettingsSession {
        dev.jev.wechatmood.analysis.SignalAnalyzer.resetSettings()
        analysisSnapshots.clear()
    }
    private val bridgeWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "yanwai-settings") }
    private val bridge = SettingsBridge(session, { bridgeWorker.execute(it) },
        SystemClock::elapsedRealtime, ::readSettings, ::reportNow)
    /** 设置服务被系统拦下时的退避重试；成功一次就清零。 */
    private val retryScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "yanwai-retry").apply { isDaemon = true } }
    private val retryDelays = longArrayOf(2_000L, 5_000L, 15_000L, 60_000L)
    private val retryLock = Any()
    @Volatile private var retryStep = 0
    @Volatile private var warmScheduled = false
    @Volatile var lastBridgeError: String? = null
        private set
    private var lastFailureSignature = ""
    private var lastFailureAt = -60_000L
    private var lastReportAt = -15_000L

    @Synchronized private fun failure(operation: String, error: Throwable) {
        val detail = "$operation：${error.javaClass.simpleName} ${error.message.orEmpty()}"
        lastBridgeError = MoodLog.sanitize(detail).take(600)
        val now = SystemClock.elapsedRealtime()
        if (detail != lastFailureSignature || now - lastFailureAt >= 60_000L) {
            MoodLog.e(operation, error)
            lastFailureSignature = detail
            lastFailureAt = now
        }
    }

    @Synchronized private fun connected() {
        if (lastBridgeError != null) MoodLog.i("BRIDGE_RECOVERED 设置服务连接恢复")
        lastBridgeError = null
        lastFailureSignature = ""
        synchronized(retryLock) { retryStep = 0 }
        warmReplySnapshot()
    }

    /**
     * 连上一次成功就把角色列表留在微信进程里。
     * 否则「帮我回」要等用户主动打开抽屉才写缓存，冷启动时就没得回退。
     */
    private fun warmReplySnapshot() {
        if (warmScheduled) return
        val ctx = context ?: return
        val generation = session.current?.generation ?: return
        warmScheduled = true
        bridgeWorker.execute {
            runCatching {
                val response = SettingsTransport.call(ctx, "reply_role_list", null,
                    Bundle().apply { putString(SettingsProvider.KEY_GENERATION, generation) })
                val roles = response?.getStringArrayList("roles") ?: return@runCatching
                ReplySnapshotCache.saveRoles(ctx, roles)
                MoodLog.i("REPLY_ROLES_WARMED count=${roles.size}")
            }.onFailure {
                warmScheduled = false
                MoodLog.w("REPLY_ROLES_WARM_FAILED ${it.javaClass.simpleName}")
            }
        }
    }
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        this.context = app
        AnalysisResultCache.init(app)
        if (app.packageName == "com.tencent.mm" && conversations == null) {
            val local = app.getSharedPreferences("yanwai_conversations", Context.MODE_PRIVATE)
            conversations = ConversationSwitches(local.getStringSet("enabled_chats", emptySet()).orEmpty()) {
                local.edit().putStringSet("enabled_chats", it).commit()
            }
        }
        // Register before reading so a save racing with initial loading cannot be missed.
        runCatching { SettingsSync.register(app, ::receiveSettings) }
            .onFailure { MoodLog.e("SYNC_REGISTER_FAILED", it) }
        if (app.packageName == "com.tencent.mm") {
            // 主进程可能被厂商后台策略挡住：先用上次成功的加密快照，让功能立刻可用，
            // 再异步刷新真实配置（广播或 Provider 成功后会覆盖）。
            cachedSnapshot()?.let { snapshot ->
                session.accept(snapshot, fromProvider = false)
                MoodLog.i("SNAPSHOT_RESTORED revision=${snapshot.revision}")
            }
            requestReload(force = true)
        } else reload(force = true)
    }

    private fun cachedSnapshot(): RuntimeSettings? = runCatching {
        SettingsSnapshotCache.load(requireNotNull(context))?.let(SettingsSync::decode)
    }.getOrNull()

    /** 只在读取失败后调度一次；成功会通过 [connected] 清零。 */
    private fun scheduleRetry() {
        val delay = synchronized(retryLock) {
            if (retryStep >= retryDelays.size) return
            retryDelays[retryStep++]
        }
        runCatching {
            retryScheduler.schedule({ requestReload(force = true) }, delay, TimeUnit.MILLISECONDS)
        }.onFailure { MoodLog.w("RETRY_SCHEDULE_FAILED ${it.javaClass.simpleName}") }
    }
    private fun receiveSettings(bundle: Bundle) {
        val snapshot = SettingsSync.decode(bundle)
        if (snapshot == null) { MoodLog.w("SYNC_INVALID 广播配置不完整"); return }
        // Invalidate a changed installation immediately, without waiting for Provider I/O.
        bridge.receive(snapshot)
        MoodLog.protect(snapshot.api.apiKey)
        MoodLog.protect(snapshot.reply.apiKey)
        MoodLog.protect(snapshot.intent.llm.apiKey)
        MoodLog.i("SYNC_RECEIVED revision=${snapshot.revision}；广播送达不代表设置服务可访问")
    }
    fun requestReload(force: Boolean = false) = bridge.requestReload(force)
    fun reload(force: Boolean = false) = bridge.reload(force)

    private fun readSettings(): RuntimeSettings? = runCatching {
        val result = SettingsTransport.call(requireNotNull(context), "config", null, null)
            ?: error("设置服务无响应或不可见；检查隐藏应用列表规则及微信分身所在空间")
        val decoded = requireNotNull(SettingsSync.decode(result)) { "设置服务返回的配置不完整" }
        // 拿到一次就落一份加密快照，供下次微信冷启动时立刻可用。
        context?.let { SettingsSnapshotCache.save(it, result) }
        decoded.also { MoodLog.protect(it.api.apiKey); MoodLog.protect(it.reply.apiKey); MoodLog.protect(it.intent.llm.apiKey); connected() }
    }.onFailure { failure("BRIDGE_READ_FAILED", it); scheduleRetry() }.getOrNull()
    // No verified snapshot means disabled; a lost connection preserves the last explicit choice.
    val bridgeAvailable get() = session.current != null
    fun analysisSettings() = session.current
    fun replySettings() = session.current?.reply ?: dev.jev.wechatmood.reply.ReplySettings.empty()
    val canGenerateReply get() = session.current?.canGenerateReply == true
    val exploreMode get() = session.current?.exploreMode == true
    val apiKey get() = session.current?.api?.apiKey.orEmpty()
    fun apiSettings(): ApiSettings = session.current?.api ?: ApiSettings.fromInput(ApiSettings.DEFAULT_ENDPOINT, "")
    fun isChatEnabled(talker: String?) = conversations?.isEnabled(talker) == true
    fun canAnalyze(talker: String?) = isChatEnabled(talker) && session.current?.canAnalyze == true
    private fun scoped(input: AnalysisInput): AnalysisInput {
        val account = dev.jev.wechatmood.hook.ReplyDatabaseHistory.accountScope(input)
        val key = dev.jev.wechatmood.reply.ReplyContactKey.of(account, input.talker)
        val background = if (key == null) dev.jev.wechatmood.reply.ContactBackground()
            else context?.let { ReplyIdentityBridge.background(it, key) }
        return input.copy(accountScope = account, background = background ?: dev.jev.wechatmood.reply.ContactBackground(),
            backgroundReady = background != null, settingsFingerprint = session.current?.analysisFingerprint().orEmpty())
    }
    fun backgroundChanged(talker: String) {
        dev.jev.wechatmood.analysis.SignalAnalyzer.cancelConversation(talker)
        analysisSnapshots.clearConversation(talker)
    }
    fun backgroundCurrent(input: AnalysisInput): Boolean {
        val latest = scoped(input)
        return latest.accountScope == input.accountScope && latest.backgroundReady && latest.background == input.background &&
            latest.settingsFingerprint == input.settingsFingerprint
    }
    fun analysisInput(input: AnalysisInput): AnalysisInput {
        val current = scoped(input)
        if (!current.accountScope.startsWith("pending:")) {
            manualAnalysis.resolveAccount(current, dev.jev.wechatmood.hook.ReplyDatabaseHistory.pendingAccountScope())
            if (!current.accountScope.startsWith("memory:"))
                manualAnalysis.resolveAccount(current, dev.jev.wechatmood.hook.ReplyDatabaseHistory.pendingAccountScope().replace("pending:", "memory:"))
        }
        val selected = manualAnalysis.selectedInput(current)?.copy(background = current.background, backgroundReady = current.backgroundReady,
            settingsFingerprint = current.settingsFingerprint)
        return selected ?: if (isChatEnabled(current.talker)) analysisSnapshots.resolve(current) else current
    }
    fun shouldDisplay(input: AnalysisInput): Boolean =
        input.accountScope == dev.jev.wechatmood.hook.ReplyDatabaseHistory.accountScope(input) &&
            !manualAnalysis.isHidden(input) &&
            manualAnalysis.allows(input, isChatEnabled(input.talker))
    fun canAnalyze(input: AnalysisInput): Boolean =
        // 联系人背景要经 Provider 读取，主进程没被拉起时读不到；
        // 这种情况降级为「无背景」继续分析，不要整块功能一起停掉。
        (!input.backgroundReady || backgroundCurrent(input)) &&
            !input.accountScope.startsWith("pending:") &&
            shouldDisplay(input) && session.current?.canAnalyze == true
    fun selectMessage(input: AnalysisInput): Boolean {
        manualAnalysis.reveal(input)
        return manualAnalysis.select(scoped(input))
    }
    /** 用户点一下卡片即可收起；只影响这一条，重新长按「翻译意图」会恢复。 */
    fun hideCard(input: AnalysisInput): Boolean = manualAnalysis.hide(input)
    fun setChatEnabled(talker: String?, value: Boolean): Boolean = runCatching {
        val saved = conversations?.setEnabled(talker, value) == true
        // Only an explicit, successfully saved switch-off resets this chat's manual choices.
        if (saved && !value && talker != null) {
            manualAnalysis.clearConversation(talker)
            // Keep completed evidence/results. Disabling is not an instruction to re-analyze.
        }
        saved
    }.onFailure { MoodLog.e("CHAT_SWITCH_SAVE_FAILED 本地会话开关保存失败", it) }.getOrDefault(false)
    fun report(status: String) = bridge.report(status)

    private fun reportNow(status: String) {
        val now = SystemClock.elapsedRealtime()
        val includeLog = now - lastReportAt >= 15_000L
        runCatching {
            val extras = if (includeLog) Bundle().apply { putString("host_log", MoodLog.read().takeLast(48 * 1024)) } else null
            requireNotNull(SettingsTransport.call(requireNotNull(context), "report", MoodLog.sanitize(status).take(200), extras)) {
                "运行状态上报服务不可见"
            }
            if (includeLog) lastReportAt = now
        }.onFailure {
            // Read failures already carry the full bridge error; do not flood the log per message.
            if (lastBridgeError == null) failure("BRIDGE_REPORT_FAILED", it)
        }
    }
}
