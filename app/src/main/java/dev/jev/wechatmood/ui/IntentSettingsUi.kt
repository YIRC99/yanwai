package dev.jev.wechatmood.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import dev.jev.wechatmood.R
import dev.jev.wechatmood.analysis.IntentProtocol
import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.databinding.IntentSettingsBinding
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*

class IntentSettingsUi(private val activity: AppCompatActivity, private val binding: IntentSettingsBinding,
    private val scope: CoroutineScope, openUrl: (String) -> Unit, openReplySettings: () -> Unit,
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
        binding.provider.setSimpleItems(ReplyProvider.entries.map { it.label }.toTypedArray())
        showProvider()
        binding.emotionSource.setSimpleItems(arrayOf("JEV · 情绪概率", "LLM · 情绪与意图一起分析"))
        binding.configSource.setSimpleItems(arrayOf("独立分析模型", "复用回复模型"))
        binding.intentRoute.setSimpleItems(arrayOf("JEV · 快速判断", "LLM · 细致解读"))
        binding.emotionSource.setText(if (emotion.source == EmotionSource.LLM) "LLM · 情绪与意图一起分析" else "JEV · 情绪概率", false)
        binding.configSource.setText(if (emotion.reuseReply) "复用回复模型" else "独立分析模型", false)
        binding.intentRoute.setText(if (route == IntentRoute.LLM) "LLM · 细致解读" else "JEV · 快速判断", false)
        showRoute()
        status("已保存的分析方式 · 尚未检测", R.color.status_neutral)
        binding.emotionSource.setOnItemClickListener { _, _, position, _ ->
            emotion = emotion.copy(source = if (position == 1) EmotionSource.LLM else EmotionSource.JEV)
            showRoute(); dirty()
        }
        binding.configSource.setOnItemClickListener { _, _, position, _ ->
            emotion = emotion.copy(reuseReply = position == 1)
            showRoute(); dirty()
        }
        binding.intentRoute.setOnItemClickListener { _, _, position, _ ->
            route = if (position == 1) IntentRoute.LLM else IntentRoute.JEV
            showRoute(); dirty()
        }
        binding.provider.setOnItemClickListener { _, _, position, _ ->
            drafts[provider] = Draft(binding.endpoint.text.toString(), binding.apiKey.text.toString(), binding.model.text.toString())
            provider = ReplyProvider.entries[position]
            showProvider(); dirty()
        }
        listOf(binding.endpoint, binding.apiKey, binding.model).forEach { field -> field.doAfterTextChanged {
            if (!rendering && !busy) {
                dirty()
                if (field != binding.model) clearModels()
            }
        } }
        binding.providerConsole.setOnClickListener { if (provider.consoleUrl.isNotBlank()) openUrl(provider.consoleUrl) }
        binding.fetchModels.setOnClickListener { fetchModels() }
        binding.referenceModels.setOnClickListener {
            setModels(provider.referenceModels)
            SettingsStatus.show(binding.modelsStatus, "文档参考模型，不代表账户可用；选择后请检测。", R.color.status_warning)
            binding.model.requestFocus(); binding.model.showDropDown()
        }
        binding.saveIntent.setOnClickListener {
            if (save()) {
                binding.intentResult.visibility = View.GONE
                android.widget.Toast.makeText(activity, "分析设置已保存", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        binding.testSelected.setOnClickListener { test() }
        binding.editReplyConfig.setOnClickListener { openReplySettings() }
    }

    private fun showRoute() {
        val pure = emotion.source == EmotionSource.LLM
        binding.configSourceLayout.visibility = if (pure) View.VISIBLE else View.GONE
        binding.reusedConfigPanel.visibility = if (pure && emotion.reuseReply) View.VISIBLE else View.GONE
        renderSharedConfig()
        binding.intentRouteLayout.visibility = if (pure) View.GONE else View.VISIBLE
        binding.routeHint.visibility = if (pure) View.GONE else View.VISIBLE
        binding.testSelected.visibility = if (pure || route == IntentRoute.LLM) View.VISIBLE else View.GONE
        binding.testHint.visibility = binding.testSelected.visibility
        binding.testSelected.text = if (pure) "保存并检测 LLM 分析" else "保存并检测意图模型"
        binding.emotionHint.text = if (pure) "一次请求生成情绪标签、意图解析、可能在意和情绪倾向，不需要 JEV。情绪是定性参考，不是概率。" +
            if (emotion.reuseReply) "\n复用回复配置，更改回复模型也会影响此处；不会修改回复设置。" else "\n使用下方独立分析配置。"
            else "保留 JEV 情绪判断及原有意图解读方式。"
        binding.llmPanel.visibility = if (pure && !emotion.reuseReply || !pure && route == IntentRoute.LLM) View.VISIBLE else View.GONE
        binding.routeHint.text = if (route == IntentRoute.JEV)
            "从内置选项中判断意图，响应较快；不会自由生成对方在意的点。只需连接 JEV。" else
            "结合前文解释意图、可能在意的点和情绪倾向。等待更久，解读也可能有误；先显示 JEV 情绪，再补充解读。"
        routeChanged(emotion.source)
    }

    private fun showProvider() {
        rendering = true
        val draft = drafts[provider] ?: IntentProfiles.load(provider) { prefs.getString(it, null) }
            .let { Draft(it.endpoint, it.apiKey, it.model) }
        binding.provider.setText(provider.label, false)
        binding.endpoint.setText(draft.endpoint); binding.apiKey.setText(draft.key); binding.model.setText(draft.model, false)
        binding.endpointLayout.visibility = if (provider == ReplyProvider.CUSTOM) View.VISIBLE else View.GONE
        // Preset addresses add no decision value; custom services expose the editable address.
        binding.providerHint.text = provider.hint
        binding.providerConsole.visibility = if (provider.consoleUrl.isBlank()) View.GONE else View.VISIBLE
        binding.referenceModels.visibility = if (provider.referenceModels.isEmpty()) View.GONE else View.VISIBLE
        binding.endpointLayout.error = null; binding.keyLayout.error = null; binding.modelLayout.error = null
        clearModels()
        rendering = false
    }

    private fun dirty() {
        testedFingerprint = null
        status("有未保存修改 · 保存后用于微信", R.color.status_warning)
        binding.intentResult.visibility = View.GONE
    }

    private fun read(requireModel: Boolean = true): ReplySettings? {
        binding.endpointLayout.error = null; binding.keyLayout.error = null; binding.modelLayout.error = null
        val endpoint = if (provider == ReplyProvider.CUSTOM) binding.endpoint.text.toString() else provider.endpoint
        if (endpoint.isBlank()) { binding.endpointLayout.error = "请填写 API 地址"; return null }
        if (binding.apiKey.text.isNullOrBlank()) { binding.keyLayout.error = "请填写 API Key"; return null }
        if (requireModel && binding.model.text.isNullOrBlank()) { binding.modelLayout.error = "请选择或填写模型 ID"; return null }
        return try {
            ReplySettings.fromInput(endpoint, binding.apiKey.text.toString(), if (requireModel) binding.model.text.toString() else "")
                .also { MoodLog.protect(it.apiKey) }
        } catch (e: IllegalArgumentException) { result(e.message.orEmpty(), true); null }
    }

    private fun save(): Boolean {
        val pure = emotion.source == EmotionSource.LLM
        if (pure && emotion.reuseReply && !ModulePrefs.replySettings().isConfigured) {
            result("「帮我回」尚未配置模型，请到「回复」页配置，或选择「独立分析模型」。", true); return false
        }
        val values = if (pure && !emotion.reuseReply || !pure && route == IntentRoute.LLM) {
            val settings = read() ?: return false
            IntentProfiles.valuesToSave(provider, settings) { prefs.getString(it, null) }
        } else emptyMap()
        if (!SettingsProvider.save(activity) {
            putString(IntentSettings.KEY_ROUTE, route.id)
            putString(EmotionSettings.KEY_SOURCE, emotion.source.id)
            putString(EmotionSettings.KEY_REUSE_REPLY, emotion.reuseReply.toString())
            values.forEach { (key, value) -> putString(key, value) }
        }) { result("保存失败，请重试", true); return false }
        ModulePrefs.reload(force = true)
        testedFingerprint = null
        status("已保存情绪来源：${emotion.source.label} · 尚未检测", R.color.status_neutral)
        routeChanged(emotion.source)
        return true
    }

    private fun setModels(models: List<String>) {
        binding.model.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, models))
        binding.modelLayout.isEndIconVisible = models.isNotEmpty()
    }
    private fun clearModels() { setModels(emptyList()); SettingsStatus.show(binding.modelsStatus, "填写 Key 后获取，或直接输入模型 ID。") }

    private fun fetchModels() {
        if (busy) return
        val settings = read(false) ?: return
        setBusy(true)
        SettingsStatus.show(binding.modelsStatus, "正在获取模型列表…", R.color.status_info)
        scope.launch {
            try {
                val models = ReplyModelsClient().list(settings)
                setModels(models)
                SettingsStatus.show(binding.modelsStatus, "已获取 ${models.size} 个候选模型；选定后请检测。", if (models.isEmpty()) R.color.status_warning else R.color.status_success)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { SettingsStatus.show(binding.modelsStatus, e.message ?: "获取失败，可重试或手动填写模型 ID", R.color.status_error) }
            finally { setBusy(false) }
        }
    }

    private fun test() {
        if (busy) return
        if (!save()) return
        val settings = if (emotion.source == EmotionSource.LLM && emotion.reuseReply) ModulePrefs.replySettings() else read() ?: return
        val snapshot = requireNotNull(ModulePrefs.analysisSettings())
        setBusy(true)
        status("正在检测${if (emotion.source == EmotionSource.LLM) "LLM 完整分析" else "意图解读"}…", R.color.status_info)
        result("使用示例聊天检测，不读取微信消息。")
        scope.launch {
            try {
                val input = AnalysisInput("睡吧睡吧", "sample", listOf(ContextMessage("我", "累了一天，终于躺下了。")))
                if (emotion.source == EmotionSource.LLM) {
                    val mood = dev.jev.wechatmood.analysis.AnalysisRouter.analyze(input, snapshot,
                        { error("纯 LLM 检测不应调用 JEV") }, { config, payload -> ReplyHttpClient().request(config, payload) { it } })
                    if (ModulePrefs.analysisSettings()?.sameAnalysis(snapshot) != true) return@launch
                    status("LLM 完整分析检测通过", R.color.status_success)
                    testedFingerprint = snapshot.analysisFingerprint()
                    result("${mood.detail}\n\n${dev.jev.wechatmood.analysis.AnalysisThinking.description(settings)}")
                } else {
                    val reading = ReplyHttpClient().request(settings, IntentProtocol.payload(input, settings), IntentProtocol::parse)
                    if (ModulePrefs.analysisSettings()?.sameAnalysis(snapshot) != true) return@launch
                    status("意图模型检测通过", R.color.status_success)
                    testedFingerprint = snapshot.analysisFingerprint()
                    result("示例解读\n\n${reading.display()}\n\n此检测仅验证 LLM；情绪的 JEV 连接请在下方单独检测。")
                }
            } catch (e: CancellationException) {
                throw e
            }
            catch (e: Exception) {
                if (ModulePrefs.analysisSettings()?.sameAnalysis(snapshot) != true) return@launch
                testedFingerprint = snapshot.analysisFingerprint()
                status("${if (emotion.source == EmotionSource.LLM) "LLM 分析" else "意图"}检测失败", R.color.status_error)
                result(e.message ?: "检测失败，请重试", true)
            }
            finally {
                setBusy(false)
                if (ModulePrefs.analysisSettings()?.sameAnalysis(snapshot) != true) {
                    status("配置已变化 · 请重新检测", R.color.status_warning)
                    binding.intentResult.visibility = View.GONE
                }
            }
        }
    }
    fun onShown() {
        renderSharedConfig()
        if (testedFingerprint != null && testedFingerprint != ModulePrefs.analysisSettings()?.analysisFingerprint()) {
            testedFingerprint = null
            status("配置已变化 · 请重新检测", R.color.status_warning)
            binding.intentResult.visibility = View.GONE
        }
    }

    private fun renderSharedConfig() {
        val shared = ModulePrefs.replySettings()
        SettingsStatus.show(binding.reusedConfigStatus,
            if (shared.isConfigured) "当前复用：${shared.model}" else "回复模型尚未配置",
            if (shared.isConfigured) R.color.status_neutral else R.color.status_warning)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        listOf(binding.emotionSourceLayout, binding.configSourceLayout, binding.intentRouteLayout, binding.testSelected,
            binding.providerLayout, binding.endpointLayout, binding.keyLayout,
            binding.modelLayout, binding.saveIntent, binding.fetchModels, binding.referenceModels)
            .forEach { it.isEnabled = !value }
        binding.progressIntent.visibility = if (value) View.VISIBLE else View.GONE
    }
    private fun status(text: String, color: Int) {
        SettingsStatus.show(binding.intentStatus, text, color)
    }
    private fun result(text: String, error: Boolean = false) {
        binding.intentResult.text = text; binding.intentResult.visibility = View.VISIBLE
        SettingsStatus.show(binding.intentResult, text, if (error) R.color.status_error else R.color.status_neutral)
    }
}
