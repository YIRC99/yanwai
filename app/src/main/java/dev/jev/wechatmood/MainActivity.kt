package dev.jev.wechatmood

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.ApiSettings
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.SettingsProvider
import dev.jev.wechatmood.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var syncingSwitches = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        MoodLog.init(this)
        // Makes the settings provider visible to WeChat on Android 11+.
        // The provider validates the caller UID before sharing settings with WeChat.
        runCatching {
            grantUriPermission("com.tencent.mm", SettingsProvider.URI, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { MoodLog.w("微信设置连接授权失败：${it.javaClass.simpleName}") }
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        ModulePrefs.init(this)
        binding.inputApiBase.setText(prefs.getString(ModulePrefs.KEY_API_BASE, ApiSettings.DEFAULT_ENDPOINT))
        binding.inputApiKey.setText(prefs.getString(ModulePrefs.KEY_API_KEY, ""))
        binding.buttonSaveApi.setOnClickListener {
            if (saveApiSettings()) Toast.makeText(this, "配置已保存，后续请求使用新配置", Toast.LENGTH_SHORT).show()
        }
        binding.switchEnabled.isChecked = prefs.getBoolean(ModulePrefs.KEY_ENABLED, true)
        binding.switchBadge.isChecked = prefs.getBoolean(ModulePrefs.KEY_SHOW_BADGE, true)
        binding.switchExplore.isChecked = prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false)
        binding.switchEnabled.setOnCheckedChangeListener { _, value -> if (!syncingSwitches) save(ModulePrefs.KEY_ENABLED, value) }
        binding.switchBadge.setOnCheckedChangeListener { _, value -> if (!syncingSwitches) save(ModulePrefs.KEY_SHOW_BADGE, value) }
        binding.switchExplore.setOnCheckedChangeListener { _, value ->
            save(ModulePrefs.KEY_EXPLORE, value)
            Toast.makeText(this, "重新启动微信后生效", Toast.LENGTH_SHORT).show()
        }
        binding.buttonDebug.setOnClickListener {
            val open = binding.debugPanel.visibility != View.VISIBLE
            binding.debugPanel.visibility = if (open) View.VISIBLE else View.GONE
            binding.buttonDebug.text = if (open) "收起排查信息" else "展开排查信息"
        }
        binding.buttonRefreshLog.setOnClickListener { refresh() }
        binding.buttonCopyLog.setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Jev 检测记录", MoodLog.read()))
            Toast.makeText(this, "检测记录已复制", Toast.LENGTH_SHORT).show()
        }
        binding.buttonTestModel.setOnClickListener { testModel() }
    }

    private fun save(key: String, value: Boolean) {
        getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE).edit().putBoolean(key, value).commit()
        refresh()
    }

    private fun saveApiSettings(): Boolean {
        binding.layoutApiBase.error = null
        binding.layoutApiKey.error = null
        val endpoint = binding.inputApiBase.text?.toString().orEmpty()
        val key = binding.inputApiKey.text?.toString().orEmpty()
        try { ApiSettings.fromInput(endpoint, "") } catch (e: IllegalArgumentException) {
            binding.layoutApiBase.error = e.message
            binding.inputApiBase.requestFocus()
            return false
        }
        val settings = try { ApiSettings.fromInput(endpoint, key) } catch (e: IllegalArgumentException) {
            binding.layoutApiKey.error = e.message
            binding.inputApiKey.requestFocus()
            return false
        }
        val saved = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE).edit()
            .putString(ModulePrefs.KEY_API_BASE, settings.endpoint)
            .putString(ModulePrefs.KEY_API_KEY, settings.apiKey).commit()
        if (!saved) {
            Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            return false
        }
        binding.inputApiBase.setText(settings.endpoint)
        binding.textTestResult.visibility = View.GONE
        ModulePrefs.reload(force = true)
        refresh()
        return true
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        syncingSwitches = true
        binding.switchEnabled.isChecked = prefs.getBoolean(ModulePrefs.KEY_ENABLED, true)
        binding.switchBadge.isChecked = prefs.getBoolean(ModulePrefs.KEY_SHOW_BADGE, true)
        syncingSwitches = false
        binding.textModelStatus.text = if (prefs.getString(ModulePrefs.KEY_API_KEY, "").isNullOrBlank())
            "言外 ${BuildConfig.VERSION_NAME} · 请填写 API Key" else
            "言外 ${BuildConfig.VERSION_NAME} · 配置已保存，可检测连接"
        val wechat = runCatching {
            @Suppress("DEPRECATION")
            "微信 ${packageManager.getPackageInfo("com.tencent.mm", 0).versionName}"
        }.getOrDefault("未检测到微信")
        val runtime = getSharedPreferences(SettingsProvider.RUNTIME_FILE, MODE_PRIVATE)
        val last = runtime.getLong("last_seen", 0)
        val evidence = if (last == 0L) "尚未收到微信模块的运行记录" else
            "最近记录（${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last))}）：\n${runtime.getString("status", "")}"
        binding.textFrameworkStatus.text = "$wechat\n$evidence"
        binding.textLog.text = MoodLog.read().ifBlank { "暂无检测记录，可先检测模型连接。" }
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
        binding.buttonTestModel.text = "正在检测…"
        binding.textTestResult.visibility = View.VISIBLE
        binding.textTestResult.text = "正在用一条示例消息检测，不读取你的聊天。"
        uiScope.launch {
            try {
                val mood = SignalAnalyzer.requestMood("这还差不多。", listOf(
                    dev.jev.wechatmood.core.ContextMessage("对方", "你是不是忘了周末吃饭的事？"),
                    dev.jev.wechatmood.core.ContextMessage("我", "记得，这次我来安排，明天把餐厅和时间告诉你。")))
                binding.textTestResult.text = "模型连接成功\n${mood.detail}"
                MoodLog.i("模型连接检测成功")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                binding.textTestResult.text = "检测失败：${e.message}"
                MoodLog.e("模型连接检测失败：${e.message}")
            } finally {
                binding.buttonTestModel.isEnabled = true
                binding.buttonSaveApi.isEnabled = true
                binding.buttonTestModel.text = "重新检测模型连接"
                refresh()
            }
        }
    }

    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
