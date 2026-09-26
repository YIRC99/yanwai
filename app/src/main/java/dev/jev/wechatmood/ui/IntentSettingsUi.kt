package dev.jev.wechatmood.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import dev.jev.wechatmood.R
import dev.jev.wechatmood.analysis.IntentProtocol
import dev.jev.wechatmood.core.*
import dev.jev.wechatmood.databinding.IntentSettingsBinding
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*

class IntentSettingsUi(private val activity: AppCompatActivity, private val binding: IntentSettingsBinding,
    private val scope: CoroutineScope, openUrl: (String) -> Unit, private val routeChanged: (IntentRoute) -> Unit) {
    private val prefs = activity.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
    private var route = IntentRoute.resolve(prefs.getString(IntentSettings.KEY_ROUTE, null))
    private var provider = ReplyProvider.resolve(prefs.getString(IntentProfiles.KEY_PROVIDER, null),
        prefs.getString(IntentSettings.KEY_ENDPOINT, "").orEmpty()).let {
        if (prefs.getString(IntentSettings.KEY_ENDPOINT, "").isNullOrBlank()) ReplyProvider.DEEPSEEK else it
    }
    private var rendering = false
    private var busy = false
    private class Draft(val endpoint: String, val key: String, val model: String)
    private val drafts = mutableMapOf<ReplyProvider, Draft>()

    init {
        binding.provider.setSimpleItems(ReplyProvider.entries.map { it.label }.toTypedArray())
        showProvider()
        binding.routeGroup.check(if (route == IntentRoute.LLM) R.id.routeLlm else R.id.routeJev)
        showRoute()
        status("当前：${route.label}" + if (route == IntentRoute.LLM) " · 尚未检测" else "", R.color.status_neutral)
        binding.routeGroup.setOnCheckedChangeListener { _, checked ->
            route = if (checked == R.id.routeLlm) IntentRoute.LLM else IntentRoute.JEV
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
            binding.modelsStatus.text = "文档参考模型，不代表账户可用；选择后请检测。"
            binding.model.requestFocus(); binding.model.showDropDown()
        }
        binding.saveIntent.setOnClickListener {
            if (save()) result(if (route == IntentRoute.JEV) "已使用 JEV 决策模型。" else
                "已开启智能分析；情绪概率仍使用 JEV。可分别检测两种连接。")
        }
        binding.testIntent.setOnClickListener { test() }
    }

    private fun showRoute() {
        binding.llmPanel.visibility = if (route == IntentRoute.LLM) View.VISIBLE else View.GONE
        binding.routeHint.text = if (route == IntentRoute.JEV)
            "从内置选项中判断意图，响应较快；不会自由生成对方在意的点。下方只需连接 JEV。" else
            "结合前文解释意图、可能在意的点和情绪倾向。等待更久，解读也可能有误；先显示 JEV 情绪，再补充解读。"
        routeChanged(route)
    }

    private fun showProvider() {
        rendering = true
        val draft = drafts[provider] ?: IntentProfiles.load(provider) { prefs.getString(it, null) }
            .let { Draft(it.endpoint, it.apiKey, it.model) }
        binding.provider.setText(provider.label, false)
        binding.endpoint.setText(draft.endpoint); binding.apiKey.setText(draft.key); binding.model.setText(draft.model, false)
        binding.endpointLayout.visibility = if (provider == ReplyProvider.CUSTOM) View.VISIBLE else View.GONE
        // Preset addresses add no decision value; custom services expose the editable address.
        binding.providerAddress.visibility = View.GONE
        binding.providerHint.text = provider.hint
        binding.providerConsole.visibility = if (provider.consoleUrl.isBlank()) View.GONE else View.VISIBLE
        binding.referenceModels.visibility = if (provider.referenceModels.isEmpty()) View.GONE else View.VISIBLE
        binding.endpointLayout.error = null; binding.keyLayout.error = null; binding.modelLayout.error = null
        clearModels()
        rendering = false
    }

    private fun dirty() {
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
        val values = if (route == IntentRoute.LLM) {
            val settings = read() ?: return false
            IntentProfiles.valuesToSave(provider, settings) { prefs.getString(it, null) }
        } else emptyMap()
        if (!SettingsProvider.save(activity) {
            putString(IntentSettings.KEY_ROUTE, route.id)
            values.forEach { (key, value) -> putString(key, value) }
        }) { result("保存失败，请重试", true); return false }
        ModulePrefs.reload(force = true)
        status("已保存：${route.label}" + if (route == IntentRoute.LLM) " · 尚未检测" else "", R.color.status_neutral)
        return true
    }

    private fun setModels(models: List<String>) {
        binding.model.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, models))
        binding.modelLayout.isEndIconVisible = models.isNotEmpty()
    }
    private fun clearModels() { setModels(emptyList()); binding.modelsStatus.text = "填写 Key 后获取，或直接输入模型 ID。" }

    private fun fetchModels() {
        if (busy) return
        val settings = read(false) ?: return
        setBusy(true)
        binding.modelsStatus.text = "正在获取模型列表，不会发送聊天内容…"
        scope.launch {
            try {
                val models = ReplyModelsClient().list(settings)
                setModels(models)
                binding.modelsStatus.text = "已获取 ${models.size} 个候选模型；选定后请检测意图解读。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { binding.modelsStatus.text = e.message ?: "获取失败，可手动填写模型 ID" }
            finally { setBusy(false) }
        }
    }

    private fun test() {
        if (busy) return
        val settings = read() ?: return
        if (!save()) return
        setBusy(true)
        status("正在检测意图解读…", R.color.status_neutral)
        result("使用示例聊天检测，不读取微信消息。")
        scope.launch {
            try {
                val input = AnalysisInput("睡吧睡吧", "sample", listOf(ContextMessage("我", "累了一天，终于躺下了。")))
                val reading = ReplyHttpClient().request(settings, IntentProtocol.payload(input, settings), IntentProtocol::parse)
                status("意图模型检测通过", R.color.status_success)
                result("示例解读\n\n${reading.display()}\n\n此检测仅验证 LLM；情绪的 JEV 连接请在下方单独检测。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status("意图检测失败", R.color.status_error); result(e.message ?: "检测失败，请重试", true) }
            finally { setBusy(false) }
        }
    }
    private fun setBusy(value: Boolean) {
        busy = value
        listOf(binding.routeJev, binding.routeLlm, binding.providerLayout, binding.endpointLayout, binding.keyLayout,
            binding.modelLayout, binding.testIntent, binding.saveIntent, binding.fetchModels, binding.referenceModels)
            .forEach { it.isEnabled = !value }
        binding.progressIntent.visibility = if (value) View.VISIBLE else View.GONE
    }
    private fun status(text: String, color: Int) {
        binding.intentStatus.text = text
        binding.intentStatus.setTextColor(ContextCompat.getColor(activity, color))
    }
    private fun result(text: String, error: Boolean = false) {
        binding.intentResult.text = text; binding.intentResult.visibility = View.VISIBLE
        binding.intentResult.setTextColor(ContextCompat.getColor(activity, if (error) R.color.status_error else R.color.text_primary))
    }
}
