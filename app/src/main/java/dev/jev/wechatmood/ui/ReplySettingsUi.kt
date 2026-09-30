package dev.jev.wechatmood.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import dev.jev.wechatmood.R
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.SettingsProvider
import dev.jev.wechatmood.databinding.ReplySettingsBinding
import dev.jev.wechatmood.reply.*
import kotlinx.coroutines.*

class ReplySettingsUi(private val activity: AppCompatActivity, private val binding: ReplySettingsBinding,
    private val scope: CoroutineScope, openUrl: (String) -> Unit) {
    private val prefs = activity.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)
    private var checking = false
    private var rendering = false
    private var provider = ReplyProvider.resolve(prefs.getString(ReplyProfiles.KEY_PROVIDER, null),
        prefs.getString(ReplySettings.KEY_ENDPOINT, "").orEmpty()).let {
        if (prefs.getString(ReplySettings.KEY_ENDPOINT, "").isNullOrBlank()) ReplyProvider.DEEPSEEK else it
    }
    // Uncommitted inputs survive switching providers within this page, never cross to another provider.
    private class Draft(val endpoint: String, val key: String, val model: String)
    private val drafts = mutableMapOf<ReplyProvider, Draft>()
    private var modelOptions = emptyList<String>()
    init {
        binding.provider.setSimpleItems(ReplyProvider.entries.map { it.label }.toTypedArray())
        showProvider()
        status(if (prefs.getString(ReplySettings.KEY_MODEL, "").isNullOrBlank()) "尚未配置" else "已保存 · 尚未检测", R.color.status_neutral)
        listOf(binding.endpoint, binding.apiKey, binding.model).forEach { input -> input.doAfterTextChanged {
            if (!checking && !rendering) {
                markDirty()
                if (input != binding.model) clearModels()
            }
        } }
        binding.provider.setOnItemClickListener { _, _, position, _ ->
            if (!checking) {
                drafts[provider] = Draft(binding.endpoint.text.toString(), binding.apiKey.text.toString(), binding.model.text.toString())
                provider = ReplyProvider.entries[position]
                showProvider()
                markDirty()
            }
        }
        binding.providerConsole.setOnClickListener { if (provider.consoleUrl.isNotBlank()) openUrl(provider.consoleUrl) }
        binding.fetchModels.setOnClickListener { fetchModels() }
        binding.referenceModels.setOnClickListener {
            setModels(provider.referenceModels)
            SettingsStatus.show(binding.modelsStatus, "文档参考模型，不代表账户可用；选择后请检测。", R.color.status_warning)
            showModelChoices()
        }
        binding.saveReply.setOnClickListener {
            save()?.let {
                binding.replyResult.visibility = View.GONE
                android.widget.Toast.makeText(activity, "回复配置已保存", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        binding.testReply.setOnClickListener { test() }
    }

    private fun showProvider() {
        rendering = true
        val draft = drafts[provider] ?: ReplyProfiles.load(provider) { prefs.getString(it, null) }.let { Draft(it.endpoint, it.apiKey, it.model) }
        binding.provider.setText(provider.label, false)
        binding.endpoint.setText(draft.endpoint)
        binding.apiKey.setText(draft.key)
        binding.model.setText(draft.model, false)
        binding.endpointLayout.visibility = if (provider == ReplyProvider.CUSTOM) View.VISIBLE else View.GONE
        binding.providerHint.text = provider.hint
        binding.providerConsole.visibility = if (provider.consoleUrl.isBlank()) View.GONE else View.VISIBLE
        binding.referenceModels.visibility = if (provider.referenceModels.isEmpty()) View.GONE else View.VISIBLE
        binding.endpointLayout.error = null; binding.keyLayout.error = null; binding.modelLayout.error = null
        clearModels()
        rendering = false
    }

    private fun markDirty() {
        status("有未保存修改", R.color.status_warning)
        binding.replyResult.visibility = View.GONE
    }

    private fun setModels(models: List<String>) {
        modelOptions = models
        binding.model.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, models))
        binding.modelLayout.isEndIconVisible = models.isNotEmpty()
    }

    private fun clearModels() {
        setModels(emptyList())
        SettingsStatus.show(binding.modelsStatus, "填写 Key 后获取，或直接输入模型 ID。")
    }

    private fun showModelChoices() {
        if (!binding.root.isShown || activity.isFinishing || activity.isDestroyed) return
        binding.model.requestFocus()
        binding.model.showDropDown()
    }

    private fun readSettings(requireModel: Boolean): ReplySettings? {
        binding.endpointLayout.error = null; binding.keyLayout.error = null; binding.modelLayout.error = null
        val settings = try { ReplySettings.fromInput(binding.endpoint.text.toString(), binding.apiKey.text.toString(),
            if (requireModel) binding.model.text.toString() else "") }
        catch (e: IllegalArgumentException) { result(e.message.orEmpty(), true); return null }
        if (settings.endpoint.isBlank() || settings.apiKey.isBlank() || requireModel && settings.model.isBlank()) {
            if (settings.endpoint.isBlank()) binding.endpointLayout.error = "请填写 API 地址"
            if (settings.apiKey.isBlank()) binding.keyLayout.error = "请填写 API Key"
            if (requireModel && settings.model.isBlank()) binding.modelLayout.error = "请选择或填写模型 ID"
            return null
        }
        MoodLog.protect(settings.apiKey)
        return settings
    }

    private fun save(): ReplySettings? {
        val settings = readSettings(true) ?: return null
        MoodLog.protect(settings.apiKey)
        val values = ReplyProfiles.valuesToSave(provider, settings) { prefs.getString(it, null) }
        val saved = SettingsProvider.save(activity) {
            values.forEach { (key, value) -> putString(key, value) }
            putBoolean(ReplySettings.KEY_CONSENT, true) // Compatibility with older running host processes.
        }
        if (!saved) { result("保存失败，请重试", true); return null }
        ModulePrefs.reload(force = true)
        status("已保存 · 可在微信中手动生成", R.color.status_neutral)
        return settings
    }

    private fun fetchModels() {
        if (checking) return
        val settings = readSettings(false) ?: return
        clearModels()
        setBusy(true)
        binding.fetchModels.text = "正在获取…"
        SettingsStatus.show(binding.modelsStatus, "正在获取模型列表…", R.color.status_info)
        scope.launch {
            var loaded = false
            try {
                val models = ReplyModelsClient().list(settings)
                setModels(models)
                SettingsStatus.show(binding.modelsStatus, "已获取 ${models.size} 个候选模型；选定后请检测。", if (models.isEmpty()) R.color.status_warning else R.color.status_success)
                loaded = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { SettingsStatus.show(binding.modelsStatus, e.message ?: "获取失败，可重试或手动填写模型 ID", R.color.status_error) }
            finally {
                setBusy(false)
                binding.fetchModels.text = "获取模型列表"
            }
            if (loaded) showModelChoices()
        }
    }

    private fun test() {
        if (checking) return
        readSettings(true) ?: return
        val settings = save() ?: return
        setBusy(true)
        binding.testReply.text = "正在检测…"
        status("正在检测回复", R.color.status_info)
        result("使用示例聊天和相关知识资料检测，不读取微信消息。")
        scope.launch {
            try {
                val knowledge = withContext(Dispatchers.IO) { ReplyKnowledge.load(activity) }
                val example = ReplyContext("sample", listOf(
                    ReplyMessage(1, "我", System.currentTimeMillis() - 60000, "最近忙完了，周末想出去走走。"),
                    ReplyMessage(2, "对方", System.currentTimeMillis(), "好呀，你有什么想去的地方吗？")))
                val suggestion = ReplyHttpClient().generate(settings, example, "想去公园", "自然简短", knowledge)
                status("回复连接成功 · 可手动生成", R.color.status_success)
                val preview = suggestion.parts.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n\n")
                result("示例回复（${suggestion.parts.size} 条）：\n$preview\n\n${suggestion.reason}\n\n接口和回复格式可用，聊天入口请在微信中体验。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status("检测失败", R.color.status_error); result(e.message ?: "检测失败，请重试", true) }
            finally { setBusy(false) }
        }
    }
    private fun setBusy(busy: Boolean) {
        checking = busy
        listOf(binding.endpointLayout, binding.keyLayout, binding.modelLayout, binding.providerLayout,
            binding.saveReply, binding.testReply, binding.fetchModels, binding.referenceModels)
            .forEach { it.isEnabled = !busy }
        binding.modelLayout.isEndIconVisible = modelOptions.isNotEmpty()
        binding.progressReply.visibility = if (busy) View.VISIBLE else View.GONE
    }
    private fun status(text: String, color: Int) {
        SettingsStatus.show(binding.replyStatus, text, color)
        SettingsStatus.show(binding.testReply, when (color) {
            R.color.status_success -> "连接成功 · 重新检测"
            R.color.status_error -> "连接失败 · 点击重试"
            R.color.status_info -> "正在检测…"
            else -> "保存并检测回复"
        }, color)
    }
    private fun result(text: String, error: Boolean = false) {
        binding.replyResult.visibility = View.VISIBLE; binding.replyResult.text = text
        SettingsStatus.show(binding.replyResult, text, if (error) R.color.status_error else R.color.status_neutral)
    }
}
