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
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.SettingsProvider
import dev.jev.wechatmood.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        val prefs = getSharedPreferences(ModulePrefs.FILE_NAME, MODE_PRIVATE)
        // First personal build migrates the old opt-in badge to the user's requested ready-to-use default.
        if (prefs.getInt("personal_defaults_version", 0) < 2) {
            prefs.edit().putBoolean(ModulePrefs.KEY_SHOW_BADGE, true)
                .putBoolean(ModulePrefs.KEY_ENABLED, prefs.getBoolean(ModulePrefs.KEY_ENABLED, true))
                .remove("api_key").remove("api_base").remove("api_model")
                .putInt("personal_defaults_version", 2).commit()
        }
        binding.switchEnabled.isChecked = prefs.getBoolean(ModulePrefs.KEY_ENABLED, true)
        binding.switchBadge.isChecked = prefs.getBoolean(ModulePrefs.KEY_SHOW_BADGE, true)
        binding.switchExplore.isChecked = prefs.getBoolean(ModulePrefs.KEY_EXPLORE, false)
        binding.switchEnabled.setOnCheckedChangeListener { _, value -> save(ModulePrefs.KEY_ENABLED, value) }
        binding.switchBadge.setOnCheckedChangeListener { _, value -> save(ModulePrefs.KEY_SHOW_BADGE, value) }
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

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        binding.textModelStatus.text = if (BuildConfig.JEV_API_KEY.isNotBlank())
            "${BuildConfig.JEV_MODEL} · 地址和密钥已内置" else "此安装包未包含密钥，请重新构建个人版"
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
        binding.buttonTestModel.isEnabled = false
        binding.buttonTestModel.text = "正在检测…"
        binding.textTestResult.visibility = View.VISIBLE
        binding.textTestResult.text = "正在用一条示例消息检测，不读取你的聊天。"
        uiScope.launch {
            try {
                val mood = SignalAnalyzer.requestMood("第一版先做商品展示，支付和物流后续再加，可以吗？")
                binding.textTestResult.text = "模型连接成功\n${mood.detail}"
                MoodLog.i("模型连接检测成功")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                binding.textTestResult.text = "检测失败：${e.message}"
                MoodLog.e("模型连接检测失败：${e.message}")
            } finally {
                binding.buttonTestModel.isEnabled = true
                binding.buttonTestModel.text = "重新检测模型连接"
                refresh()
            }
        }
    }

    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}