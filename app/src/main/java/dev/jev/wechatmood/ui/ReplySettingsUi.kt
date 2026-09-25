package dev.jev.wechatmood.ui

import android.content.Context
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
    init {
        binding.endpoint.setText(prefs.getString(ReplySettings.KEY_ENDPOINT, ""))
        binding.apiKey.setText(prefs.getString(ReplySettings.KEY_API_KEY, ""))
        binding.model.setText(prefs.getString(ReplySettings.KEY_MODEL, ""))
        binding.replyConsent.isChecked = prefs.getBoolean(ReplySettings.KEY_CONSENT, false)
        status(if (prefs.getString(ReplySettings.KEY_MODEL, "").isNullOrBlank()) "尚未配置" else "已保存 · 尚未检测", R.color.status_neutral)
        listOf(binding.endpoint, binding.apiKey, binding.model).forEach { input -> input.doAfterTextChanged {
            if (!checking) { status("有未保存修改", R.color.status_warning); binding.replyResult.visibility = View.GONE }
        } }
        binding.replyConsent.setOnCheckedChangeListener { _, _ -> status("有未保存修改", R.color.status_warning) }
        binding.saveReply.setOnClickListener { save()?.let { result("回复配置已保存。点击「帮我回」时使用，与情绪判断互不影响。") } }
        binding.testReply.setOnClickListener { test() }
        binding.knowledgeSource.setOnClickListener { openUrl(ReplyKnowledge.SOURCE_URL) }
        binding.knowledgeLicense.setOnClickListener {
            AlertDialog.Builder(activity).setTitle("狗头军师 · MIT License")
                .setMessage(activity.assets.open("goutoujunshi/LICENSE").bufferedReader().use { it.readText() })
                .setPositiveButton("关闭", null).show()
        }
    }

    private fun save(): ReplySettings? {
        binding.endpointLayout.error = null; binding.keyLayout.error = null; binding.modelLayout.error = null
        val settings = try { ReplySettings.fromInput(binding.endpoint.text.toString(), binding.apiKey.text.toString(), binding.model.text.toString()) }
        catch (e: IllegalArgumentException) { result(e.message.orEmpty(), true); return null }
        if (binding.replyConsent.isChecked && !settings.isConfigured) {
            if (settings.endpoint.isBlank()) binding.endpointLayout.error = "请填写 API 地址"
            if (settings.apiKey.isBlank()) binding.keyLayout.error = "请填写 API Key"
            if (settings.model.isBlank()) binding.modelLayout.error = "请填写模型名"
            return null
        }
        MoodLog.protect(settings.apiKey)
        val saved = SettingsProvider.save(activity) {
            putString(ReplySettings.KEY_ENDPOINT, settings.endpoint)
            putString(ReplySettings.KEY_API_KEY, settings.apiKey)
            putString(ReplySettings.KEY_MODEL, settings.model)
            putBoolean(ReplySettings.KEY_CONSENT, binding.replyConsent.isChecked)
        }
        if (!saved) { result("保存失败，请重试", true); return null }
        ModulePrefs.reload(force = true)
        status(if (binding.replyConsent.isChecked) "已保存 · 允许手动生成" else "已保存 · 手动生成未开启", R.color.status_neutral)
        return settings
    }

    private fun test() {
        val settings = save() ?: return
        if (!settings.isConfigured) { result("请填写完整的地址、API Key 和模型名再检测", true); return }
        checking = true
        setEnabled(false)
        binding.progressReply.visibility = View.VISIBLE
        status("正在检测回复", R.color.status_neutral)
        result("使用示例聊天和完整知识资料检测，不读取微信消息。长上下文模型可能需要稍等。")
        scope.launch {
            try {
                val knowledge = withContext(Dispatchers.IO) { ReplyKnowledge.load(activity) }
                val example = ReplyContext("sample", listOf(
                    ReplyMessage(1, "我", System.currentTimeMillis() - 60000, "最近忙完了，周末想出去走走。"),
                    ReplyMessage(2, "对方", System.currentTimeMillis(), "好呀，你有什么想去的地方吗？")))
                val suggestion = ReplyHttpClient().generate(settings, example, "想去公园", "自然简短", knowledge)
                status("回复检测通过", R.color.status_success)
                result("示例回复：\n${suggestion.text}\n\n${suggestion.reason}\n\n接口和回复格式可用，聊天入口请在微信中体验。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status("检测失败", R.color.status_error); result(e.message ?: "检测失败，请重试", true) }
            finally { checking = false; setEnabled(true); binding.progressReply.visibility = View.GONE }
        }
    }
    private fun setEnabled(value: Boolean) {
        listOf(binding.endpoint, binding.apiKey, binding.model, binding.replyConsent, binding.saveReply, binding.testReply)
            .forEach { it.isEnabled = value }
        binding.testReply.text = if (value) "保存并检测回复" else "正在检测…"
    }
    private fun status(text: String, color: Int) {
        binding.replyStatus.text = text; binding.replyStatus.setTextColor(ContextCompat.getColor(activity, color))
    }
    private fun result(text: String, error: Boolean = false) {
        binding.replyResult.visibility = View.VISIBLE; binding.replyResult.text = text
        binding.replyResult.setTextColor(ContextCompat.getColor(activity, if (error) R.color.status_error else R.color.text_primary))
    }
}
