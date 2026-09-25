package dev.jev.wechatmood

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.ApiSettings
import dev.jev.wechatmood.core.ApiProfiles
import dev.jev.wechatmood.core.JevProvider
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.core.SettingsProvider
import dev.jev.wechatmood.databinding.ActivityMainBinding
import dev.jev.wechatmood.updates.UpdateNotice
import dev.jev.wechatmood.ui.ProbeState
import dev.jev.wechatmood.ui.SetupAction
import dev.jev.wechatmood.ui.SetupPresenter
import dev.jev.wechatmood.ui.StatusTone
import kotlinx.coroutines.*
import androidx.core.widget.doAfterTextChanged

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var updateNotice: UpdateNotice
    private var syncingSwitches = false
    private var selectedProvider = JevProvider.TYPESAFE
    private var bindingInputs = false
    private var probeState = ProbeState.UNTESTED
    private val refreshAfterSettings = Runnable { if (!isFinishing && !isDestroyed) refresh() }
    private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Saving one profile updates several keys. Render once after the entire edit.
        binding.root.removeCallbacks(refreshAfterSettings)
        binding.root.post(refreshAfterSettings)
    }
    // No data class: accidental logging must not print a key. Drafts never cross channels.
    private class ApiDraft(val endpoint: String, val key: String, val model: String)
    private val drafts = mutableMapOf<JevProvider, ApiDraft>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.title = getString(R.string.app_title_version, BuildConfig.VERSION_NAME)
        dev.jev.wechatmood.ui.ReplySettingsUi(this, binding.replySettings, uiScope, ::openHelp)
        binding.modelTabs.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                binding.emotionPanel.visibility = if (id == R.id.tabEmotion) View.VISIBLE else View.GONE
                binding.replySettings.root.visibility = if (id == R.id.tabReply) View.VISIBLE else View.GONE
                binding.pageScroll.scrollTo(0, 0)
            }
        }
        if (savedInstanceState?.getBoolean("reply_tab") == true || intent.getBooleanExtra("reply_tab", false))
            binding.modelTabs.check(R.id.tabReply)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        MoodLog.init(this)
        MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(this)}")
        // Makes the settings provider visible to WeChat on Android 11+.
        // The provider validates the caller UID before sharing settings with WeChat.
        runCatching {
            grantUriPermission("com.tencent.mm", SettingsProvider.URI, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onSuccess { MoodLog.i("BRIDGE_VISIBILITY_GRANTED 微信读取授权已授予") }
            .onFailure { MoodLog.e("BRIDGE_VISIBILITY_GRANT_FAILED", it) }
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        prefs.all.filterKeys { it == ModulePrefs.KEY_API_KEY || it.endsWith("_key") }
            .values.filterIsInstance<String>().forEach(MoodLog::protect)
        ModulePrefs.init(this)
        SettingsProvider.publish(this)
        val savedEndpoint = prefs.getString(ModulePrefs.KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT).orEmpty()
        selectedProvider = JevProvider.resolve(prefs.getString(ModulePrefs.KEY_API_PROVIDER, null), savedEndpoint)
        drafts[selectedProvider] = ApiDraft(savedEndpoint, prefs.getString(ModulePrefs.KEY_API_KEY, "").orEmpty(),
            prefs.getString(ModulePrefs.KEY_API_MODEL, "").orEmpty())
        binding.inputProvider.setSimpleItems(JevProvider.entries.map { it.label }.toTypedArray())
        showProvider()
        binding.inputProvider.setOnItemClickListener { _, _, position, _ ->
            drafts[selectedProvider] = ApiDraft(binding.inputApiBase.text.toString(), binding.inputApiKey.text.toString(),
                binding.inputApiModel.text.toString())
            selectedProvider = JevProvider.entries[position]
            showProvider()
        }
        binding.buttonGetKey.setOnClickListener { selectedProvider.keyUrl?.let(::openHelp) }
        binding.buttonProviderDocs.setOnClickListener { openHelp(selectedProvider.docsUrl) }
        listOf(binding.inputApiBase, binding.inputApiKey, binding.inputApiModel).forEach { field ->
            field.doAfterTextChanged {
                if (!bindingInputs) {
                    probeState = ProbeState.UNTESTED
                    binding.textTestResult.visibility = View.GONE
                    renderOverview()
                }
            }
        }
        binding.buttonSaveApi.setOnClickListener {
            if (saveApiSettings()) showResult("配置已保存\n可以继续检测连接，确认当前渠道和 Key 是否可用。", StatusTone.NEUTRAL)
        }
        binding.switchExplore.isChecked = prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false)
        binding.switchExplore.setOnCheckedChangeListener { _, value ->
            if (!syncingSwitches) {
                save(ModulePrefs.KEY_EXPLORE, value)
                Toast.makeText(this, "重新启动微信后生效", Toast.LENGTH_SHORT).show()
            }
        }
        binding.buttonDebug.setOnClickListener {
            val open = binding.debugPanel.visibility != View.VISIBLE
            binding.debugPanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.buttonDebug.text = if (open) "收起详细排查信息" else "查看详细排查信息"
            if (open) binding.textLog.text = Diagnostics.collect(this)
        }
        binding.buttonProviderHelp.setOnClickListener {
            val open = binding.providerHelpPanel.visibility != View.VISIBLE
            binding.providerHelpPanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.buttonProviderHelp.text = if (open) "收起 Key 获取方法" else "没有 Key？查看获取方法"
        }
        binding.buttonSetupGuide.setOnClickListener { showSetupGuide(binding.setupGuidePanel.visibility != View.VISIBLE) }
        binding.buttonOpenWechat.setOnClickListener { openWechat() }
        binding.buttonJumpModel.setOnClickListener { scrollTo(binding.modelSection) }
        binding.buttonNextStep.setOnClickListener {
            when (overview().action) {
                SetupAction.CONFIGURE -> { scrollTo(binding.modelSection); binding.inputApiKey.requestFocus() }
                SetupAction.TEST -> {
                    if (probeState == ProbeState.FAILED) scrollTo(binding.textTestResult)
                    else { scrollTo(binding.modelSection); testModel() }
                }
                SetupAction.GUIDE -> { showSetupGuide(true); scrollTo(binding.wechatSection) }
                SetupAction.OPEN_WECHAT -> openWechat()
            }
        }
        binding.buttonRefreshLog.setOnClickListener { refresh() }
        binding.buttonCopyLog.setOnClickListener { Diagnostics.copy(this) }
        binding.buttonExportLog.setOnClickListener { Diagnostics.export(this) }
        binding.buttonOpenSource.setOnClickListener { openHelp("https://github.com/YIRC99/yanwai") }
        binding.buttonCopyAuthor.setOnClickListener {
            runCatching {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("作者微信号", "YIRC99"))
            }.onSuccess { Toast.makeText(this, "已复制微信号 YIRC99", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "复制失败，请长按上方微信号手动复制", Toast.LENGTH_LONG).show() }
        }
        binding.buttonTestModel.setOnClickListener { testModel() }
        updateNotice = UpdateNotice(this, binding, uiScope, ::openHelp)
    }

    private fun save(key: String, value: Boolean) {
        if (!SettingsProvider.save(this) { putBoolean(key, value) }) {
            Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
        }
        ModulePrefs.reload(force = true)
        refresh()
    }

    private fun showProvider() {
        bindingInputs = true
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        val draft = drafts[selectedProvider] ?: ApiDraft(
            prefs.getString("channel_${selectedProvider.id}_endpoint", selectedProvider.endpoint).orEmpty(),
            prefs.getString("channel_${selectedProvider.id}_key", "").orEmpty(),
            prefs.getString("channel_${selectedProvider.id}_model", selectedProvider.model).orEmpty())
        val custom = selectedProvider == JevProvider.CUSTOM
        binding.inputProvider.setText(selectedProvider.label, false)
        binding.inputApiBase.setText(if (custom) draft.endpoint else selectedProvider.endpoint)
        binding.inputApiModel.setText(if (custom) draft.model else selectedProvider.model)
        binding.inputApiKey.setText(draft.key)
        binding.layoutApiBase.isEnabled = custom
        binding.layoutApiBase.visibility = if (custom) View.VISIBLE else View.GONE
        binding.layoutApiBase.helperText = if (custom) "请填写完整 Jev 兼容接口地址，不会自动补路径。" else "已按渠道匹配，无需手动修改。"
        binding.layoutApiModel.visibility = if (custom) View.VISIBLE else View.GONE
        binding.textProviderGuide.text = selectedProvider.guide
        binding.textProviderSummary.text = if (custom) "填写支持 Jev 协议的完整地址、模型名和 Key。" else
            "${selectedProvider.label} 的地址和模型已匹配，只需填写对应 Key。"
        binding.buttonGetKey.visibility = if (selectedProvider.keyUrl == null) View.GONE else View.VISIBLE
        binding.layoutApiBase.error = null
        binding.layoutApiKey.error = null
        binding.layoutApiModel.error = null
        binding.textTestResult.visibility = View.GONE
        probeState = ProbeState.UNTESTED
        bindingInputs = false
        renderOverview()
    }

    private fun openHelp(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "未找到浏览器，请先安装浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveApiSettings(): Boolean {
        binding.layoutApiBase.error = null
        binding.layoutApiKey.error = null
        binding.layoutApiModel.error = null
        val endpoint = binding.inputApiBase.text?.toString().orEmpty()
        val key = binding.inputApiKey.text?.toString().orEmpty()
        val model = binding.inputApiModel.text?.toString().orEmpty()
        try { ApiSettings.fromInput(endpoint, "", selectedProvider.id) } catch (e: IllegalArgumentException) {
            binding.layoutApiBase.error = e.message
            binding.inputApiBase.requestFocus()
            return false
        }
        try { ApiSettings.fromInput(endpoint, "", selectedProvider.id, model) } catch (e: IllegalArgumentException) {
            binding.layoutApiModel.error = e.message
            binding.inputApiModel.requestFocus()
            return false
        }
        val settings = try { ApiSettings.fromInput(endpoint, key, selectedProvider.id, model) } catch (e: IllegalArgumentException) {
            binding.layoutApiKey.error = e.message
            binding.inputApiKey.requestFocus()
            return false
        }
        MoodLog.protect(settings.apiKey)
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        val values = ApiProfiles.valuesToSave(settings) { prefs.getString(it, null) }
        val saved = SettingsProvider.save(this) {
            values.forEach { (name, value) -> putString(name, value) }
        }
        if (!saved) {
            showResult("配置未保存\n请重试；若仍失败，可在「遇到问题」中导出日志。", StatusTone.ERROR)
            return false
        }
        bindingInputs = true
        binding.inputApiBase.setText(settings.endpoint)
        binding.inputApiModel.setText(settings.model)
        bindingInputs = false
        binding.textTestResult.visibility = View.GONE
        ModulePrefs.reload(force = true)
        refresh()
        return true
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Lifecycle dispatch finishes after onResume; cached notices also need RESUMED.
        binding.root.post {
            if (!isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                updateNotice.onResume()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(stateListener)
        getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(stateListener)
    }

    override fun onStop() {
        binding.root.removeCallbacks(refreshAfterSettings)
        getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(stateListener)
        getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(stateListener)
        super.onStop()
    }

    private fun refresh() {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        syncingSwitches = true
        binding.switchExplore.isChecked = prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false)
        syncingSwitches = false
        val savedProvider = JevProvider.resolve(prefs.getString(ModulePrefs.KEY_API_PROVIDER, null),
            prefs.getString(ModulePrefs.KEY_API_BASE, "").orEmpty())
        binding.textModelStatus.text = if (prefs.getString(ModulePrefs.KEY_API_KEY, "").isNullOrBlank())
            "选择渠道，填写对应 Key，再保存并检测。" else
            "当前保存：${savedProvider.label}。更换渠道或 Key 后，请重新检测。"
        val wechat = runCatching {
            @Suppress("DEPRECATION")
            "微信 ${packageManager.getPackageInfo("com.tencent.mm", 0).versionName}"
        }.getOrDefault("未检测到微信")
        val runtime = getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE)
        val last = runtime.getLong("last_seen", 0)
        val evidence = if (last == 0L) "尚未收到微信运行记录。先按下方步骤启用模块，再打开一个聊天。" else
            "最近记录（${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last))}）：\n${runtime.getString("status", "")}"
        binding.textFrameworkStatus.text = "$wechat\n$evidence\n这里展示最近上报情况，不代表微信当前在线。"
        if (binding.debugPanel.visibility == View.VISIBLE) binding.textLog.text = Diagnostics.collect(this)
        renderOverview()
    }

    private fun testModel() {
        if (!saveApiSettings()) return
        if (ModulePrefs.apiKey.isBlank()) {
            binding.layoutApiKey.error = "请先填写 API Key"
            binding.inputApiKey.requestFocus()
            return
        }
        binding.buttonTestModel.isEnabled = false
        binding.buttonSaveApi.isEnabled = false
        binding.layoutProvider.isEnabled = false
        binding.layoutApiBase.isEnabled = false
        binding.layoutApiKey.isEnabled = false
        binding.layoutApiModel.isEnabled = false
        binding.buttonTestModel.text = "正在检测…"
        probeState = ProbeState.CHECKING
        binding.progressModel.visibility = View.VISIBLE
        showResult("正在使用示例消息检测\n不会读取你的微信聊天，请稍等。", StatusTone.NEUTRAL)
        renderOverview()
        uiScope.launch {
            try {
                val mood = SignalAnalyzer.requestMood("这还差不多。", listOf(
                    dev.jev.wechatmood.core.ContextMessage("对方", "你是不是忘了周末吃饭的事？"),
                    dev.jev.wechatmood.core.ContextMessage("我", "记得，这次我来安排，明天把餐厅和时间告诉你。")))
                probeState = ProbeState.PASSED
                showResult("${selectedProvider.label} 检测通过\n${mood.detail}\n\n模型连接正常，微信模块是否生效请查看下方运行记录。", StatusTone.SUCCESS)
                MoodLog.i("模型连接检测成功")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                probeState = ProbeState.FAILED
                showResult("检测失败\n${e.message}\n\n修正配置或检查网络后，点击「重试连接检测」。", StatusTone.ERROR)
                MoodLog.e("模型连接检测失败：${e.message}")
            } finally {
                binding.buttonTestModel.isEnabled = true
                binding.buttonSaveApi.isEnabled = true
                binding.layoutProvider.isEnabled = true
                binding.layoutApiBase.isEnabled = selectedProvider == JevProvider.CUSTOM
                binding.layoutApiKey.isEnabled = true
                binding.layoutApiModel.isEnabled = true
                binding.buttonTestModel.text = if (probeState == ProbeState.FAILED) "重试连接检测" else "重新检测连接"
                binding.progressModel.visibility = View.GONE
                refresh()
            }
        }
    }

    private fun draftDirty(): Boolean {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        val draft = runCatching { ApiSettings.fromInput(binding.inputApiBase.text.toString(),
            binding.inputApiKey.text.toString(), selectedProvider.id, binding.inputApiModel.text.toString()) }.getOrNull() ?: return true
        val saved = runCatching { ApiSettings.fromInput(prefs.getString(ModulePrefs.KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT).orEmpty(),
            prefs.getString(ModulePrefs.KEY_API_KEY, "").orEmpty(), prefs.getString(ModulePrefs.KEY_API_PROVIDER, null),
            prefs.getString(ModulePrefs.KEY_API_MODEL, "").orEmpty()) }.getOrNull() ?: return true
        return draft.endpoint != saved.endpoint || draft.apiKey != saved.apiKey || draft.model != saved.model || draft.provider != saved.provider
    }

    private fun overview() = SetupPresenter.resolve(
        !getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE).getString(ModulePrefs.KEY_API_KEY, "").isNullOrBlank(),
        draftDirty(), probeState,
        getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE).getLong("last_seen", 0), System.currentTimeMillis())

    private fun renderOverview() {
        val state = overview()
        binding.textOverviewTitle.text = state.title
        binding.textOverviewDescription.text = state.description
        binding.textModelBadge.text = state.modelLabel
        binding.textHostBadge.text = state.hostLabel
        tintStatus(binding.textModelBadge, state.modelTone)
        tintStatus(binding.textHostBadge, state.hostTone)
        binding.overviewCard.strokeColor = ContextCompat.getColor(this, when (state.tone) {
            StatusTone.ERROR -> R.color.status_error
            StatusTone.WARNING -> R.color.status_warning
            StatusTone.SUCCESS -> R.color.brand_primary
            StatusTone.NEUTRAL -> R.color.outline_subtle
        })
        binding.buttonNextStep.text = state.actionLabel
        binding.buttonNextStep.isEnabled = probeState != ProbeState.CHECKING
        binding.buttonTestModel.text = when (probeState) {
            ProbeState.CHECKING -> "正在检测…"
            ProbeState.FAILED -> "重试连接检测"
            ProbeState.PASSED -> "重新检测连接"
            ProbeState.UNTESTED -> "保存并检测连接"
        }
        binding.buttonJumpModel.visibility = if (state.action == SetupAction.CONFIGURE || state.action == SetupAction.TEST) View.GONE else View.VISIBLE
        binding.textDraftStatus.visibility = if (draftDirty()) View.VISIBLE else View.GONE
        tintStatus(binding.textDraftStatus, StatusTone.WARNING)
    }

    private fun tintStatus(view: TextView, tone: StatusTone) {
        val colors = when (tone) {
            StatusTone.SUCCESS -> R.color.status_success to R.color.status_success_bg
            StatusTone.WARNING -> R.color.status_warning to R.color.status_warning_bg
            StatusTone.ERROR -> R.color.status_error to R.color.status_error_bg
            StatusTone.NEUTRAL -> R.color.status_neutral to R.color.status_neutral_bg
        }
        view.setTextColor(ContextCompat.getColor(this, colors.first))
        view.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colors.second))
    }

    private fun showResult(message: String, tone: StatusTone) {
        binding.textTestResult.text = message
        binding.textTestResult.visibility = View.VISIBLE
        tintStatus(binding.textTestResult, tone)
    }

    private fun scrollTo(view: View) { binding.pageScroll.post { binding.pageScroll.smoothScrollTo(0, view.top) } }

    private fun showSetupGuide(open: Boolean) {
        binding.setupGuidePanel.visibility = if (open) View.VISIBLE else View.GONE
        binding.buttonSetupGuide.text = if (open) "收起启用步骤" else "首次使用 / 未生效？查看步骤"
    }

    private fun openWechat() {
        runCatching {
            startActivity(requireNotNull(packageManager.getLaunchIntentForPackage("com.tencent.mm")) { "未找到当前空间的微信" })
        }.onFailure {
            showSetupGuide(true)
            scrollTo(binding.wechatSection)
            Toast.makeText(this, "无法打开微信，请检查是否安装在同一空间", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("reply_tab", binding.modelTabs.checkedButtonId == R.id.tabReply)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
