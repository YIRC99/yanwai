package com.dogheadstrategist.chat.ui

import android.view.View
import android.widget.TextView
import com.dogheadstrategist.chat.databinding.ReplySettingsBackupBinding
import com.dogheadstrategist.chat.databinding.ReplySettingsBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 模型配置页的视图集合。
 *
 * 主 AI 和备用 AI 用的是同一套交互，只是控件 ID 不同（两个独立 include）。
 * 抽这层接口后，[ReplySettingsUi] 写一次就能同时驱动两套配置。
 */
interface ReplySetupViews {
    val root: View
    val replyStatus: TextView
    val endpointLayout: TextInputLayout
    val endpoint: TextInputEditText
    val keyLayout: TextInputLayout
    val apiKey: TextInputEditText
    val fetchModels: MaterialButton
    val modelsStatus: TextView
    val modelLayout: TextInputLayout
    val model: MaterialAutoCompleteTextView
    val testReply: MaterialButton
    val saveReply: MaterialButton
    val progressReply: View
    val replyResult: TextView
}

class PrimarySetupViews(private val binding: ReplySettingsBinding) : ReplySetupViews {
    override val root get() = binding.root
    override val replyStatus get() = binding.replyStatus
    override val endpointLayout get() = binding.endpointLayout
    override val endpoint get() = binding.endpoint
    override val keyLayout get() = binding.keyLayout
    override val apiKey get() = binding.apiKey
    override val fetchModels get() = binding.fetchModels
    override val modelsStatus get() = binding.modelsStatus
    override val modelLayout get() = binding.modelLayout
    override val model get() = binding.model
    override val testReply get() = binding.testReply
    override val saveReply get() = binding.saveReply
    override val progressReply get() = binding.progressReply
    override val replyResult get() = binding.replyResult
}

class BackupSetupViews(private val binding: ReplySettingsBackupBinding) : ReplySetupViews {
    override val root get() = binding.root
    override val replyStatus get() = binding.backupReplyStatus
    override val endpointLayout get() = binding.backupEndpointLayout
    override val endpoint get() = binding.backupEndpoint
    override val keyLayout get() = binding.backupKeyLayout
    override val apiKey get() = binding.backupApiKey
    override val fetchModels get() = binding.backupFetchModels
    override val modelsStatus get() = binding.backupModelsStatus
    override val modelLayout get() = binding.backupModelLayout
    override val model get() = binding.backupModel
    override val testReply get() = binding.backupTestReply
    override val saveReply get() = binding.backupSaveReply
    override val progressReply get() = binding.backupProgressReply
    override val replyResult get() = binding.backupReplyResult
}
