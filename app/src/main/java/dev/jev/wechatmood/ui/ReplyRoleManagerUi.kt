package dev.jev.wechatmood.ui

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jev.wechatmood.R
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.ReplyIdentityProvider
import dev.jev.wechatmood.core.SettingsProvider
import dev.jev.wechatmood.databinding.RoleManagerBinding
import dev.jev.wechatmood.reply.ReplyRole
import kotlinx.coroutines.*

/** All management runs in the module app; host callers cannot enumerate contact profiles. */
class ReplyRoleManagerUi(private val activity: AppCompatActivity, private val binding: RoleManagerBinding,
    private val scope: CoroutineScope, private val reveal: (View) -> Unit) {
    private var records = emptyList<ReplyRole>()
    private var editing: ReplyRole? = null
    private var busy = false
    private var load: Job? = null

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { onShown() }
        }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                activity.contentResolver.registerContentObserver(ReplyIdentityProvider.CHANGES_URI, false, observer)
            }
            override fun onStop(owner: LifecycleOwner) {
                activity.contentResolver.unregisterContentObserver(observer)
            }
        })
        binding.addRole.setOnClickListener { edit(null) }
        binding.refreshRoles.setOnClickListener { onShown() }
        binding.searchRoles.doAfterTextChanged { render() }
        binding.cancelEdit.setOnClickListener { closeEditor() }
        binding.saveRole.setOnClickListener {
            val name = binding.roleName.text.toString().trim()
            val background = binding.roleBackground.text.toString()
            val selected = editing
            binding.nameLayout.error = if (name.isBlank()) "请填写角色名称" else null
            if (name.isNotBlank()) mutate {
                ReplyIdentityProvider.saveRole(activity, selected?.id, name, background, selected?.revision)
            }
        }
    }
    fun onShown() {
        if (busy) return
        load?.cancel()
        load = scope.launch {
            binding.roleProgress.visibility = View.VISIBLE
            try {
                records = withContext(Dispatchers.IO) { ReplyIdentityProvider.roles(activity) }
                render()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { SettingsStatus.show(binding.roleStatus, "读取失败，点击刷新重试。", R.color.status_error) }
            finally { binding.roleProgress.visibility = View.GONE }
        }
    }
    private fun edit(role: ReplyRole?) {
        if (busy) return
        editing = role
        binding.editorTitle.text = if (role == null) "添加角色" else "编辑角色"
        binding.nameLayout.error = null
        binding.roleName.setText(role?.name.orEmpty())
        binding.roleBackground.setText(role?.background.orEmpty())
        binding.editorHint.text = if (role?.fromContact == true) "修改当前联系人的角色与背景，重新打开微信回复面板后使用。"
            else "这个角色有独立的背景。微信中选中它后，会使用这里的名称和背景；两边编辑会同步。"
        binding.editorCard.visibility = View.VISIBLE
        reveal(binding.editorCard)
        binding.roleName.requestFocus()
    }
    private fun closeEditor() {
        binding.editorCard.visibility = View.GONE
        editing = null
        binding.roleName.text?.clear(); binding.roleBackground.text?.clear()
        activity.currentFocus?.clearFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }
    private fun mutate(operation: () -> Unit) {
        if (busy) return
        load?.cancel()
        setBusy(true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    operation()
                    // Role revision is stored atomically with the data. Publish even if the unrelated preference revision fails.
                    if (!SettingsProvider.save(activity) {}) SettingsProvider.publish(activity)
                    ModulePrefs.reload(force = true)
                }
                closeEditor()
                records = withContext(Dispatchers.IO) { ReplyIdentityProvider.roles(activity) }
                render()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                SettingsStatus.show(binding.roleStatus, when (e) {
                    is IllegalArgumentException, is IllegalStateException -> e.message ?: "操作失败，请刷新后重试。"
                    else -> "保存失败，请重试。"
                }, R.color.status_error)
            } finally { setBusy(false) }
        }
    }
    private fun setBusy(value: Boolean) {
        busy = value
        listOf(binding.addRole, binding.saveRole, binding.cancelEdit, binding.roleName,
            binding.roleBackground, binding.refreshRoles, binding.searchRoles).forEach { it.isEnabled = !value }
        binding.roleProgress.visibility = if (value) View.VISIBLE else View.GONE
    }
    private fun render() {
        binding.roleList.removeAllViews()
        val query = binding.searchRoles.text.toString().trim()
        val visible = records.filter { query.isEmpty() || it.name.contains(query, true) || it.background.contains(query, true) }
        SettingsStatus.show(binding.roleStatus, when {
            records.isEmpty() -> "还没有角色，点击上方添加。"
            visible.isEmpty() -> "没有找到匹配的角色。"
            else -> "共 ${visible.size} 个角色"
        })
        listOf(false, true).forEach { fromContact ->
            val group = visible.filter { it.fromContact == fromContact }
            if (group.isNotEmpty()) {
                binding.roleList.addView(label(if (fromContact) "聊天中保存" else "我的角色", 16f, R.color.text_primary).apply {
                    setPadding(0, dp(16), 0, dp(8))
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                })
                group.forEach { addRow(it) }
            }
        }
    }
    private fun addRow(role: ReplyRole) {
        val card = MaterialCardView(activity).apply {
            radius = dp(16).toFloat(); cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(activity, R.color.panel_surface))
            strokeWidth = dp(1); strokeColor = ContextCompat.getColor(activity, R.color.outline_subtle)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        content.addView(label(role.name, 17f, R.color.text_primary))
        content.addView(label(role.background.ifBlank { "暂未填写背景" }, 13f, R.color.text_secondary).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, dp(4))
        })
        val actions = LinearLayout(activity)
        actions.addView(button("查看 / 编辑") { edit(role) }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("删除") {
            if (!busy) MaterialAlertDialogBuilder(activity).setTitle("删除这个角色？")
                .setMessage(if (role.fromContact) "会清除该联系人的身份与背景。" else "从角色库移除；使用它的聊天需要重新选择角色。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> mutate { ReplyIdentityProvider.deleteRole(activity, role) } }.show()
        }.apply { setTextColor(ContextCompat.getColor(activity, R.color.status_error)) }, LinearLayout.LayoutParams(-2, -2))
        content.addView(actions); card.addView(content)
        binding.roleList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    private fun button(text: String, click: () -> Unit) = MaterialButton(activity, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        this.text = text; isAllCaps = false; minHeight = dp(48); setOnClickListener { if (!busy) click() }
    }
    private fun label(text: String, size: Float, color: Int) = TextView(activity).apply {
        this.text = text; textSize = size; setTextColor(ContextCompat.getColor(activity, color)); isSaveEnabled = false
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
}
