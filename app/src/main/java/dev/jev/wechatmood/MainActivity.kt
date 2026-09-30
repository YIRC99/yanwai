package dev.jev.wechatmood

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import dev.jev.wechatmood.analysis.ChatAnalysis
import dev.jev.wechatmood.analysis.JevHttpClient
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
import dev.jev.wechatmood.ui.SetupPresenter
import dev.jev.wechatmood.ui.SettingsStatus
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
    private val pagePositions = mutableMapOf<Int, Int>()
    private var currentPage = R.id.tabHome
    private var modelsReturnPage = R.id.tabEmotion
    private var intentSettingsUi: dev.jev.wechatmood.ui.IntentSettingsUi? = null
    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (currentPage == R.id.modelsPanel) showPage(modelsReturnPage)
            else binding.navigation.selectedItemId = R.id.tabHome
        }
    }
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
        onBackPressedDispatcher.addCallback(this, backToHome)
        dev.jev.wechatmood.ui.ReplySettingsUi(this, binding.replySettings, uiScope, ::openHelp)
        binding.navigation.setOnItemSelectedListener { item ->
            showPage(item.itemId)
            true
        }
        val selectedTab = savedInstanceState?.getInt("selected_tab")
            ?.takeIf { it in listOf(R.id.tabHome, R.id.tabEmotion, R.id.tabReply, R.id.tabAbout) }
            ?: if (intent.getBooleanExtra("reply_tab", false)) R.id.tabReply else R.id.tabHome
        binding.navigation.selectedItemId = selectedTab
        binding.entryAnalysis.setOnClickListener { binding.navigation.selectedItemId = R.id.tabEmotion }
        binding.entryReply.setOnClickListener { binding.navigation.selectedItemId = R.id.tabReply }
        binding.entryDisplay.setOnClickListener {
            binding.navigation.selectedItemId = R.id.tabAbout
            scrollTo(binding.showIntent)
        }
        binding.entryGuide.setOnClickListener { showSetupGuide(true); scrollTo(binding.setupGuidePanel) }
        bindDisplaySettings()
        binding.toggleContact.setOnClickListener {
            val open = binding.contactPanel.visibility != View.VISIBLE
            binding.contactPanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.toggleContact.text = if (open) "联系作者  ▴" else "联系作者  ▾"
        }
        binding.toggleSource.setOnClickListener {
            val open = binding.sourcePanel.visibility != View.VISIBLE
            binding.sourcePanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.toggleSource.text = if (open) "免费与开源  ▴" else "免费与开源  ▾"
        }
        binding.knowledgeSource.setOnClickListener { openHelp(dev.jev.wechatmood.reply.ReplyKnowledge.SOURCE_URL) }
        binding.knowledgeLicense.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("狗头军师 · MIT License")
                .setMessage(assets.open("goutoujunshi/LICENSE").bufferedReader().use { it.readText() })
                .setPositiveButton("关闭", null).show()
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
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
        intentSettingsUi = dev.jev.wechatmood.ui.IntentSettingsUi(this, binding.intentSettings,
            binding.analysisModelSettings, uiScope, ::openHelp,
            { openModels(R.id.modelReply) }, { openModels(R.id.modelJev) }) { renderOverview() }
        binding.modelTabs.addOnButtonCheckedListener { _, id, checked -> if (checked) showModelTab(id) }
        binding.openReplyModel.setOnClickListener { openModels(R.id.modelReply) }
        binding.openAllModels.setOnClickListener { openModels(R.id.modelJev) }
        binding.toolbar.setNavigationOnClickListener { backToHome.handleOnBackPressed() }
        showModelTab(savedInstanceState?.getInt("model_tab", R.id.modelJev) ?: R.id.modelJev)
        if (savedInstanceState?.getBoolean("models_open") == true) {
            modelsReturnPage = selectedTab
            showPage(R.id.modelsPanel)
        }
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
        binding.buttonSetupGuide.setOnClickListener {
            val open = binding.setupGuidePanel.visibility != View.VISIBLE
            showSetupGuide(open)
            if (open) scrollTo(binding.setupGuidePanel)
        }
        binding.buttonOpenWechat.setOnClickListener { openWechat() }
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
            showResult("配置未保存\n请重试；若仍失败，可在「更多 → 遇到问题」中导出日志。", StatusTone.ERROR)
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
        val wechat = runCatching {
            @Suppress("DEPRECATION")
            "微信 ${packageManager.getPackageInfo("com.tencent.mm", 0).versionName}"
        }.getOrDefault("未检测到微信")
        val runtime = getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE)
        val last = runtime.getLong("last_seen", 0)
        val evidence = if (last == 0L) "尚未收到运行记录，可查看启用指南。" else
            "最近记录（${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last))}）：\n${runtime.getString("status", "")}"
        binding.textFrameworkStatus.text = "$wechat\n$evidence\n运行记录不代表微信当前在线。"
        val host = SetupPresenter.resolve(false, false, ProbeState.UNTESTED, last, System.currentTimeMillis())
        binding.textHostBadge.text = host.hostLabel
        tintStatus(binding.textHostBadge, host.hostTone)
        if (binding.debugPanel.visibility == View.VISIBLE) binding.textLog.text = Diagnostics.collect(this)
        renderOverview()
    }

    private fun testModel() {
        if (probeState == ProbeState.CHECKING) return
        if (!saveApiSettings()) return
        if (ModulePrefs.apiKey.isBlank()) {
            binding.layoutApiKey.error = "请先填写 API Key"
            binding.inputApiKey.requestFocus()
            return
        }
        val testedSettings = ModulePrefs.analysisSettings() ?: return
        binding.buttonTestModel.isEnabled = false
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
                // Test this connector only, even if the user has an unsaved route change.
                val sample = dev.jev.wechatmood.core.AnalysisInput("这还差不多。", "sample", listOf(
                    dev.jev.wechatmood.core.ContextMessage("对方", "你是不是忘了周末吃饭的事？"),
                    dev.jev.wechatmood.core.ContextMessage("我", "记得，这次我来安排，明天把餐厅和时间告诉你。")))
                val client = JevHttpClient()
                val mood = ChatAnalysis.analyzeSuspending(sample, testedSettings.api.model,
                    { payload -> client.exchangeSuspending(payload, testedSettings.api) })
                if (!sameJevSettings(testedSettings.api)) {
                    return@launch
                }
                probeState = ProbeState.PASSED
                showResult("JEV 检测通过\n${mood.detail}\n\n仅验证 JEV 连接；微信模块状态见首页。", StatusTone.SUCCESS)
                MoodLog.i("模型连接检测成功")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!sameJevSettings(testedSettings.api)) {
                    return@launch
                }
                probeState = ProbeState.FAILED
                showResult("检测失败\n${e.message}\n\n修正配置或检查网络后，点击「重试连接检测」。", StatusTone.ERROR)
                MoodLog.e("模型连接检测失败：${e.message}")
            } finally {
                if (!sameJevSettings(testedSettings.api)) {
                    probeState = ProbeState.UNTESTED
                    showResult("配置已变化，请重新检测。", StatusTone.WARNING)
                }
                binding.buttonTestModel.isEnabled = true
                binding.layoutProvider.isEnabled = true
                binding.layoutApiBase.isEnabled = selectedProvider == JevProvider.CUSTOM
                binding.layoutApiKey.isEnabled = true
                binding.layoutApiModel.isEnabled = true
                binding.buttonTestModel.text = if (probeState == ProbeState.FAILED) "重试连接检测" else "重新检测 JEV"
                binding.progressModel.visibility = View.GONE
                refresh()
            }
        }
    }

    private fun sameJevSettings(tested: ApiSettings): Boolean {
        val current = ModulePrefs.analysisSettings()?.api ?: return false
        return current.endpoint == tested.endpoint && current.apiKey == tested.apiKey &&
            current.model == tested.model && current.provider == tested.provider
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

    private fun renderOverview() {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        val dirty = draftDirty()
        val hasKey = !prefs.getString(ModulePrefs.KEY_API_KEY, "").isNullOrBlank()
        val state = SetupPresenter.resolve(hasKey, dirty, probeState, 0, System.currentTimeMillis())
        binding.textModelStatus.text = "JEV · ${state.modelLabel}"
        tintStatus(binding.textModelStatus, state.modelTone)
        if (probeState == ProbeState.CHECKING) SettingsStatus.show(binding.textModelStatus, "JEV · 正在检测", R.color.status_info)
        SettingsStatus.show(binding.buttonTestModel, "", when (probeState) {
            ProbeState.PASSED -> R.color.status_success
            ProbeState.FAILED -> R.color.status_error
            ProbeState.CHECKING -> R.color.status_info
            ProbeState.UNTESTED -> R.color.status_neutral
        })
        val reply = ModulePrefs.replySettings()
        SettingsStatus.show(binding.replySummary, if (reply.isConfigured) "当前模型：" + reply.model else "先连接回复模型", if (reply.isConfigured) R.color.status_neutral else R.color.status_warning)
        binding.buttonTestModel.text = when (probeState) {
            ProbeState.CHECKING -> "正在检测…"
            ProbeState.FAILED -> "连接失败 · 点击重试"
            ProbeState.PASSED -> "连接成功 · 重新检测"
            ProbeState.UNTESTED -> "保存并检测 JEV"
        }
    }

    private fun showPage(id: Int) {
        pagePositions[currentPage] = binding.pageScroll.scrollY
        currentFocus?.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        currentPage = id
        backToHome.isEnabled = id != R.id.tabHome
        if (id == R.id.tabEmotion) intentSettingsUi?.onShown()
        binding.homePanel.visibility = if (id == R.id.tabHome) View.VISIBLE else View.GONE
        binding.emotionPanel.visibility = if (id == R.id.tabEmotion) View.VISIBLE else View.GONE
        binding.replyPanel.visibility = if (id == R.id.tabReply) View.VISIBLE else View.GONE
        binding.modelsPanel.visibility = if (id == R.id.modelsPanel) View.VISIBLE else View.GONE
        binding.navigation.visibility = if (id == R.id.modelsPanel) View.GONE else View.VISIBLE
        binding.toolbar.navigationIcon = if (id == R.id.modelsPanel) androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_back) else null
        binding.toolbar.navigationContentDescription = "返回上一页"
        if (id == R.id.modelsPanel) intentSettingsUi?.onShown()
        binding.aboutPanel.visibility = if (id == R.id.tabAbout) View.VISIBLE else View.GONE
        binding.pageScroll.post { if (currentPage == id) binding.pageScroll.scrollTo(0, pagePositions[id] ?: 0) }
    }

    private fun bindDisplaySettings() {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        listOf(binding.showIntent to dev.jev.wechatmood.core.CardDisplaySettings.KEY_INTENT,
            binding.showConcern to dev.jev.wechatmood.core.CardDisplaySettings.KEY_CONCERN,
            binding.showTone to dev.jev.wechatmood.core.CardDisplaySettings.KEY_TONE).forEach { (toggle, key) ->
            toggle.isChecked = prefs.getBoolean(key, true)
            ViewCompat.setStateDescription(toggle, if (toggle.isChecked) "显示" else "隐藏")
            var restoring = false
            toggle.setOnCheckedChangeListener { _, enabled ->
                if (!restoring) {
                    val saved = SettingsProvider.save(this) { putBoolean(key, enabled) }
                    if (saved) ModulePrefs.reload(force = true) else {
                        restoring = true; toggle.isChecked = prefs.getBoolean(key, true); restoring = false
                    }
                    ViewCompat.setStateDescription(toggle, if (toggle.isChecked) "显示" else "隐藏")
                    SettingsStatus.show(binding.cardDisplayStatus,
                        if (saved) "已保存，返回聊天即可查看。" else "保存失败，请重试。",
                        if (saved) R.color.status_success else R.color.status_error)
                }
            }
        }
    }

    private fun tintStatus(view: TextView, tone: StatusTone) {
        val color = when (tone) {
            StatusTone.SUCCESS -> R.color.status_success
            StatusTone.WARNING -> R.color.status_warning
            StatusTone.ERROR -> R.color.status_error
            StatusTone.NEUTRAL -> R.color.status_neutral
        }
        SettingsStatus.show(view, view.text.toString(), color)
    }

    private fun showResult(message: String, tone: StatusTone) {
        binding.textTestResult.text = message
        binding.textTestResult.visibility = View.VISIBLE
        tintStatus(binding.textTestResult, tone)
    }

    private fun scrollTo(view: View) {
        binding.pageScroll.post {
            val content = binding.pageScroll.getChildAt(0) as android.view.ViewGroup
            val bounds = Rect()
            view.getDrawingRect(bounds)
            content.offsetDescendantRectToMyCoords(view, bounds)
            binding.pageScroll.smoothScrollTo(0, bounds.top)
        }
    }

    private fun openModels(tab: Int) {
        if (currentPage != R.id.modelsPanel) modelsReturnPage = currentPage
        showModelTab(tab)
        pagePositions[R.id.modelsPanel] = 0
        showPage(R.id.modelsPanel)
    }

    private fun showModelTab(id: Int) {
        val tab = id.takeIf { it in listOf(R.id.modelJev, R.id.modelAnalysis, R.id.modelReply) } ?: R.id.modelJev
        if (binding.modelTabs.checkedButtonId != tab) binding.modelTabs.check(tab)
        binding.jevModelPage.visibility = if (tab == R.id.modelJev) View.VISIBLE else View.GONE
        binding.analysisModelSettings.root.visibility = if (tab == R.id.modelAnalysis) View.VISIBLE else View.GONE
        binding.replySettings.root.visibility = if (tab == R.id.modelReply) View.VISIBLE else View.GONE
        currentFocus?.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.pageScroll.post { if (currentPage == R.id.modelsPanel) binding.pageScroll.scrollTo(0, 0) }
    }

    private fun showSetupGuide(open: Boolean) {
        binding.setupGuidePanel.visibility = if (open) View.VISIBLE else View.GONE
        binding.buttonSetupGuide.text = if (open) "收起使用指南" else "查看启用与使用指南"
    }

    private fun openWechat() {
        runCatching {
            startActivity(requireNotNull(packageManager.getLaunchIntentForPackage("com.tencent.mm")) { "未找到当前空间的微信" })
        }.onFailure {
            showSetupGuide(true)
            binding.navigation.selectedItemId = R.id.tabHome
            scrollTo(binding.setupGuidePanel)
            Toast.makeText(this, "无法打开微信，请检查是否安装在同一空间", Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected_tab", binding.navigation.selectedItemId)
        outState.putBoolean("models_open", currentPage == R.id.modelsPanel)
        outState.putInt("model_tab", binding.modelTabs.checkedButtonId)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
