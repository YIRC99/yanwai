package com.dogheadstrategist.chat.ui

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.dogheadstrategist.chat.R
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.SettingsProvider
import com.dogheadstrategist.chat.reply.*
import kotlinx.coroutines.*

class ReplySettingsUi(private val activity: AppCompatActivity, private val views: ReplySetupViews,
    private val scope: CoroutineScope, private val backup: Boolean = false, openUrl: (String) -> Unit) {
    private val prefs = activity.getSharedPreferences(ModulePrefs.FILE_NAME, Context.MODE_PRIVATE)

    /**
     * 备用配置走独立命名空间。
     *
     * 模型三项必须落到同步用的 reply_backup_* 键（微信进程按这些键读取），
     * 其余键加 backup_ 前缀，与主配置互不覆盖。
     */
    private fun key(base: String): String = if (!backup) base else when (base) {
        ReplySettings.KEY_ENDPOINT -> ReplySettings.KEY_BACKUP_ENDPOINT
        ReplySettings.KEY_API_KEY -> ReplySettings.KEY_BACKUP_API_KEY
        ReplySettings.KEY_MODEL -> ReplySettings.KEY_BACKUP_MODEL
        else -> "backup_" + base
    }
    private var checking = false
    private var rendering = false
    private var modelOptions = emptyList<String>()
    init {
        showProvider()
        status(if (prefs.getString(key(ReplySettings.KEY_MODEL), "").isNullOrBlank()) "尚未配置" else "已保存 · 尚未检测", R.color.status_neutral)
        listOf(views.endpoint, views.apiKey, views.model).forEach { input -> input.doAfterTextChanged {
            if (!checking && !rendering) {
                markDirty()
                if (input != views.model) clearModels()
            }
        } }
        views.fetchModels.setOnClickListener { fetchModels() }
        views.saveReply.setOnClickListener {
            save()?.let {
                views.replyResult.visibility = View.GONE
                android.widget.Toast.makeText(activity, "回复配置已保存", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        views.testReply.setOnClickListener { test() }
    }

    /**
     * 把已保存的配置读进输入框。
     *
     * 供应商下拉去掉之后没有预设可选了，API 地址一律可编辑——所以这里
     * 不再有「只有自定义才显示地址框」的逻辑。
     */
    private fun showProvider() {
        rendering = true
        views.endpoint.setText(prefs.getString(key(ReplySettings.KEY_ENDPOINT), "").orEmpty())
        views.apiKey.setText(prefs.getString(key(ReplySettings.KEY_API_KEY), "").orEmpty())
        views.model.setText(prefs.getString(key(ReplySettings.KEY_MODEL), "").orEmpty(), false)
        views.endpointLayout.visibility = View.VISIBLE
        views.endpointLayout.error = null; views.keyLayout.error = null; views.modelLayout.error = null
        clearModels()
        rendering = false
    }

    private fun markDirty() {
        status("有未保存修改", R.color.status_warning)
        views.replyResult.visibility = View.GONE
    }

    private fun setModels(models: List<String>) {
        modelOptions = models
        views.model.setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, models))
        views.modelLayout.isEndIconVisible = models.isNotEmpty()
    }

    private fun clearModels() {
        setModels(emptyList())
        SettingsStatus.show(views.modelsStatus, "填写 Key 后获取，或直接输入模型 ID。")
    }

    private fun showModelChoices() {
        if (!views.root.isShown || activity.isFinishing || activity.isDestroyed) return
        views.model.requestFocus()
        views.model.showDropDown()
    }

    private fun readSettings(requireModel: Boolean): ReplySettings? {
        views.endpointLayout.error = null; views.keyLayout.error = null; views.modelLayout.error = null
        val settings = try { ReplySettings.fromInput(views.endpoint.text.toString(), views.apiKey.text.toString(),
            if (requireModel) views.model.text.toString() else "") }
        catch (e: IllegalArgumentException) { result(e.message.orEmpty(), true); return null }
        if (settings.endpoint.isBlank() || settings.apiKey.isBlank() || requireModel && settings.model.isBlank()) {
            if (settings.endpoint.isBlank()) views.endpointLayout.error = "请填写 API 地址"
            if (settings.apiKey.isBlank()) views.keyLayout.error = "请填写 API Key"
            if (requireModel && settings.model.isBlank()) views.modelLayout.error = "请选择或填写模型 ID"
            return null
        }
        MoodLog.protect(settings.apiKey)
        return settings
    }

    private fun save(): ReplySettings? {
        val settings = readSettings(true) ?: return null
        MoodLog.protect(settings.apiKey)
        val saved = SettingsProvider.save(activity) {
            putString(key(ReplySettings.KEY_ENDPOINT), settings.endpoint)
            putString(key(ReplySettings.KEY_API_KEY), settings.apiKey)
            putString(key(ReplySettings.KEY_MODEL), settings.model)
            putBoolean(key(ReplySettings.KEY_CONSENT), true) // Compatibility with older running host processes.
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
        views.fetchModels.text = "正在获取…"
        SettingsStatus.show(views.modelsStatus, "正在获取模型列表…", R.color.status_info)
        scope.launch {
            var loaded = false
            try {
                val models = ReplyModelsClient().list(settings)
                setModels(models)
                SettingsStatus.show(views.modelsStatus, "已获取 ${models.size} 个候选模型；选定后请检测。", if (models.isEmpty()) R.color.status_warning else R.color.status_success)
                loaded = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { SettingsStatus.show(views.modelsStatus, e.message ?: "获取失败，可重试或手动填写模型 ID", R.color.status_error) }
            finally {
                setBusy(false)
                views.fetchModels.text = "获取模型列表"
            }
            if (loaded) showModelChoices()
        }
    }

    private fun test() {
        if (checking) return
        readSettings(true) ?: return
        val settings = save() ?: return
        setBusy(true)
        views.testReply.text = "正在检测…"
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
        listOf(views.endpointLayout, views.keyLayout, views.modelLayout,
            views.saveReply, views.testReply, views.fetchModels)
            .forEach { it.isEnabled = !busy }
        views.modelLayout.isEndIconVisible = modelOptions.isNotEmpty()
        views.progressReply.visibility = if (busy) View.VISIBLE else View.GONE
    }
    private fun status(text: String, color: Int) {
        SettingsStatus.show(views.replyStatus, text, color)
        SettingsStatus.show(views.testReply, when (color) {
            R.color.status_success -> "连接成功 · 重新检测"
            R.color.status_error -> "连接失败 · 点击重试"
            R.color.status_info -> "正在检测…"
            else -> "保存并检测回复"
        }, color)
    }
    private fun result(text: String, error: Boolean = false) {
        views.replyResult.visibility = View.VISIBLE; views.replyResult.text = text
        SettingsStatus.show(views.replyResult, text, if (error) R.color.status_error else R.color.status_neutral)
    }
}
