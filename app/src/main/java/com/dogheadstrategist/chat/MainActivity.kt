package com.dogheadstrategist.chat

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.dogheadstrategist.chat.core.Diagnostics
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.SettingsProvider
import com.dogheadstrategist.chat.databinding.ActivityMainBinding
import com.dogheadstrategist.chat.feature.AiStats
import com.dogheadstrategist.chat.feature.Aide
import com.dogheadstrategist.chat.feature.AideInbox
import com.dogheadstrategist.chat.feature.AidePrefs
import com.dogheadstrategist.chat.feature.AideWidget
import com.dogheadstrategist.chat.feature.UsageTierPrefs
import com.dogheadstrategist.chat.ui.BackupSetupViews
import com.dogheadstrategist.chat.ui.PrimarySetupViews
import com.dogheadstrategist.chat.ui.ReplySettingsUi
import com.dogheadstrategist.chat.feature.Intelligence
import com.dogheadstrategist.chat.feature.IntelligencePrefs
import com.dogheadstrategist.chat.feature.IntelligenceStore
import com.dogheadstrategist.chat.feature.Communicate
import com.dogheadstrategist.chat.feature.CommunicatePrefs
import com.dogheadstrategist.chat.feature.MatterItem
import com.dogheadstrategist.chat.feature.MatterStore
import com.dogheadstrategist.chat.feature.MatterType
import com.dogheadstrategist.chat.feature.RelayPrefs
import com.dogheadstrategist.chat.feature.SentryPrefs
import com.dogheadstrategist.chat.feature.SentryStore
import com.dogheadstrategist.chat.feature.StrategistFeature
import com.dogheadstrategist.chat.feature.StrategistModule
import com.dogheadstrategist.chat.feature.StrategistModules
import com.dogheadstrategist.chat.feature.ThinkingMode
import com.dogheadstrategist.chat.feature.ThinkingPrefs
import com.dogheadstrategist.chat.feature.WarReviewPrefs
import com.dogheadstrategist.chat.reply.ConnectionProbe
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import com.dogheadstrategist.chat.reply.ReplySettings
import com.dogheadstrategist.chat.sentry.SentryNotificationService
import com.google.android.material.slider.Slider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var syncingSwitches = false
    /** 回填自定义片段时置位，避免 setText 触发监听又把同一个值写回去。 */
    private var syncingThinkingCustom = false
    /** AI 设置面板（主/备用）。构造时 init 块自动绑定全部监听器，必须持有引用防被回收。 */
    private var primarySetupUi: ReplySettingsUi? = null
    private var backupSetupUi: ReplySettingsUi? = null
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pagePositions = mutableMapOf<Int, Int>()
    private var currentPage = R.id.tabHome

    /**
     * 返回键的行为分两种：二级面板回首页，已经在首页就交给系统退出。
     *
     * 面板页不能直接 finish——用户是从首页点进去的，直接退会跳出 App。
     */
    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (currentPage == R.id.sentinelPanel ||
                currentPage == R.id.relayPanel ||
                currentPage == R.id.intelPanel
            ) {
                showPage(R.id.tabHome)
                return
            }
            binding.navigation.selectedItemId = R.id.tabHome
        }
    }

    /** 设置变了就刷一下界面，但要防抖——一次保存会触发好几个 key 的回调。 */
    private val refreshAfterSettings = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        refresh()
    }

    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        binding.root.removeCallbacks(refreshAfterSettings)
        binding.root.post(refreshAfterSettings)
    }

    private val warReviewSwitchListener =
        CompoundButton.OnCheckedChangeListener { _, checked ->
            // refresh() 同步开关时会赋值 isChecked，值一变 Android 就回调这里；
            // 不拦的话 setEnabled(true) 里的 putString(KEY_PENDING, "[]") 会
            // 把待整理样本清空——表现就是「冷启动后进 app，攒着的样本没了」。
            if (syncingSwitches) return@OnCheckedChangeListener
            WarReviewPrefs.setEnabled(this, checked)
            ModulePrefs.reload(true)
            binding.badgeWarReview.text = if (checked) "已启用" else "已关闭"
            binding.textWarReviewHint.text =
                if (checked) "已开启，攒够样本后会自动整理" else "已关闭"
        }

    private fun prefs(): SharedPreferences = getSharedPreferences(ModulePrefs.FILE_NAME, 0)

    private fun loadTimeoutSettings() {
        val p = prefs()
        val failover = p.getLong(ReplySettings.KEY_FAILOVER_MS, ReplySettings.DEFAULT_FAILOVER_MS)
        val final = p.getLong(ReplySettings.KEY_FINAL_MS, 30000L)
        binding.sliderFailover.value = (failover / 1000).toFloat().coerceIn(3f, 30f)
        binding.sliderFinal.value = (final / 1000).toFloat().coerceIn(10f, 120f)
        val tokens = p.getInt(ReplySettings.KEY_MAX_TOKENS, 1000).coerceIn(200, ReplySettings.MAX_MAX_TOKENS)
        binding.sliderMaxTokens.value = tokens.toFloat()
        updateTimeoutLabels()
        binding.textTimeoutStatus.text =
            "当前：主模型等 ${failover / 1000} 秒切备用，备用等 ${final / 1000} 秒判失败，输出上限 $tokens"
    }

    private fun updateTimeoutLabels() {
        val failover = binding.sliderFailover.value.toInt()
        val final = binding.sliderFinal.value.toInt()
        binding.textFailover.text = "主模型超过 $failover 秒没有响应，就改问备用模型"
        binding.textFinal.text = "备用模型超过 $final 秒还没回，就判定失败"
        val tokens = binding.sliderMaxTokens.value.toInt()
        binding.textMaxTokens.text =
            "模型单次最多输出约 $tokens 个 token" +
                if (tokens < 800) "；群聊一次要给几条建议，调小容易被截断" else ""
    }

    private fun saveTimeoutSettings() {
        val failover = (binding.sliderFailover.value.toInt() * 1000L)
            .coerceIn(ReplySettings.MIN_FAILOVER_MS, 30000L)
        val final = (binding.sliderFinal.value.toInt() * 1000L)
            .coerceIn(ReplySettings.MIN_FINAL_MS, 120000L)
        if (final < failover) {
            binding.textTimeoutStatus.text = "兜底超时不能小于切换超时"
            return
        }
        val maxTokens = binding.sliderMaxTokens.value.toInt()
            .coerceIn(200, ReplySettings.MAX_MAX_TOKENS)
        val saved = SettingsProvider.save(this) {
            putLong(ReplySettings.KEY_FAILOVER_MS, failover)
            putLong(ReplySettings.KEY_FINAL_MS, final)
            putInt(ReplySettings.KEY_MAX_TOKENS, maxTokens)
        }
        if (!saved) {
            binding.textTimeoutStatus.text = "保存失败，请重试"
            return
        }
        ModulePrefs.reload(true)
        binding.textTimeoutStatus.text =
            "已保存：切备用 ${failover / 1000} 秒，兜底 ${final / 1000} 秒"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 底部导航只在两个 tab 之间切，二级面板统一走 showPage
        binding.navigation.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        binding.entryStrategist.setOnClickListener { binding.navigation.selectedItemId = R.id.tabReply }
        binding.entrySentinel.setOnClickListener { showPage(R.id.sentinelPanel) }
        binding.entryRelay.setOnClickListener { showPage(R.id.relayPanel) }
        binding.entryIntel.setOnClickListener { showPage(R.id.intelPanel) }
        binding.entryCommunicate.setOnClickListener { showPage(R.id.communicatePanel) }
        binding.entryAide.setOnClickListener { showPage(R.id.aidePanel) }
        binding.entryUsageTier.setOnClickListener { chooseUsageTier() }
        renderUsageTier()

        onBackPressedDispatcher.addCallback(this, backToHome)
        binding.toolbar.setNavigationOnClickListener { backToHome.handleOnBackPressed() }

        binding.buttonAiStats.setOnClickListener {
            renderStats()
            showPage(R.id.statsPanel)
        }
        binding.buttonDogStatsBack.setOnClickListener { showPage(R.id.statsPanel) }
        binding.entryPrimaryAi.setOnClickListener { showPage(R.id.aiPrimaryPanel) }
        binding.entryBackupAi.setOnClickListener { showPage(R.id.aiBackupPanel) }
        // 主/备用 AI 配置面板：ReplySettingsUi 的 init 块负责绑定输入框、拉模型、测试、保存。
        // 不实例化的话，面板里的控件全是死的——这就是「AI 设置失灵」。
        primarySetupUi = ReplySettingsUi(this, PrimarySetupViews(binding.replySettings), lifecycleScope, backup = false) { openHelp(it) }
        backupSetupUi = ReplySettingsUi(this, BackupSetupViews(binding.replySettingsBackup), lifecycleScope, backup = true) { openHelp(it) }
        binding.entryTimeout.setOnClickListener {
            loadTimeoutSettings()
            showPage(R.id.aiTimeoutPanel)
        }
        binding.sliderFailover.clearOnChangeListeners()
        binding.sliderFailover.addOnChangeListener { _, _, _ -> updateTimeoutLabels() }
        binding.sliderFinal.clearOnChangeListeners()
        binding.sliderFinal.addOnChangeListener { _, _, _ -> updateTimeoutLabels() }
        binding.sliderMaxTokens.clearOnChangeListeners()
        binding.sliderMaxTokens.addOnChangeListener { _, _, _ -> updateTimeoutLabels() }
        binding.buttonSaveTimeouts.setOnClickListener { saveTimeoutSettings() }
        binding.buttonAiProbe.setOnClickListener { probeConnection() }
        binding.buttonThinkingTemplate.setOnClickListener { chooseThinkingTemplate(false) }
        binding.buttonBackupThinkingTemplate.setOnClickListener { chooseThinkingTemplate(true) }
        binding.buttonOpenWechat.setOnClickListener { openWechat() }

        binding.buttonSentryUsersRefresh.setOnClickListener {
            val list = binding.listSentryUsers
            if (list.visibility == View.VISIBLE) {
                list.visibility = View.GONE
                binding.buttonSentryUsersRefresh.text = "查看本地用户清单"
            } else {
                renderSentryUsers()
                list.visibility = View.VISIBLE
                binding.buttonSentryUsersRefresh.text = "收起本地用户清单"
            }
        }

        binding.entryWarReview.setOnClickListener {
            renderWarReview()
            showPage(R.id.warReviewPanel)
        }
        binding.switchWarReview.setOnCheckedChangeListener(warReviewSwitchListener)
        binding.sliderWarReviewInterval.clearOnChangeListeners()
        binding.sliderWarReviewInterval.addOnChangeListener { _, value, fromUser ->
            binding.textWarReviewInterval.text = "每隔 ${value.toInt()} 小时整理一次"
            if (fromUser) {
                WarReviewPrefs.setIntervalHours(this, value.toInt())
                ModulePrefs.reload(true)
            }
        }
        binding.sliderWarReviewMinSamples.clearOnChangeListeners()
        binding.sliderWarReviewMinSamples.addOnChangeListener { _, value, fromUser ->
            binding.textWarReviewMinSamples.text = "攒够 ${value.toInt()} 条才整理"
            if (fromUser) {
                WarReviewPrefs.setMinSamples(this, value.toInt())
                ModulePrefs.reload(true)
            }
        }
        binding.buttonWarReviewNow.setOnClickListener {
            WarReviewPrefs.setForceRequested(this, true)
            ModulePrefs.reload(true)
            binding.textWarReviewHint.text = "已请求整理，微信进程下一轮检查时执行（约 1 分钟内）"
        }
        binding.buttonWarReviewRestart.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空已采集？")
                .setMessage("已经采集的样本会清掉，之后重新开始攒。画像和「已排除」的指纹库保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    WarReviewPrefs.clearSamples(this)
                    renderWarReview()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        binding.buttonWarReviewClear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空画像？")
                .setMessage("会删掉已经学到的说话方式。采集记录和已排除的建议不受影响，重新攒够样本后会再生成一份。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    WarReviewPrefs.clearProfile(this)
                    renderWarReview()
                    Toast.makeText(this, "画像已清空", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        binding.buttonStatsClear.setOnClickListener {
            AiStats.clear(this)
            renderStats()
            showPage(R.id.statsPanel)
            Toast.makeText(this, "统计已清零", Toast.LENGTH_SHORT).show()
        }
        binding.buttonExportLog.setOnClickListener { Diagnostics.export(this) }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(binding.root)

        StrategistModules.install(this)
        bindSentry()
        bindThinkingSwitches()
        bindFeatureRows()

        val selectedTab = savedInstanceState?.getInt("selected_tab")
            ?.takeIf { it in listOf(R.id.tabHome, R.id.tabReply, R.id.tabAbout) }
            ?: if (intent.getBooleanExtra("reply_tab", false)) R.id.tabReply else R.id.tabHome
        binding.navigation.selectedItemId = selectedTab

        // 从桌面小组件点进来时直接停在通知代办面板，不然用户还要自己找
        if (intent.getBooleanExtra(EXTRA_OPEN_AIDE, false)) showPage(R.id.aidePanel)
    }

    companion object {
        /** 桌面小组件点进来时带这个标记，进来直接落到通知代办面板。 */
        const val EXTRA_OPEN_AIDE = "open_aide"

        /**
         * 微信包名。
         *
         * 不直接引用 SentryNotificationService.PACKAGE——它写在私有 companion 里，
         * 外部访问不到。这里单独放一份，只用于「打开微信」这一个入口。
         */
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    // ===================== 统计 =====================

    private fun renderStats() {
        binding.statsDogList.removeAllViews()
        var any = false
        var todayCalls = 0
        var todayTokens = 0L
        var todayMsTotal = 0L
        var allCalls = 0
        var allTokens = 0L
        for (kind in AiStats.Kind.entries) {
            val today = AiStats.summary(this, kind, AiStats.Range.TODAY)
            val all = AiStats.summary(this, kind, AiStats.Range.ALL)
            todayCalls += today.calls
            todayTokens += today.totalTokens
            todayMsTotal += today.avgMs * today.calls
            allCalls += all.calls
            allTokens += all.totalTokens
            if (all.calls != 0) {
                any = true
                addDogRow(binding.statsDogList, kind, today, all)
            }
        }
        // 顶部总计：今日消耗 + 累计。数据全来自本机记录，不上传。
        binding.textStatsTotalToday.text = if (todayCalls == 0) "—" else tokens(todayTokens)
        binding.textStatsTotalDetail.text = if (todayCalls == 0) {
            "今天还没有调用"
        } else {
            "今日 $todayCalls 次调用 · 平均 " + duration(if (todayCalls > 0) todayMsTotal / todayCalls else 0)
        }
        binding.textStatsTotalAll.text =
            if (allCalls == 0) "还没有累计记录" else "累计 $allCalls 次 · " + tokens(allTokens) + " tokens"
        if (!any) addStatNote(binding.statsDogList, "还没有调用记录。用一次功能后回来看看。")
    }

    private fun addDogRow(parent: LinearLayout, kind: AiStats.Kind, today: AiStats.Summary, all: AiStats.Summary) {
        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpOf(4), dpOf(14), dpOf(4), dpOf(14))
            isClickable = true
            isFocusable = true
            setOnClickListener { renderDogStats(kind) }
        }
        row.addView(TextView(this).apply {
            text = kind.label
            textSize = 16f
            setTypeface(null, 1)
            setTextColor(primary)
        })
        val detail = StringBuilder("今日 ${today.calls} 次")
        if (today.totalTokens > 0) detail.append(" · ${tokens(today.totalTokens)} tokens")
        if (today.calls > 0) detail.append(" · 平均 ${duration(today.avgMs)}")
        detail.append("\n累计 ${all.calls} 次")
        if (all.totalTokens > 0) detail.append(" · ${tokens(all.totalTokens)} tokens")
        if (all.failed > 0) detail.append(" · 失败 ${all.failed}")
        row.addView(TextView(this).apply {
            text = detail.toString()
            textSize = 13f
            setTextColor(secondary)
            setLineSpacing(0f, 1.4f)
            setPadding(0, dpOf(5), 0, 0)
        })
        parent.addView(row)
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 1)
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.outline_subtle))
        })
    }

    private fun refreshEntryTimes() {
        val buckets = AiStats.snapshot(this)
        binding.timeStrategist.text = relativeTime(lastAt(buckets, AiStats.Kind.REPLY))
        binding.timeSentinel.text = relativeTime(lastAt(buckets, AiStats.Kind.SENTRY))
        binding.timeRelay.text = relativeTime(lastAt(buckets, AiStats.Kind.RELAY))
        binding.timeIntel.text = relativeTime(lastAt(buckets, AiStats.Kind.CARD))
        val reviewAt = WarReviewPrefs.updatedAt(this)
        binding.timeWarReview.text = if (reviewAt > 0) relativeTime(reviewAt) else "还没整理过"
        binding.timeCommunicate.text = relativeTime(lastAt(buckets, AiStats.Kind.COMMUNICATE))
        binding.timeAide.text = relativeTime(lastAt(buckets, AiStats.Kind.AIDE))
    }

    private fun lastAt(map: Map<AiStats.Kind, AiStats.Bucket>, kind: AiStats.Kind): Long =
        map[kind]?.lastAt ?: 0L

    private fun relativeTime(at: Long): String {
        if (at <= 0) return "还没用过"
        val diff = System.currentTimeMillis() - at
        if (diff < 60_000) return "刚刚"
        if (diff < 3_600_000) return "${diff / 60_000} 分钟前"
        if (diff < 86_400_000) return "${diff / 3_600_000} 小时前"
        if (diff < 604_800_000) return "${diff / 86_400_000} 天前"
        return SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(at))
    }

    private fun renderWarReview() {
        binding.switchWarReview.setOnCheckedChangeListener(null)
        binding.switchWarReview.isChecked = WarReviewPrefs.enabled(this)
        binding.switchWarReview.setOnCheckedChangeListener(warReviewSwitchListener)

        val stats = WarReviewPrefs.stats(this)
        val skipped = stats.second
        val pending = stats.third
        binding.textWarReviewStats.text = "已排除 $skipped 条 · 待整理 $pending 条"

        val updated = WarReviewPrefs.updatedAt(this)
        binding.textWarReviewUpdated.text = if (updated > 0) {
            "画像更新于 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updated))
        } else {
            ""
        }

        val profile = WarReviewPrefs.profile(this)
        binding.textWarReviewProfile.text =
            if (profile.isBlank()) "还没有画像。攒够样本后会自动整理，也可以点下面的按钮立即整理。" else profile

        binding.listWarReviewVocab.removeAllViews()
        val words = WarReviewPrefs.vocab(this)
        if (words.isEmpty()) {
            addStatNote(binding.listWarReviewVocab, "还没整理出词库，整理一次就有了。")
        } else {
            addStatNote(binding.listWarReviewVocab, "共 ${words.size} 条")
            for (item in words) addStatNote(binding.listWarReviewVocab, item.render())
        }

        binding.listWarReviewRecent.removeAllViews()
        val recent = WarReviewPrefs.recentSamples(this)
        if (recent.isEmpty()) {
            addStatNote(binding.listWarReviewRecent, "还没有。攒下的话整理后清空。")
        } else {
            for (item in recent) addStatNote(binding.listWarReviewRecent, item.text)
        }

        binding.sliderWarReviewInterval.value = WarReviewPrefs.intervalHours(this).toFloat()
            .coerceIn(binding.sliderWarReviewInterval.valueFrom, binding.sliderWarReviewInterval.valueTo)
        binding.textWarReviewInterval.text = "每隔 ${binding.sliderWarReviewInterval.value.toInt()} 小时整理一次"

        binding.sliderWarReviewMinSamples.value = WarReviewPrefs.minSamples(this).toFloat()
            .coerceIn(binding.sliderWarReviewMinSamples.valueFrom, binding.sliderWarReviewMinSamples.valueTo)
        binding.textWarReviewMinSamples.text = "攒够 ${binding.sliderWarReviewMinSamples.value.toInt()} 条才整理"
    }

    private fun renderDogStats(kind: AiStats.Kind) {
        binding.textDogStatsTitle.text = kind.label
        val body = binding.statsDogBody
        body.removeAllViews()
        for (range in AiStats.Range.entries) {
            addRangeBlock(body, range.label, AiStats.summary(this, kind, range))
        }
        body.addView(TextView(this).apply {
            text = "最近调用"
            textSize = 14f
            setTypeface(null, 1)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, dpOf(24), 0, dpOf(6))
        })
        val recent = AiStats.recent(this, kind, 10)
        if (recent.isEmpty()) {
            addStatNote(body, "暂无")
        } else {
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            for (record in recent) {
                val line = StringBuilder()
                line.append(stamp.format(Date(record.at)))
                line.append("\u3000").append(duration(record.totalMs))
                if (record.firstMs > 0) line.append("（首条 ").append(duration(record.firstMs)).append("）")
                val recordTokens = record.prompt + record.completion
                if (recordTokens > 0) line.append("\u3000").append(tokens(recordTokens)).append(" tokens")
                if (!record.ok) line.append("\u3000失败")
                addStatNote(body, line.toString())
            }
        }
        showPage(R.id.statsDogPanel)
    }

    private fun addRangeBlock(parent: LinearLayout, title: String, s: AiStats.Summary) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, 1)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setPadding(0, dpOf(18), 0, dpOf(4))
        })
        if (s.calls == 0) {
            addStatNote(parent, "暂无")
            return
        }
        val head = StringBuilder("${s.calls} 次 · 成功 ${s.ok}")
        if (s.failed > 0) head.append(" · 失败 ${s.failed}")
        head.append("（成功率 ${s.successRate}%）")
        addStatNote(parent, head.toString())

        val tokenLine = StringBuilder("token ${tokens(s.totalTokens)}")
        if (s.prompt > 0 || s.completion > 0) {
            tokenLine.append("（输入 ${tokens(s.prompt)} / 输出 ${tokens(s.completion)}）")
        }
        addStatNote(parent, tokenLine.toString())

        val latency = StringBuilder("延时 平均 ${duration(s.avgMs)}")
        if (s.maxMs > 0) latency.append(" · 最长 ${duration(s.maxMs)}")
        if (s.minMs > 0) latency.append(" · 最短 ${duration(s.minMs)}")
        addStatNote(parent, latency.toString())

        if (s.avgFirstMs > 0) {
            val firstLine = StringBuilder("首条 平均 ${duration(s.avgFirstMs)}")
            if (s.maxFirstMs > 0) firstLine.append(" · 最长 ${duration(s.maxFirstMs)}")
            addStatNote(parent, firstLine.toString())
        }
    }

    private data class KindStat(
        val label: String,
        val calls: Int,
        val avgMs: Long,
        val totalTokens: Long,
    )

    private fun addStatNote(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setLineSpacing(0f, 1.4f)
            setPadding(0, 8, 0, 8)
        })
    }

    private fun dpOf(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ===================== 哨兵 =====================

    private fun ago(at: Long): String {
        if (at <= 0) return ""
        val diff = System.currentTimeMillis() - at
        if (diff < 60_000) return "刚刚"
        if (diff < 3_600_000) return "${diff / 60_000} 分钟前"
        if (diff < 86_400_000) return "${diff / 3_600_000} 小时前"
        return "${diff / 86_400_000} 天前"
    }

    private fun duration(ms: Long): String {
        if (ms <= 0) return "—"
        if (ms < 1000) return "${ms}ms"
        if (ms >= 60_000) return "${ms / 60_000}分${(ms % 60_000) / 1000}秒"
        return String.format(Locale.CHINA, "%.1fs", ms / 1000.0)
    }

    private fun tokens(n: Long): String {
        if (n <= 0) return "—"
        if (n < 1000) return n.toString()
        if (n >= 1_000_000) return String.format(Locale.CHINA, "%.2fM", n / 1_000_000.0)
        return String.format(Locale.CHINA, "%.1fk", n / 1000.0)
    }

    /** 滑块档位换算成分钟。18 档表示不过期。 */
    private fun ttlMinutesOf(step: Int): Int = if (step >= 18) 0 else (step + 1) * 10

    private fun ttlStepOf(minutes: Int): Int =
        if (minutes <= 0) 18 else (minutes / 10 - 1).coerceIn(0, 17)

    private fun ttlLabel(step: Int): String {
        val minutes = ttlMinutesOf(step)
        if (minutes <= 0) return "永不过期"
        if (minutes < 60) return "保留 $minutes 分钟"
        if (minutes % 60 == 0) return "保留 ${minutes / 60} 小时"
        return "保留 ${minutes / 60} 小时 ${minutes % 60} 分"
    }

    private fun bindSentry() {
        binding.switchSentry.isChecked = SentryPrefs.enabled(this)
        binding.switchSentry.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            SentryPrefs.setEnabled(this, checked)
            ModulePrefs.reload(true)
            if (checked && !notificationAccessGranted()) {
                Toast.makeText(this, "还需要授予通知使用权，哨兵才能收到消息", Toast.LENGTH_LONG).show()
            }
            refreshSentryStatus()
        }
        binding.buttonSentryPermission.setOnClickListener { openSentryPermission() }

        binding.switchSentryWhitelist.isChecked = SentryPrefs.whitelistEnabled(this)
        binding.switchSentryWhitelist.setOnCheckedChangeListener { _, checked ->
            SentryPrefs.setWhitelistEnabled(this, checked)
            ModulePrefs.reload(true)
            updateSentryWhitelistSummary()
        }
        binding.buttonSentryWhitelist.setOnClickListener { chooseSentryWhitelist() }
        updateSentryWhitelistSummary()

        binding.switchSentryTestMode.isChecked = SentryPrefs.testMode(this)
        binding.switchSentryTestMode.setOnCheckedChangeListener { _, checked ->
            SentryPrefs.setTestMode(this, checked)
            ModulePrefs.reload(true)
        }

        binding.sliderSentryCount.value = SentryPrefs.count(this).toFloat()
            .coerceIn(binding.sliderSentryCount.valueFrom, binding.sliderSentryCount.valueTo)
        binding.textSentryCount.text = "最多展示 ${binding.sliderSentryCount.value.toInt()} 条"
        binding.sliderSentryCount.clearOnChangeListeners()
        binding.sliderSentryCount.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.textSentryCount.text = "最多展示 $n 条"
            if (fromUser) {
                SentryPrefs.setCount(this, n)
                ModulePrefs.reload(true)
            }
        }

        binding.sliderSentryContext.value = SentryPrefs.contextLimit(this).toFloat()
            .coerceIn(binding.sliderSentryContext.valueFrom, binding.sliderSentryContext.valueTo)
        binding.textSentryContext.text = "读取最近 ${binding.sliderSentryContext.value.toInt()} 条"
        binding.sliderSentryContext.clearOnChangeListeners()
        binding.sliderSentryContext.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.textSentryContext.text = "读取最近 $n 条"
            if (fromUser) {
                SentryPrefs.setContextLimit(this, n)
                ModulePrefs.reload(true)
            }
        }

        binding.sliderSentryCooldown.value = SentryPrefs.cooldown(this).toFloat()
            .coerceIn(binding.sliderSentryCooldown.valueFrom, binding.sliderSentryCooldown.valueTo)
        binding.textSentryCooldown.text = "冷却 ${binding.sliderSentryCooldown.value.toInt()} 秒"
        binding.sliderSentryCooldown.clearOnChangeListeners()
        binding.sliderSentryCooldown.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.textSentryCooldown.text = "冷却 $n 秒"
            if (fromUser) {
                SentryPrefs.setCooldown(this, n)
                ModulePrefs.reload(true)
            }
        }

        binding.sliderSentryTtl.value = ttlStepOf(SentryPrefs.cacheTtlMinutes(this)).toFloat()
            .coerceIn(binding.sliderSentryTtl.valueFrom, binding.sliderSentryTtl.valueTo)
        binding.textSentryTtl.text = ttlLabel(binding.sliderSentryTtl.value.toInt())
        binding.sliderSentryTtl.clearOnChangeListeners()
        binding.sliderSentryTtl.addOnChangeListener { _, value, fromUser ->
            val step = value.toInt()
            binding.textSentryTtl.text = ttlLabel(step)
            if (fromUser) {
                SentryPrefs.setCacheTtlMinutes(this, ttlMinutesOf(step))
                ModulePrefs.reload(true)
            }
        }

        binding.switchSentryOnlyUnreplied.isChecked = SentryPrefs.onlyUnreplied(this)
        binding.switchSentryOnlyUnreplied.setOnCheckedChangeListener { _, checked ->
            SentryPrefs.setOnlyUnreplied(this, checked)
            ModulePrefs.reload(true)
        }

        binding.sliderSentrySyncUsers.value = SentryPrefs.syncUsers(this).toFloat()
            .coerceIn(binding.sliderSentrySyncUsers.valueFrom, binding.sliderSentrySyncUsers.valueTo)
        binding.textSentrySyncUsers.text =
            "同步最近 ${binding.sliderSentrySyncUsers.value.toInt()} 个会话的记录（微信冻结时也能提前准备）"
        binding.sliderSentrySyncUsers.clearOnChangeListeners()
        binding.sliderSentrySyncUsers.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.textSentrySyncUsers.text = "同步最近 $n 个会话的记录（微信冻结时也能提前准备）"
            if (fromUser) {
                SentryPrefs.setSyncUsers(this, n)
                ModulePrefs.reload(true)
            }
        }

        refreshSentryStatus()
    }

    private fun refreshSentryStatus() {
        val granted = notificationAccessGranted()
        binding.buttonSentryPermission.text = if (granted) "通知使用权已授予" else "授予通知使用权"
        binding.textSentryStatus.text = if (granted) {
            if (!SentryPrefs.enabled(this)) {
                "通知使用权已授予，但哨兵处于关闭状态。"
            } else {
                "已就绪：对方发来消息时会提前准备，进入聊天后显示在右下角。"
            }
        } else {
            "还没有授予通知使用权，哨兵收不到消息。"
        }
    }

    private fun notificationAccessGranted(): Boolean = runCatching {
        val flat = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners",
        )
        flat?.contains(packageName) == true
    }.getOrDefault(false)

    private fun updateSentryWhitelistSummary() {
        val list = SentryPrefs.whitelist(this)
        binding.textSentryWhitelist.text = if (list.isEmpty()) {
            "未启用白名单时，所有会话都会被关注。"
        } else {
            val names = list.take(3).joinToString("、") { it.nick.ifEmpty { it.talker } }
            "已选 ${list.size} 人：$names" + if (list.size > 3) " 等" else ""
        }
    }

    private fun chooseSentryWhitelist() {
        val candidates = parseSessionIndex()
        if (candidates.isEmpty()) {
            Toast.makeText(this, "还没有会话索引，让微信运行一会儿再来选择", Toast.LENGTH_LONG).show()
            return
        }
        val existing = SentryPrefs.whitelist(this)
        val checked = BooleanArray(candidates.size) { index ->
            val (talker, nick) = candidates[index]
            existing.any { SentryPrefs.hits(it, talker, nick) }
        }
        val labels = candidates.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择白名单联系人")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("保存") { _, _ ->
                val picked = candidates.filterIndexed { index, _ -> checked[index] }
                    .map { SentryPrefs.WhitelistEntry(it.first, it.second) }
                SentryPrefs.setWhitelist(this, picked)
                ModulePrefs.reload(true)
                SentryStore.purgeRecords(this) { talker, nick -> SentryPrefs.allows(picked, talker, nick) }
                updateSentryWhitelistSummary()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 读微信进程写下的会话索引，给白名单选择用。解析失败就当没有。 */
    private fun parseSessionIndex(): List<Pair<String, String>> = runCatching {
        val array = org.json.JSONArray(SentryStore.sessions(this))
        val result = ArrayList<Pair<String, String>>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val talker = obj.optString("t").orEmpty()
            val nick = obj.optString("n").orEmpty()
            if (talker.isBlank() || nick.isBlank()) continue
            result.add(talker to nick)
        }
        result
    }.getOrDefault(emptyList())

    private fun openSentryPermission() {
        runCatching {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }.onFailure {
            Toast.makeText(this, "打不开设置页，请手动到 设置 → 通知 → 通知使用权", Toast.LENGTH_LONG).show()
        }
    }

    // ===================== 功能行 =====================

    private fun bindFeatureRows() {
        for (feature in StrategistFeature.entries) {
            val module = StrategistModules.of(feature)
            val status = StrategistModules.statusText(module)
            when (feature) {
                StrategistFeature.STRATEGIST -> {
                    binding.emojiStrategist.text = feature.emoji
                    binding.titleStrategist.text = feature.title
                    binding.badgeStrategist.text = status
                }
                StrategistFeature.SENTINEL -> {
                    binding.emojiSentinel.text = feature.emoji
                    binding.titleSentinel.text = feature.title
                    binding.badgeSentinel.text = status
                    binding.sentinelTitle.text = feature.emoji + " " + feature.title
                    binding.sentinelDetail.text = feature.detail
                    binding.sentinelStage.text = if (notificationAccessGranted()) {
                        if (!SentryPrefs.enabled(this)) "$status · 已关闭" else "$status · 运行中"
                    } else {
                        "$status · 待授权通知使用权"
                    }
                }
                StrategistFeature.AIDE -> {
                    binding.emojiAide.text = feature.emoji
                    binding.titleAide.text = feature.title
                    binding.badgeAide.text = status
                    binding.switchAide.isChecked = AidePrefs.enabled(this)
                    binding.switchAide.setOnCheckedChangeListener { _, checked ->
                        if (syncingSwitches) return@setOnCheckedChangeListener
                        AidePrefs.setEnabled(this, checked)
                        binding.badgeAide.text = if (checked) "已启用" else "未启用"
                    }
                    binding.aideTitle.text = feature.emoji + " " + feature.title
                    binding.aideDetail.text = feature.detail
                    bindAidePanel()
                }
                StrategistFeature.INTELLIGENCE -> {
                    binding.emojiIntel.text = feature.emoji
                    binding.titleIntel.text = feature.title
                    binding.badgeIntel.text = status
                    bindIntelPanel()
                }
                StrategistFeature.COMMUNICATE -> {
                    binding.emojiCommunicate.text = feature.emoji
                    binding.titleCommunicate.text = feature.title
                    binding.badgeCommunicate.text = status
                    binding.switchCommunicate.isChecked = CommunicatePrefs.enabled(this)
                    binding.switchCommunicate.setOnCheckedChangeListener { _, checked ->
                        if (syncingSwitches) return@setOnCheckedChangeListener
                        CommunicatePrefs.setEnabled(this, checked)
                        binding.badgeCommunicate.text = if (checked) "已启用" else "未启用"
                    }
                    binding.communicateTitle.text = feature.emoji + " " + feature.title
                    binding.communicateDetail.text = feature.detail

                    val senderChars = CommunicatePrefs.senderMaxChars(this)
                    binding.sliderCommunicateSenderChars.value = senderChars.toFloat().coerceIn(3f, 12f)
                    binding.textCommunicateSenderChars.text = "发信人名字最多 $senderChars 字"
                    binding.sliderCommunicateSenderChars.clearOnChangeListeners()
                    binding.sliderCommunicateSenderChars.addOnChangeListener { _, value, fromUser ->
                        val n = value.toInt()
                        binding.textCommunicateSenderChars.text = "发信人名字最多 $n 字"
                        if (fromUser) CommunicatePrefs.setSenderMaxChars(this, n)
                    }

                    val maxChars = CommunicatePrefs.summaryMaxChars(this)
                    binding.sliderCommunicateMaxChars.value = maxChars.toFloat().coerceIn(4f, 16f)
                    binding.textCommunicateMaxChars.text = "让 AI 把归纳压到 $maxChars 字以内"
                    binding.sliderCommunicateMaxChars.clearOnChangeListeners()
                    binding.sliderCommunicateMaxChars.addOnChangeListener { _, value, fromUser ->
                        val n = value.toInt()
                        binding.textCommunicateMaxChars.text = "让 AI 把归纳压到 $n 字以内"
                        if (fromUser) CommunicatePrefs.setSummaryMaxChars(this, n)
                    }

                    val trigger = CommunicatePrefs.minTriggerChars(this)
                    binding.sliderCommunicateTrigger.value = trigger.toFloat().coerceIn(0f, 80f)
                    binding.textCommunicateTrigger.text =
                        if (trigger <= 0) "正文至少多少字才总结 · 当前每条都总结"
                        else "正文至少多少字才总结 · 当前 $trigger 字"
                    binding.sliderCommunicateTrigger.clearOnChangeListeners()
                    binding.sliderCommunicateTrigger.addOnChangeListener { _, value, fromUser ->
                        val n = value.toInt()
                        binding.textCommunicateTrigger.text =
                            if (n <= 0) "正文至少多少字才总结 · 当前每条都总结"
                            else "正文至少多少字才总结 · 当前 $n 字"
                        if (fromUser) CommunicatePrefs.setMinTriggerChars(this, n)
                    }

                    val aiTimeout = CommunicatePrefs.aiTimeoutSec(this)
                    binding.sliderCommunicateAiTimeout.value = aiTimeout.toFloat().coerceIn(3f, 20f)
                    binding.textCommunicateAiTimeout.text = "AI 最多等 $aiTimeout 秒"
                    binding.sliderCommunicateAiTimeout.clearOnChangeListeners()
                    binding.sliderCommunicateAiTimeout.addOnChangeListener { _, value, fromUser ->
                        val sec = value.toInt()
                        binding.textCommunicateAiTimeout.text = "AI 最多等 $sec 秒"
                        if (fromUser) CommunicatePrefs.setAiTimeoutSec(this, sec)
                    }

                    val displaySec = CommunicatePrefs.displaySec(this)
                    binding.sliderCommunicateDisplay.value = displaySec.toFloat().coerceIn(3f, 30f)
                    binding.textCommunicateDisplay.text = "通知停留 $displaySec 秒"
                    binding.sliderCommunicateDisplay.clearOnChangeListeners()
                    binding.sliderCommunicateDisplay.addOnChangeListener { _, value, fromUser ->
                        val sec = value.toInt()
                        binding.textCommunicateDisplay.text = "通知停留 $sec 秒"
                        if (fromUser) CommunicatePrefs.setDisplaySec(this, sec)
                    }

                    binding.switchCommunicateRootJump.isChecked = CommunicatePrefs.useRootJump(this)
                    binding.switchCommunicateRootJump.setOnCheckedChangeListener { _, checked ->
                        CommunicatePrefs.setUseRootJump(this, checked)
                    }

                    binding.buttonCommunicateTest.setOnClickListener {
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != 0
                        ) {
                            ActivityCompat.requestPermissions(
                                this, arrayOf("android.permission.POST_NOTIFICATIONS"), 3074,
                            )
                            binding.textCommunicateStatus.text = "先在弹窗里允许通知权限，再点一次"
                            return@setOnClickListener
                        }
                        binding.textCommunicateStatus.text = "正在总结…"
                        binding.buttonCommunicateTest.isEnabled = false
                        uiScope.launch {
                            runCatching { Communicate.testPush(this@MainActivity) }
                                .onFailure {
                                    binding.textCommunicateStatus.text =
                                        "试推失败：" + (it.message ?: it.javaClass.simpleName)
                                    binding.buttonCommunicateTest.isEnabled = true
                                }
                        }
                    }
                }
                StrategistFeature.RELAY -> {
                    binding.emojiRelay.text = feature.emoji
                    binding.titleRelay.text = feature.title
                    binding.badgeRelay.text = status
                    binding.relayTitle.text = feature.emoji + " " + feature.title
                    binding.relayDetail.text = feature.detail
                    binding.relayStage.text = if (module.ready) status else "$status · 开发中"

                    val minPrefix = RelayPrefs.minPrefix(this)
                    binding.sliderRelayMinPrefix.value = minPrefix.toFloat().coerceIn(2f, 20f)
                    binding.textRelayMinPrefix.text = "$minPrefix 字起开始预测"
                    binding.sliderRelayMinPrefix.clearOnChangeListeners()
                    binding.sliderRelayMinPrefix.addOnChangeListener { _, value, fromUser ->
                        val n = value.toInt()
                        binding.textRelayMinPrefix.text = "$n 字起开始预测"
                        if (fromUser) RelayPrefs.setMinPrefix(this, n)
                    }

                    val contextLimit = RelayPrefs.contextLimit(this)
                    binding.sliderRelayContextLimit.value = contextLimit.toFloat().coerceIn(10f, 100f)
                    binding.textRelayContextLimit.text = "读取最近 $contextLimit 条聊天记录"
                    binding.sliderRelayContextLimit.clearOnChangeListeners()
                    binding.sliderRelayContextLimit.addOnChangeListener { _, value, fromUser ->
                        val n = value.toInt()
                        binding.textRelayContextLimit.text = "读取最近 $n 条聊天记录"
                        if (fromUser) RelayPrefs.setContextLimit(this, n)
                    }

                    val candidateCount = RelayPrefs.candidateCount(this)
                    binding.sliderRelayCandidates.value = candidateCount.toFloat().coerceIn(5f, 150f)
                    binding.textRelayCandidates.text = "一次生成 $candidateCount 条备选"
                    binding.sliderRelayCandidates.clearOnChangeListeners()
                    binding.sliderRelayCandidates.addOnChangeListener { _, value, fromUser ->
                        val n = value.toInt()
                        binding.textRelayCandidates.text = "一次生成 $n 条备选"
                        if (fromUser) RelayPrefs.setCandidateCount(this, n)
                    }

                    binding.switchRelay.isChecked = RelayPrefs.enabled(this)
                    binding.switchRelay.setOnCheckedChangeListener { _, checked ->
                        if (syncingSwitches) return@setOnCheckedChangeListener
                        RelayPrefs.setEnabled(this, checked)
                        if (checked) Toast.makeText(this, "已开启，重启微信后生效", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        binding.emojiWarReview.text = "🪞"
        binding.titleWarReview.text = "我的风格"
        binding.badgeWarReview.text = if (WarReviewPrefs.enabled(this)) "已启用" else "已关闭"
        bindWarReviewCollapse()
        refreshEntryTimes()
    }

    /** 人物档案面板：启用开关、两个阈值滑块、对象画像列表与详情卡。 */
    private fun bindIntelPanel() {
        binding.switchIntel.isChecked = IntelligencePrefs.enabled(this)
        binding.switchIntel.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            IntelligencePrefs.setEnabled(this, checked)
            binding.badgeIntel.text = if (checked) "已启用" else "未开启"
        }

        val minSamples = IntelligencePrefs.minSamples(this)
        binding.sliderIntelMinSamples.value = minSamples.toFloat().coerceIn(
            IntelligencePrefs.MIN_SAMPLES_FLOOR.toFloat(), IntelligencePrefs.MIN_SAMPLES_CEIL.toFloat())
        binding.textIntelMinSamples.text = "攒够 $minSamples 条新消息就整理一次"
        binding.sliderIntelMinSamples.clearOnChangeListeners()
        binding.sliderIntelMinSamples.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.textIntelMinSamples.text = "攒够 $n 条新消息就整理一次"
            if (fromUser) IntelligencePrefs.setMinSamples(this, n)
        }

        val hours = IntelligencePrefs.intervalHours(this)
        binding.sliderIntelInterval.value = hours.toFloat().coerceIn(
            IntelligencePrefs.INTERVAL_FLOOR.toFloat(), IntelligencePrefs.INTERVAL_CEIL.toFloat())
        binding.textIntelInterval.text = intervalLabel(hours)
        binding.sliderIntelInterval.clearOnChangeListeners()
        binding.sliderIntelInterval.addOnChangeListener { _, value, fromUser ->
            val h = value.toInt()
            binding.textIntelInterval.text = intervalLabel(h)
            if (fromUser) IntelligencePrefs.setIntervalHours(this, h)
        }

        binding.buttonIntelClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空全部画像")
                .setMessage("所有对象的画像都会删除，聊天记录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    Intelligence.clearAll(this)
                    renderIntelProfiles()
                    Toast.makeText(this, "已清空全部画像", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        binding.intelDetailBack.setOnClickListener { backToIntelList() }

        renderIntelProfiles()
    }

    private fun intervalLabel(hours: Int): String =
        if (hours <= 0) "只按条数，不按时间" else "至少间隔 $hours 小时"

    /** 画像列表：已生成的显示更新时间，还在攒消息的显示攒了多少条。 */
    private fun renderIntelProfiles() {
        val entries = runCatching { Intelligence.entries(this) }.getOrDefault(emptyList())
        binding.listIntelProfiles.removeAllViews()
        binding.textIntelEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val primary = ContextCompat.getColor(this, R.color.text_primary)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)
        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpOf(4), dpOf(12), dpOf(4), dpOf(12))
                isClickable = true
                isFocusable = true
                setOnClickListener { showIntelDetail(entry) }
            }
            row.addView(TextView(this).apply {
                text = entry.nick.ifBlank { entry.label.ifBlank { entry.talker } }
                textSize = 16f
                setTypeface(null, 1)
                setTextColor(primary)
            })
            row.addView(TextView(this).apply {
                text = when {
                    entry.profile.isNotBlank() && entry.updatedAt > 0 ->
                        "更新于 " + relativeTime(entry.updatedAt) +
                            if (entry.pending > 0) " · 攒了 ${entry.pending} 条待整理" else ""
                    entry.pending > 0 || entry.samples > 0 -> "还在攒消息（${entry.pending} 条待整理）"
                    else -> "还没生成画像"
                }
                textSize = 13f
                setTextColor(secondary)
                setPadding(0, dpOf(4), 0, 0)
            })
            binding.listIntelProfiles.addView(row)
        }
    }

    private fun showIntelDetail(entry: IntelligenceStore.Entry) {
        binding.listIntelProfiles.visibility = View.GONE
        binding.textIntelEmpty.visibility = View.GONE
        binding.buttonIntelClearAll.visibility = View.GONE
        binding.intelDetailCard.visibility = View.VISIBLE

        val name = entry.nick.ifBlank { entry.label.ifBlank { entry.talker } }
        binding.intelDetailTitle.text = name
        binding.intelDetailMeta.text =
            if (entry.updatedAt > 0) "更新于 " + relativeTime(entry.updatedAt) else "还没生成"
        val profile = runCatching { Intelligence.profile(this, entry.talker) }
            .getOrDefault("").ifBlank { entry.profile }
        binding.intelDetailBody.text = profile
        binding.intelDetailBody.visibility = if (profile.isBlank()) View.GONE else View.VISIBLE
        binding.intelDetailEmpty.visibility = if (profile.isBlank()) View.VISIBLE else View.GONE
        binding.intelDetailClear.setOnClickListener {
            Intelligence.clear(this, entry.talker)
            backToIntelList()
            Toast.makeText(this, "已清空「$name」的画像", Toast.LENGTH_SHORT).show()
        }
    }

    private fun backToIntelList() {
        binding.intelDetailCard.visibility = View.GONE
        binding.listIntelProfiles.visibility = View.VISIBLE
        binding.buttonIntelClearAll.visibility = View.VISIBLE
        renderIntelProfiles()
    }

    private fun bindAidePanel() {
        binding.switchAide.isChecked = AidePrefs.enabled(this)
        renderAideItems()
        renderAideTypes()
        renderAideInterval()
        renderAideKeyUsers()
        Aide.refresh(this)


        binding.buttonAideClear.setOnClickListener {
            Aide.clear(this)
            renderAideItems()
        }

        binding.buttonAideKeyUsers.setOnClickListener { editAideKeyUsers() }
    }

    /**
     * 投送间隔滑块。
     *
     * 文案和滑块值一起刷：光改值不改字，用户拖完看不到自己拖到了哪一档。
     */
    private fun renderAideInterval() {
        val minutes = AidePrefs.digestIntervalMin(this)
        binding.textAideIntervalLabel.text = "投送间隔：$minutes 分钟"
        binding.sliderAideInterval.value = minutes.toFloat().coerceIn(1f, 60f)
        binding.sliderAideInterval.clearOnChangeListeners()
        binding.sliderAideInterval.addOnChangeListener { _, value, fromUser ->
            val n = value.toInt()
            binding.textAideIntervalLabel.text = "投送间隔：$n 分钟"
            if (fromUser) AidePrefs.setDigestIntervalMin(this, n)
        }
        renderAideQueue()
    }

    /** 队列里还压着多少条没提取。用户在等的时候最想知道这个数。 */
    private fun renderAideQueue() {
        val count = runCatching { AideInbox.count(this) }.getOrDefault(0)
        binding.textAideQueue.text =
            if (count <= 0) "当前没有待处理的提醒" else "当前待处理 $count 条"
    }

    private fun renderAideKeyUsers() {
        val users = AidePrefs.keyUsers(this)
        binding.textAideKeyUsers.text =
            if (users.isEmpty()) "未设置 · 所有事项都会记下来" else users.joinToString("、")
    }

    /**
     * 关键用户名单的编辑框。
     *
     * 用顿号分隔而不是做多选列表：名单常常是从群里复制过来的一串名字，
     * 粘贴进输入框比一个个勾选快得多。
     */
    private fun editAideKeyUsers() {
        val input = EditText(this).apply {
            setText(AidePrefs.keyUsers(this@MainActivity).joinToString("、"))
            hint = "用顿号或逗号分隔，如：张三、李四"
            setSingleLine(false)
            minLines = 2
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                input,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        AlertDialog.Builder(this)
            .setTitle("关键用户")
            .setMessage("只记和这些人相关的事项。留空则全部都记。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                AidePrefs.setKeyUsers(
                    this,
                    input.text.toString().split('、', ',', '，', ';', '；', '\n'),
                )
                ModulePrefs.reload(true)
                renderAideKeyUsers()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 用量档位：一个入口统一改掉八项参数。
     *
     * 逐项调很容易调出互相矛盾的状态（上下文拉到最大、间隔又设成 1 分钟），
     * 档位是给不想逐项琢磨的人的快捷方式。
     */
    private fun renderUsageTier() {
        val tier = runCatching { UsageTierPrefs.current(this) }.getOrNull() ?: return
        binding.badgeUsageTier.text = tier.label
        binding.taglineUsageTier.text = tier.hint
    }

    private fun chooseUsageTier() {
        val tiers = UsageTierPrefs.Tier.entries.filter { it != UsageTierPrefs.Tier.CUSTOM }
        val current = runCatching { UsageTierPrefs.current(this) }.getOrNull()
        val labels = tiers.map { it.label + "　" + it.hint }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("用量档位")
            .setSingleChoiceItems(labels, tiers.indexOf(current)) { dialog, which ->
                UsageTierPrefs.apply(this, tiers[which])
                ModulePrefs.reload(true)
                renderUsageTier()
                // 档位会改掉滑块背后的值，重新绑定一次面板，免得界面和实际设置对不上
                bindFeatureRows()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }


    private fun renderAideTypes() {
        val types = MatterStore.types(this)
        binding.listAideTypes.removeAllViews()
        for (type in types) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(View(this).apply {
                setBackgroundColor(
                    runCatching { Color.parseColor(type.color) }.getOrDefault(0xFF607D8B.toInt())
                )
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(32)).apply { rightMargin = dp(12) }
            })
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            column.addView(TextView(this).apply {
                text = type.emoji + " " + type.title + if (type.preset) "" else " · 自定义"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 15f
            })
            column.addView(TextView(this).apply {
                val desc = type.desc.ifBlank { "（没有描述，模型只能靠标题猜）" }
                text = desc + if (type.expireHours > 0) " · ${type.expireHours} 小时后过期" else " · 不过期"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(column)
            row.addView(TextView(this).apply {
                text = "编辑"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(dp(10), dp(6), 0, dp(6))
                setOnClickListener { showAideTypeDialog(type) }
            })
            if (type.preset) {
                row.addView(TextView(this).apply {
                    text = "恢复"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    textSize = 12f
                    setPadding(dp(10), dp(6), 0, dp(6))
                    setOnClickListener {
                        MatterStore.resetType(this@MainActivity, type.key)
                        renderAideTypes()
                    }
                })
            } else {
                row.addView(TextView(this).apply {
                    text = "删除"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_error))
                    textSize = 12f
                    setPadding(dp(10), dp(6), 0, dp(6))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("删除「${type.title}」？")
                            .setMessage("已经记下的这类要事会保留，但之后模型不再往这一类归。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ ->
                                MatterStore.deleteType(this@MainActivity, type.key)
                                renderAideTypes()
                            }
                            .show()
                    }
                })
            }
            binding.listAideTypes.addView(row)
        }
        binding.buttonAideAddType.setOnClickListener { showAideTypeDialog(null) }
    }

    /** 预设色板。和 Pinme 同一套六色，够区分，再多就选不过来了。 */
    private val AIDE_PRESET_COLORS = listOf(
        "#FFC107", // 黄
        "#4CAF50", // 绿
        "#2196F3", // 蓝
        "#FF5722", // 橙
        "#E91E63", // 粉
        "#9C27B0", // 紫
    )

    /** 圆形色块。选中描三重边，未选中描一重淡边。 */
    private fun aideSwatchDrawable(hex: String, selected: Boolean): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(runCatching { Color.parseColor(hex) }.getOrDefault(0xFF607D8B.toInt()))
            setStroke(
                if (selected) dp(3) else dp(1),
                ContextCompat.getColor(
                    this@MainActivity,
                    if (selected) R.color.text_primary else R.color.outline_subtle,
                ),
            )
        }

    private fun showAideTypeDialog(existing: MatterType?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun field(label: String, value: String, placeholder: String = ""): EditText {
            container.addView(TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
            })
            return EditText(this).apply {
                setText(value)
                if (placeholder.isNotEmpty()) hint = placeholder
                setPadding(0, dp(4), 0, dp(4))
            }.also { container.addView(it) }
        }

        val titleField = field("标题", existing?.title ?: "", "例如「航班」「会议」")
        val descField = field("描述", existing?.desc ?: "", "告诉模型这一类长什么样，越具体越准")
        // 示例决定 content 的长短和格式，比描述更直接影响输出质量
        val exampleField = field("输出示例", existing?.outputExample ?: "", "例如「明天15:00 部门例会」")
        val emojiField = field("图标", existing?.emoji ?: "📌", "一个 emoji")

        // 颜色：一排色块点选。选中的加粗描边，一眼能看出当前选的是哪个。
        container.addView(TextView(this).apply {
            text = "颜色"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
        })
        var selectedColor = existing?.color ?: AIDE_PRESET_COLORS.first()
        val swatches = ArrayList<View>()
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        fun refreshSwatches() {
            for (index in AIDE_PRESET_COLORS.indices) {
                val hex = AIDE_PRESET_COLORS[index]
                swatches.getOrNull(index)?.background =
                    aideSwatchDrawable(hex, hex.equals(selectedColor, ignoreCase = true))
            }
        }
        for (hex in AIDE_PRESET_COLORS) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(10) }
                background = aideSwatchDrawable(hex, hex.equals(selectedColor, ignoreCase = true))
                setOnClickListener {
                    selectedColor = hex
                    refreshSwatches()
                }
            }
            swatches.add(dot)
            colorRow.addView(dot)
        }
        container.addView(colorRow)
        val colorField = field("自定义颜色", "", "十六进制，如 #2196F3，留空用上面的色块")

        val expireField = field(
            "几小时后过期",
            (existing?.expireHours ?: 24).toString(),
            "0 表示不过期",
        )
        expireField.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加类型" else "编辑类型")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val title = titleField.text.toString().trim()
                if (title.isEmpty()) {
                    toast("标题不能为空")
                    return@setPositiveButton
                }
                val emoji = emojiField.text.toString().trim().take(2).ifBlank { "📌" }
                val expire = expireField.text.toString().trim().toIntOrNull()?.coerceIn(0, 8760) ?: 24
                val desc = descField.text.toString().trim()
                val example = exampleField.text.toString().trim()
                // 自定义输入填了就用它，没填就用上面点中的色块。
                // parseColor 遇到非法值会直接抛，写错了退回默认灰而不是存个错颜色
                val typed = colorField.text.toString().trim()
                val color = if (typed.isEmpty()) {
                    selectedColor
                } else {
                    runCatching {
                        Color.parseColor(typed)
                        typed
                    }.getOrDefault("#795548")
                }
                if (existing == null) {
                    // 自定义类型的 key 用时间戳，避免和预置撞、也不用让用户想标识
                    val key = "custom_" + System.currentTimeMillis().toString().takeLast(9)
                    MatterStore.addType(
                        this,
                        MatterType(
                            key = key,
                            title = title,
                            desc = desc,
                            outputExample = example,
                            emoji = emoji,
                            color = color,
                            expireHours = expire,
                            preset = false,
                        ),
                    )
                } else {
                    MatterStore.updateType(
                        this,
                        existing.copy(
                            title = title,
                            desc = desc,
                            outputExample = example,
                            emoji = emoji,
                            color = color,
                            expireHours = expire,
                        ),
                    )
                }
                renderAideTypes()
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun renderAideItems() {
        val items = Aide.items(this)
        renderAideQueue()
        binding.listAideItems.removeAllViews()
        binding.textAideEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.buttonAideClear.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) return
        val typeMap = MatterStore.types(this).associateBy { it.key }
        for (item in items) {
            val type = typeMap[item.typeKey]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(View(this).apply {
                setBackgroundColor(
                    runCatching { Color.parseColor(type?.color ?: "#607D8B") }
                        .getOrDefault(0xFF607D8B.toInt())
                )
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(28)).apply { rightMargin = dp(12) }
            })
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            column.addView(TextView(this).apply {
                text = item.emoji + " " + (type?.title ?: item.typeKey)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
            })
            column.addView(TextView(this).apply {
                text = item.content
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 15f
                setPadding(0, dp(2), 0, 0)
            })
            column.addView(TextView(this).apply {
                text = item.source + " · " + formatAideTime(item.createdAt)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 11f
                setPadding(0, dp(2), 0, 0)
            })
            row.addView(column)
            row.addView(TextView(this).apply {
                text = "删除"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 12f
                setPadding(dp(12), dp(6), 0, dp(6))
                setOnClickListener {
                    Aide.delete(this@MainActivity, item.id)
                    renderAideItems()
                }
            })
            binding.listAideItems.addView(row)
        }
    }

    private fun formatAideTime(millis: Long): String {
        if (millis <= 0) return ""
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
        val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
        val hm = String.format(
            "%02d:%02d",
            target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE),
        )
        if (sameYear && dayDiff == 0) return "今天 $hm"
        if (sameYear && dayDiff == 1) return "昨天 $hm"
        return String.format(
            "%02d-%02d %s",
            target.get(Calendar.MONTH) + 1, target.get(Calendar.DAY_OF_MONTH), hm,
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ===================== 思考模式 / 连接测试 =====================

    /**
     * 模板按钮文字、自定义输入框显隐、下方状态提示，都跟着当前选中的片段走。
     *
     * 以前按钮文字是 XML 里写死的「关闭片段：不注入」，代码从不更新它——用户选了
     * 自定义、填了 JSON，界面还显示「不注入」，自然以为没生效。
     */
    private fun renderThinkingTemplate(backup: Boolean = false) {
        val id = if (backup) ThinkingPrefs.backupTemplate(this) else ThinkingPrefs.template(this)
        val template = ThinkingMode.Template.of(id)
        val custom = if (backup) ThinkingPrefs.backupCustom(this) else ThinkingPrefs.custom(this)
        val isCustom = template == ThinkingMode.Template.CUSTOM
        if (backup) {
            binding.buttonBackupThinkingTemplate.text = "关闭片段：" + template.label
            binding.backupThinkingCustomLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
        } else {
            binding.buttonThinkingTemplate.text = "关闭片段：" + template.label
            binding.thinkingCustomLayout.visibility = if (isCustom) View.VISIBLE else View.GONE
            binding.textThinkingSupport.text = when {
                template == ThinkingMode.Template.NONE ->
                    "当前不注入任何片段，请求体保持原样。"
                isCustom && custom.isBlank() ->
                    "已选「自定义」，但片段还是空的——填一段 JSON 才会生效。"
                isCustom ->
                    "当前用自定义片段，会合并进请求体顶层。"
                else ->
                    "当前用 ${template.label}（${template.hint}），会合并进请求体顶层。"
            }
        }
    }

    /** 「我的风格」页的三个折叠区：点标题展开/收起，箭头跟着变。 */
    private fun bindWarReviewCollapse() {
        listOf(
            binding.headerWarReviewProfile to binding.boxWarReviewProfile,
            binding.headerWarReviewVocab to binding.boxWarReviewVocab,
            binding.headerWarReviewRecent to binding.boxWarReviewRecent,
        ).forEach { (header, box) ->
            fun label(open: Boolean) =
                header.text.toString().removeSuffix(" ▾").removeSuffix(" ▸") + if (open) " ▾" else " ▸"
            header.text = label(box.visibility == View.VISIBLE)
            header.setOnClickListener {
                val open = box.visibility == View.VISIBLE
                box.visibility = if (open) View.GONE else View.VISIBLE
                header.text = label(!open)
            }
        }
    }

    private fun bindThinkingSwitches() {
        binding.switchThinkingReply.isChecked = ThinkingPrefs.reply(this)
        binding.switchThinkingReply.setOnCheckedChangeListener { _, value ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            ThinkingPrefs.setReply(this, value)
            ModulePrefs.reload(true)
            if (value) Toast.makeText(this, "已关闭帮我回复的思考过程，响应会更快", Toast.LENGTH_SHORT).show()
        }
        binding.switchThinkingTopics.isChecked = ThinkingPrefs.topics(this)
        binding.switchThinkingTopics.setOnCheckedChangeListener { _, value ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            ThinkingPrefs.setTopics(this, value)
            ModulePrefs.reload(true)
            if (value) Toast.makeText(this, "已关闭找话题的思考过程，响应会更快", Toast.LENGTH_SHORT).show()
        }
        binding.switchThinkingRelay.isChecked = ThinkingPrefs.relay(this)
        binding.switchThinkingRelay.setOnCheckedChangeListener { _, value ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            ThinkingPrefs.setRelay(this, value)
            ModulePrefs.reload(true)
            if (value) Toast.makeText(this, "已关闭自动填充的思考过程，灰字会更快出来", Toast.LENGTH_SHORT).show()
        }
        binding.switchThinkingAide.isChecked = ThinkingPrefs.aide(this)
        binding.switchThinkingAide.setOnCheckedChangeListener { _, value ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            ThinkingPrefs.setAide(this, value)
            ModulePrefs.reload(true)
            if (value) Toast.makeText(this, "已关闭通知代办的思考过程，事项提取会更快", Toast.LENGTH_SHORT).show()
        }
        binding.switchThinkingIntel.isChecked = ThinkingPrefs.intel(this)
        binding.switchThinkingIntel.setOnCheckedChangeListener { _, value ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            ThinkingPrefs.setIntel(this, value)
            ModulePrefs.reload(true)
            if (value) Toast.makeText(this, "已关闭人物档案的思考过程，画像整理会更快", Toast.LENGTH_SHORT).show()
        }
        binding.switchThinkingCommunicate.isChecked = ThinkingPrefs.communicate(this)
        binding.switchThinkingCommunicate.setOnCheckedChangeListener { _, value ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            ThinkingPrefs.setCommunicate(this, value)
            ModulePrefs.reload(true)
            if (value) Toast.makeText(this, "已关闭通知摘要的思考过程，归纳会更快", Toast.LENGTH_SHORT).show()
        }
        binding.switchThinkingWarReview.isChecked = ThinkingPrefs.warReview(this)
        binding.switchThinkingWarReview.setOnCheckedChangeListener { _, value ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            ThinkingPrefs.setWarReview(this, value)
            ModulePrefs.reload(true)
            if (value) Toast.makeText(this, "已关闭我的风格的思考过程，画像整理会更快", Toast.LENGTH_SHORT).show()
        }
        // 自定义片段输入框以前只有回填、没有保存：用户填完就丢。
        // 现在边输边存，不需要额外的保存按钮。
        binding.inputThinkingCustom.doAfterTextChanged { editable ->
            if (syncingThinkingCustom) return@doAfterTextChanged
            val text = editable?.toString().orEmpty()
            ThinkingPrefs.setCustom(this, text)
            // 填了内容却还停在「不注入」时自动切到「自定义」，否则片段存了也不会被用
            if (text.isNotBlank()) {
                val cur = ThinkingPrefs.template(this)
                if (cur.isBlank() || cur == ThinkingMode.Template.NONE.id) {
                    ThinkingPrefs.setTemplate(this, ThinkingMode.Template.CUSTOM.id)
                    ModulePrefs.reload(true)
                }
            }
            renderThinkingTemplate(false)
        }
        binding.inputBackupThinkingCustom.doAfterTextChanged { editable ->
            if (syncingThinkingCustom) return@doAfterTextChanged
            val text = editable?.toString().orEmpty()
            ThinkingPrefs.setBackupCustom(this, text)
            if (text.isNotBlank()) {
                val cur = ThinkingPrefs.backupTemplate(this)
                if (cur.isBlank() || cur == ThinkingMode.Template.NONE.id) {
                    ThinkingPrefs.setBackupTemplate(this, ThinkingMode.Template.CUSTOM.id)
                    ModulePrefs.reload(true)
                }
            }
            renderThinkingTemplate(true)
        }
        renderThinkingTemplate(false)
        renderThinkingTemplate(true)
    }

    private fun probeConnection() {
        // 直接读本机 SharedPreferences，不走 ModulePrefs 的跨进程快照：
        // 快照是异步加载的，用户刚保存完就点检查时可能还没到位，
        // 于是明明配好了却提示「请先保存地址、API Key 和模型名」。
        val settings = ReplySettings.load(this)
        if (settings == null || !settings.isConfigured) {
            binding.textAiProbeResult.text = "请先保存地址、API Key 和模型名"
            return
        }
        binding.buttonAiProbe.isEnabled = false
        binding.textAiProbeResult.text = "测试中…（会发两次请求，约 5~30 秒）"
        uiScope.launch {
            val probe = runCatching {
                ReplyHttpClient().probeConnection(
                    settings,
                    ThinkingPrefs.template(this@MainActivity),
                    ThinkingPrefs.custom(this@MainActivity),
                )
            }.getOrNull()
            binding.buttonAiProbe.isEnabled = true
            binding.textAiProbeResult.text = if (probe == null) "测试失败，看日志确认原因" else describeProbe(probe)
        }
    }

    private fun describeProbe(probe: ConnectionProbe): String {
        val sb = StringBuilder()
        if (probe.ok) {
            sb.append("基本可用 ").append(probe.latencyMs).append("ms")
        } else {
            sb.append("不可用：").append(probe.error)
        }
        sb.append("\n")
        if (probe.streamOk) {
            sb.append("流式可用（首包 ").append(probe.streamLatencyMs).append("ms，自动填充会更快）")
        } else {
            sb.append("流式不可用：").append(probe.streamError).append("（自动填充只能等整包）")
        }
        sb.append("\n")
        if (probe.reasoningChars > 0) {
            sb.append("模型仍在思考（推理 ").append(probe.reasoningChars).append(" 字）")
        } else {
            sb.append("没有思考输出，响应应该很快")
        }
        return sb.toString()
    }

    private fun chooseThinkingTemplate(backup: Boolean = false) {
        val templates = ThinkingMode.Template.entries
        val effective = if (backup) {
            ThinkingPrefs.backupTemplate(this).ifBlank { ThinkingPrefs.template(this) }
        } else {
            ThinkingPrefs.template(this)
        }
        val current = templates.indexOfFirst { it.id == effective }.coerceAtLeast(0)
        val labels = templates.map { it.label + "\u3000" + it.hint }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (backup) "备用模型关闭思考的片段" else "关闭思考的片段")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked = templates[which]
                if (backup) ThinkingPrefs.setBackupTemplate(this, picked.id)
                else ThinkingPrefs.setTemplate(this, picked.id)
                ModulePrefs.reload(true)
                // 选到「自定义」时把已存的片段填进输入框，省得用户从零写
                if (picked == ThinkingMode.Template.CUSTOM) {
                    syncingThinkingCustom = true
                    if (backup) {
                        binding.inputBackupThinkingCustom.setText(ThinkingPrefs.backupCustom(this))
                    } else {
                        binding.inputThinkingCustom.setText(ThinkingPrefs.custom(this))
                    }
                    syncingThinkingCustom = false
                }
                renderThinkingTemplate(backup)
                dialog.dismiss()
            }
            .setNeutralButton(if (backup) "恢复沿用主 AI" else "取消") { _, _ ->
                if (backup) {
                    ThinkingPrefs.setBackupTemplate(this, "")
                    ModulePrefs.reload(true)
                }
            }
            .show()
    }

    private fun openHelp(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
            )
        }.onFailure {
            Toast.makeText(this, "未找到浏览器，请先安装浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    // ===================== 页面切换 =====================

    private fun showPage(id: Int) {
        if (id == R.id.tabHome) refreshEntryTimes()
        pagePositions[currentPage] = binding.pageScroll.scrollY
        currentFocus?.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        currentPage = id
        backToHome.isEnabled = id != R.id.tabHome

        binding.homePanel.visibility = if (id == R.id.tabHome) View.VISIBLE else View.GONE
        binding.replyPanel.visibility = if (id == R.id.tabReply) View.VISIBLE else View.GONE
        binding.aiPrimaryPanel.visibility = if (id == R.id.aiPrimaryPanel) View.VISIBLE else View.GONE
        binding.aiBackupPanel.visibility = if (id == R.id.aiBackupPanel) View.VISIBLE else View.GONE
        binding.aiTimeoutPanel.visibility = if (id == R.id.aiTimeoutPanel) View.VISIBLE else View.GONE
        binding.intelPanel.visibility = if (id == R.id.intelPanel) View.VISIBLE else View.GONE
        if (id == R.id.intelPanel) {
            renderIntelProfiles()
            // 名字来自后台缓存，进页面主动刷一次：用户刚在微信里改完身份也能看到
            uiScope.launch {
                runCatching { Intelligence.refreshLabels(this@MainActivity) }
                renderIntelProfiles()
            }
        }
        binding.sentinelPanel.visibility = if (id == R.id.sentinelPanel) View.VISIBLE else View.GONE
        binding.relayPanel.visibility = if (id == R.id.relayPanel) View.VISIBLE else View.GONE
        binding.statsPanel.visibility = if (id == R.id.statsPanel) View.VISIBLE else View.GONE
        binding.statsDogPanel.visibility = if (id == R.id.statsDogPanel) View.VISIBLE else View.GONE
        binding.warReviewPanel.visibility = if (id == R.id.warReviewPanel) View.VISIBLE else View.GONE
        binding.communicatePanel.visibility = if (id == R.id.communicatePanel) View.VISIBLE else View.GONE
        binding.aidePanel.visibility = if (id == R.id.aidePanel) View.VISIBLE else View.GONE
        binding.aboutPanel.visibility = if (id == R.id.tabAbout) View.VISIBLE else View.GONE

        val detailPage = id == R.id.sentinelPanel || id == R.id.relayPanel ||
            id == R.id.intelPanel || id == R.id.statsPanel || id == R.id.statsDogPanel ||
            id == R.id.warReviewPanel || id == R.id.communicatePanel || id == R.id.aidePanel ||
            id == R.id.aiPrimaryPanel || id == R.id.aiBackupPanel || id == R.id.aiTimeoutPanel
        binding.toolbar.navigationIcon =
            if (detailPage) ContextCompat.getDrawable(this, R.drawable.ic_back) else null
        binding.toolbar.navigationContentDescription = "返回上一页"

        binding.pageScroll.post {
            if (currentPage == id) binding.pageScroll.scrollTo(0, pagePositions[id] ?: 0)
        }
    }

    private fun scrollTo(view: View) {
        binding.pageScroll.post {
            val content = binding.pageScroll.getChildAt(0) as? ViewGroup ?: return@post
            val bounds = Rect()
            view.getDrawingRect(bounds)
            content.offsetDescendantRectToMyCoords(view, bounds)
            binding.pageScroll.smoothScrollTo(0, bounds.top)
        }
    }

    private fun openWechat() {
        runCatching {
            val intent = packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
                ?: throw IllegalArgumentException("未找到当前空间的微信")
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, "无法打开微信，请检查是否安装在同一空间", Toast.LENGTH_LONG).show()
        }
    }

    // ===================== 生命周期 =====================

    override fun onResume() {
        runCatching { refreshSentryStatus() }
        super.onResume()
        refresh()
        if (currentPage == R.id.intelPanel) renderIntelProfiles()
        // 小组件不跨进程实时刷（别的 uid 更新会被系统按包校验拒掉），
        // 打开 App 就成了最自然的刷新时机
        runCatching { AideWidget.refresh(this) }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(stateListener)
        getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(stateListener)
    }

    override fun onStop() {
        binding.root.removeCallbacks(refreshAfterSettings)
        getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(stateListener)
        getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", binding.navigation.selectedItemId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    /**
     * 设置变了之后的统一刷新。
     *
     * 用 [syncingSwitches] 把程序化赋值和用户点击区分开——否则每刷一次就会
     * 触发一遍开关回调，又把 prefs 写回去，白跑一轮。
     */
    /** 「微信模块」卡片的状态文字：桥可用说明模块进程活着，否则提示去激活。 */
    private fun renderFrameworkStatus() {
        val ok = runCatching { ModulePrefs.bridgeAvailable }.getOrDefault(false)
        binding.textFrameworkStatus.text = if (ok) {
            "已连接，微信里可以使用各功能。"
        } else {
            "还没连上微信。激活模块并重启微信后再回来。"
        }
    }

    private fun refresh() {
        if (!::binding.isInitialized) return
        if (isFinishing || isDestroyed) return
        syncingSwitches = true
        try {
            runCatching { refreshSentryStatus() }
            runCatching { renderFrameworkStatus() }
            runCatching { refreshEntryTimes() }
            runCatching { renderWarReview() }
            runCatching { renderAideItems() }
            runCatching { renderAideTypes() }
            runCatching { binding.switchThinkingReply.isChecked = ThinkingPrefs.reply(this) }
            runCatching { binding.switchThinkingTopics.isChecked = ThinkingPrefs.topics(this) }
            runCatching { binding.switchThinkingRelay.isChecked = ThinkingPrefs.relay(this) }
            runCatching { binding.switchThinkingAide.isChecked = ThinkingPrefs.aide(this) }
            runCatching { binding.switchThinkingIntel.isChecked = ThinkingPrefs.intel(this) }
            runCatching { binding.switchThinkingCommunicate.isChecked = ThinkingPrefs.communicate(this) }
            runCatching { binding.switchThinkingWarReview.isChecked = ThinkingPrefs.warReview(this) }
            runCatching { binding.switchAide.isChecked = AidePrefs.enabled(this) }
            runCatching { binding.switchSentry.isChecked = SentryPrefs.enabled(this) }
            runCatching { binding.switchCommunicate.isChecked = CommunicatePrefs.enabled(this) }
            runCatching { binding.switchRelay.isChecked = RelayPrefs.enabled(this) }
            runCatching { binding.switchWarReview.isChecked = WarReviewPrefs.enabled(this) }
        } finally {
            syncingSwitches = false
        }
    }

    /**
     * 本地用户清单。
     *
     * 记录由微信进程写入，这里只负责把 JSON 摊平成人能看的一行行。
     * 字段名以微信进程写下的格式为准，认不出来时退回 talker，不丢条目。
     */
    private fun renderSentryUsers() {
        val list = binding.listSentryUsers
        list.removeAllViews()
        val array = runCatching { org.json.JSONArray(SentryStore.records(this)) }.getOrNull()
        if (array == null || array.length() == 0) {
            addStatNote(list, "还没有本地记录。让微信收到几条消息再来看。")
            return
        }
        addStatNote(list, "共 ${array.length()} 条记录")
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val talker = obj.optString("t").ifBlank { obj.optString("talker") }
            val nick = obj.optString("n").ifBlank { obj.optString("nick") }
            val at = obj.optLong("at").takeIf { it > 0 } ?: obj.optLong("updatedAt")
            val who = nick.ifBlank { talker }.ifBlank { "（未知会话）" }
            val whenText = if (at > 0) stamp.format(Date(at)) else "—"
            addStatNote(list, "$who\u3000$whenText")
        }
    }
}