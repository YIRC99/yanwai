package dev.jev.wechatmood.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import dev.jev.wechatmood.R
import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.databinding.IntentSettingsBinding
import dev.jev.wechatmood.databinding.AnalysisModelSettingsBinding
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*

class IntentSettingsUi(private val activity: AppCompatActivity, private val binding: IntentSettingsBinding,
    private val modelBinding: AnalysisModelSettingsBinding,
    private val scope: CoroutineScope, openUrl: (String) -> Unit, openReplySettings: () -> Unit, openModels: () -> Unit,
    private val routeChanged: (EmotionSource) -> Unit) {
    private val prefs = activity.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
    private var route = IntentRoute.resolve(prefs.getString(IntentSettings.KEY_ROUTE, null))
    private var emotion = EmotionSettings.load { prefs.getString(it, null) }
    private var provider = ReplyProvider.resolve(prefs.getString(IntentProfiles.KEY_PROVIDER, null),
        prefs.getString(IntentSettings.KEY_ENDPOINT, "").orEmpty()).let {
        if (prefs.getString(IntentSettings.KEY_ENDPOINT, "").isNullOrBlank()) ReplyProvider.DEEPSEEK else it
    }
    private var rendering = false
    private var busy = false
    private var testedFingerprint: String? = null
    private class Draft(val endpoint: String, val key: String, val model: String)
    private val drafts = mutableMapOf<ReplyProvider, Draft>()

    init {
        modelBinding.provider.setSimpleItems(ReplyProvider.entries.map { it.label }.toTypedArray())
        showProvider()
        binding.emotionSource.setSimpleItems(arrayOf("决策模型（JEV）", "智能模型（LLM）"))
        binding.configSource.setSimpleItems(arrayOf("独立分析模型", "复用回复模型"))
        binding.intentRoute.setSimpleItems(arrayOf("决策模型（JEV）", "智能模型（LLM）"))
        binding.emotionSource.setText(if (emotion.source == EmotionSource.LLM) "智能模型（LLM）" else "决策模型（JEV）", false)
        binding.configSource.setText(if (emotion.reuseReply) "复用回复模型" else "独立分析模型", false)
        binding.intentRoute.setText(if (route == IntentRoute.LLM) "智能模型（LLM）" else "决策模型（JEV）", false)
        showRoute()
        status("已保存的分析方式", R.color.status_neutral)
        modelStatus("尚未检测", R.color.status_neutral)
        binding.emotionSource.setOnItemClickListener { _, _, position, _ ->
            emotion = emotion.copy(source = if (position == 1) EmotionSource.LLM else EmotionSource.JEV)
            showRoute(); status("有未保存修改", R.color.status_warning)
        }
        binding.configSource.setOnItemClickListener { _, _, position, _ ->
            emotion = emotion.copy(reuseReply = position == 1)
            showRoute(); status("有未保存修改", R.color.status_warning)
        }
        binding.intentRoute.setOnItemClickListener { _, _, position, _ ->
            route = if (position == 1) IntentRoute.LLM else IntentRoute.JEV
            showRoute(); status("有未保存修改", R.color.status_warning)
        }
        modelBinding.provider.setOnItemClickListener { _, _, position, _ ->
            drafts[provider] = Draft(modelBinding.endpoint.text.toString(), modelBinding.apiKey.text.toString(), modelBinding.model.text.toString())
            provider = ReplyProvider.entries[position]
            showProvider(); dirty()
        }
        listOf(modelBinding.endpoint, modelBinding.apiKey, modelBinding.model).forEach { field -> field.doAfterTextChanged {
            if (!rendering && !busy) {
                dirty()
                if (field != modelBinding.model) clearModels()
            }
        } }
        modelBinding.providerConsole.setOnClickListener { if (provider.consoleUrl.isNotBlank()) openUrl(provider.consoleUrl) }
        modelBinding.fetchModels.setOnClickListener { fetchModels() }
        modelBinding.referenceModels.setOnClickListener {
            setModels(provider.referenceModels)
            SettingsStatus.show(modelBinding.modelsStatus, "文档参考模型，不代表账户可用；选择后请检测。", R.color.status_warning)
            modelBinding.model.requestFocus(); modelBinding.model.showDropDown()
        }
        binding.saveIntent.setOnClickListener {
            if (save()) {
                android.widget.Toast.makeText(activity, "分析设置已保存", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        modelBinding.testSelected.setOnClickListener { test() }
        binding.editReplyConfig.setOnClickListener { openReplySettings() }
        binding.openModelSettings.setOnClickListener { openModels() }
    }

    private fun showRoute() {
        val pure = emotion.source == EmotionSource.LLM
        binding.configSourceLayout.visibility = if (pure) View.VISIBLE else View.GONE
        binding.reusedConfigPanel.visibility = if (pure && emotion.reuseReply) View.VISIBLE else View.GONE
        renderSharedConfig()
        binding.intentRouteLayout.visibility = if (pure) View.GONE else View.VISIBLE
        binding.routeHint.visibility = if (pure) View.GONE else View.VISIBLE
        binding.emotionHint.text = if (pure) "判断文字表达的情绪，仅供参考。" else "给出情绪概率，仅供参考。"
        binding.routeHint.text = if (route == IntentRoute.JEV) "从内置选项快速判断，只需要 JEV。" else "JEV 判断情绪，LLM 结合前文补充解读。"
        routeChanged(emotion.source)
    }

    private fun showProvider() {
        rendering = true
        val draft = drafts[provider] ?: IntentProfiles.load(provider) { prefs.getString(it, null) }
            .let { Draft(it.endpoint, it.apiKey, it.model) }
        modelBinding.provider.setText(provider.label, false)
        modelBinding.endpoint.setText(draft.endpoint); modelBinding.apiKey.setText(draft.key); modelBinding.model.setText(draft.model, false)
        modelBinding.endpointLayout.visibility = if (provider == ReplyProvider.CUSTOM) View.VISIBLE else View.GONE
        // Preset addresses add no decision value; custom services expose the editable address.
        modelBinding.providerHint.text = provider.hint
        modelBinding.providerConsole.visibility = if (provider.consoleUrl.isBlank()) View.GONE else View.VISIBLE
        modelBinding.referenceModels.visibility = if (provider.referenceModels.isEmpty()) View.GONE else View.VISIBLE
        modelBinding.endpointLayout.error = null; modelBinding.keyLayout.error = null; modelBinding.modelLayout.error = null
        clearModels()
        rendering = false
    }

    private fun dirty() {
        testedFingerprint = null
        modelStatus("有未保存修改 · 保存后用于微信", R.color.status_warning)
        modelBinding.intentResult.visibility = View.GONE
    }

    private fun read(requireModel: Boolean = true): ReplySettings? {
        modelBinding.endpointLayout.error = null; modelBinding.keyLayout.error = null; modelBinding.modelLayout.error = null
        val endpoint = if (provider == ReplyProvider.CUSTOM) modelBinding.endpoint.text.toString() else provider.endpoint
        if (endpoint.isBlank()) { modelBinding.endpointLayout.error = "请填写 API 地址"; return null }
        if (modelBinding.apiKey.text.isNullOrBlank()) { modelBinding.keyLayout.error = "请填写 API Key"; return null }
        if (requireModel && modelBinding.model.text.isNullOrBlank()) { modelBinding.modelLayout.error = "请选择或填写模型 ID"; return null }
        return try {
            ReplySettings.fromInput(endpoint, modelBinding.apiKey.text.toString(), if (requireModel) modelBinding.model.text.toString() else "")
                .also { MoodLog.protect(it.apiKey) }
        } catch (e: IllegalArgumentException) { result(e.message.orEmpty(), true); null }
    }

    private fun save(): Boolean {
        if (!SettingsProvider.save(activity) {
            putString(IntentSettings.KEY_ROUTE, route.id)
            putString(EmotionSettings.KEY_SOURCE, emotion.source.id)
            putString(EmotionSettings.KEY_REUSE_REPLY, emotion.reuseReply.toString())
        }) { status("保存失败，请重试", R.color.status_error); return false }
        ModulePrefs.reload(force = true)
        status("分析方式已保存", R.color.status_success)
        renderSharedConfig()
        routeChanged(emotion.source)
        return true
    }

    private fun saveModel(): ReplySettings? {
        val settings = read() ?: return null
        val values = IntentProfiles.valuesToSave(provider, settings) { prefs.getString(it, null) }
        if (!SettingsProvider.save(activity) { values.forEach { (key, value) -> putString(key, value) } }) {
            result("保存失败，请重试", true); return null
        }
        ModulePrefs.reload(force = true)
        renderSharedConfig()
        return settings
    }

    private fun savedModelFingerprint(): String {
        val saved = IntentSettings.load { prefs.getString(it, null) }.llm
        return AnalysisCacheKey.digest("analysis-connection", ModulePrefs.analysisSettings()?.generation.orEmpty(), saved.endpoint, saved.apiKey, saved.model)
    }

    private fun setModels(models: List<String>) {
        modelBinding.model.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, models))
        modelBinding.modelLayout.isEndIconVisible = models.isNotEmpty()
    }
    private fun clearModels() { setModels(emptyList()); SettingsStatus.show(modelBinding.modelsStatus, "填写 Key 后获取，或直接输入模型 ID。") }

    private fun fetchModels() {
        if (busy) return
        val settings = read(false) ?: return
        setBusy(true)
        SettingsStatus.show(modelBinding.modelsStatus, "正在获取模型列表…", R.color.status_info)
        scope.launch {
            try {
                val models = ReplyModelsClient().list(settings)
                setModels(models)
                SettingsStatus.show(modelBinding.modelsStatus, "已获取 ${models.size} 个候选模型；选定后请检测。", if (models.isEmpty()) R.color.status_warning else R.color.status_success)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { SettingsStatus.show(modelBinding.modelsStatus, e.message ?: "获取失败，可重试或手动填写模型 ID", R.color.status_error) }
            finally { setBusy(false) }
        }
    }

    private fun test() {
        if (busy) return
        val settings = saveModel() ?: return
        val fingerprint = savedModelFingerprint()
        setBusy(true)
        modelStatus("正在检测分析模型…", R.color.status_info)
        result("使用示例聊天检测，不读取微信消息。")
        scope.launch {
            try {
                val input = AnalysisInput("睡吧睡吧", "sample", listOf(ContextMessage("我", "累了一天，终于躺下了。")))
                val mood = ReplyHttpClient().request(settings,
                    dev.jev.wechatmood.analysis.LlmEmotionProtocol.payload(input, settings),
                    dev.jev.wechatmood.analysis.LlmEmotionProtocol::parse)
                if (savedModelFingerprint() != fingerprint) return@launch
                testedFingerprint = fingerprint
                modelStatus("分析模型连接成功", R.color.status_success)
                result(mood.detail)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (savedModelFingerprint() != fingerprint) return@launch
                testedFingerprint = fingerprint
                modelStatus("分析模型连接失败 · 点击重试", R.color.status_error)
                result(e.message ?: "检测失败，请重试", true)
            } finally {
                setBusy(false)
                if (savedModelFingerprint() != fingerprint) {
                    testedFingerprint = null
                    modelStatus("配置已变化 · 请重新检测", R.color.status_warning)
                    modelBinding.intentResult.visibility = View.GONE
                }
            }
        }
    }
    fun onShown() {
        renderSharedConfig()
        if (testedFingerprint != null && testedFingerprint != savedModelFingerprint()) {
            testedFingerprint = null
            modelStatus("配置已变化 · 请重新检测", R.color.status_warning)
            modelBinding.intentResult.visibility = View.GONE
        }
    }

    private fun renderSharedConfig() {
        val shared = ModulePrefs.replySettings()
        val saved = ModulePrefs.analysisSettings()
        val llmNeeded = emotion.source == EmotionSource.LLM || route == IntentRoute.LLM
        val llm = if (emotion.source == EmotionSource.LLM && emotion.reuseReply) shared else saved?.intent?.llm
        val missing = buildList {
            if (emotion.source == EmotionSource.JEV && saved?.api?.isConfigured != true) add("JEV")
            if (llmNeeded && llm?.isConfigured != true) add("LLM")
        }
        binding.connectionSummary.text = if (missing.isEmpty()) "所需连接已配置，可进入模型页查看或检测。" else "还需配置：" + missing.joinToString("、")
        binding.connectionSummary.setTextColor(androidx.core.content.ContextCompat.getColor(activity, if (missing.isEmpty()) R.color.text_secondary else R.color.status_warning))
        SettingsStatus.show(binding.reusedConfigStatus,
            if (shared.isConfigured) "当前复用：${shared.model}" else "回复模型尚未配置",
            if (shared.isConfigured) R.color.status_neutral else R.color.status_warning)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(modelBinding.testSelected,
            modelBinding.providerLayout, modelBinding.endpointLayout, modelBinding.keyLayout,
            modelBinding.modelLayout, modelBinding.fetchModels, modelBinding.referenceModels)
            .forEach { it.isEnabled = !value }
        modelBinding.progressIntent.visibility = if (value) View.VISIBLE else View.GONE
    }
    private fun modelStatus(text: String, color: Int) {
        SettingsStatus.show(modelBinding.modelStatus, text, color)
        SettingsStatus.show(modelBinding.testSelected, when (color) {
            R.color.status_success -> "连接成功 · 重新检测"
            R.color.status_error -> "连接失败 · 点击重试"
            R.color.status_info -> "正在检测…"
            else -> "保存并检测分析模型"
        }, color)
    }
    private fun status(text: String, color: Int) {
        SettingsStatus.show(binding.intentStatus, text, color)
    }
    private fun result(text: String, error: Boolean = false) {
        modelBinding.intentResult.text = text; modelBinding.intentResult.visibility = View.VISIBLE
        SettingsStatus.show(modelBinding.intentResult, text, if (error) R.color.status_error else R.color.status_neutral)
    }
}
