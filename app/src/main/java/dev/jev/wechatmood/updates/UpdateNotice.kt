package dev.jev.wechatmood.updates

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Exists only with the standalone settings Activity, never in the WeChat scanning loop. */
class UpdateNotice(private val activity: AppCompatActivity, private val binding: ActivityMainBinding,
    private val scope: CoroutineScope, private val openPage: (String) -> Unit) {
    private val prefs = activity.getSharedPreferences("release_updates", Context.MODE_PRIVATE)
    private val client = ReleaseClient(BuildConfig.VERSION_NAME)
    private var checking = false

    init {
        binding.buttonCheckUpdate.setOnClickListener { check(manual = true) }
        binding.buttonReleasePage.setOnClickListener {
            val release = cachedRelease()
            openPage(if (release?.isUpdateFor(BuildConfig.VERSION_NAME) == true) release.pageUrl else ReleaseInfo.RELEASES_URL)
        }
        render()
    }

    private fun cachedRelease(): ReleaseInfo? = runCatching {
        prefs.getString("release_json", null)?.let(ReleaseInfo::parse)
    }.getOrNull()

    fun onResume() = check(manual = false)

    private fun check(manual: Boolean) {
        if (checking) return
        val now = System.currentTimeMillis()
        if (!UpdateSchedule.shouldCheck(now, prefs.getLong("last_attempt", 0), manual,
                prefs.getBoolean("failed", false), prefs.getLong("blocked_until", 0))) {
            render()
            if (manual) Toast.makeText(activity, "刚刚检查过或 GitHub 暂时限流，请稍后重试，也可直接查看发布页", Toast.LENGTH_LONG).show()
            promptIfNeeded()
            return
        }
        checking = true
        // Persist the attempt before network I/O, including cancellation/rotation cases.
        prefs.edit().putLong("last_attempt", now).putBoolean("failed", true).apply()
        binding.buttonCheckUpdate.isEnabled = false
        binding.buttonCheckUpdate.text = "正在检查…"
        render()
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    client.fetch(if (cachedRelease() != null) prefs.getString("etag", null) else null)
                }
                val editor = prefs.edit()
                when (reply) {
                    is ReleaseReply.Found -> editor.putString("release_json", reply.json).putString("etag", reply.etag)
                    ReleaseReply.NotPublished -> editor.remove("release_json").remove("etag")
                    ReleaseReply.Unchanged -> if (cachedRelease() == null) {
                        prefs.edit().remove("etag").apply()
                        throw UpdateCheckException("本地版本记录已失效，请稍后重试")
                    }
                }
                editor.putLong("last_success", System.currentTimeMillis()).putBoolean("failed", false)
                    .remove("last_error").remove("blocked_until").apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val known = e as? UpdateCheckException
                prefs.edit().putBoolean("failed", true)
                    .putString("last_error", known?.message ?: "暂时无法检查更新，请稍后重试")
                    .putLong("blocked_until", System.currentTimeMillis() + (known?.retryAfterMs ?: 0)).apply()
            } finally {
                checking = false
                binding.buttonCheckUpdate.isEnabled = true
                binding.buttonCheckUpdate.text = "检查更新"
                render()
            }
            promptIfNeeded()
        }
    }

    private fun render() {
        val release = cachedRelease()
        val current = BuildConfig.VERSION_NAME
        val lastSuccess = prefs.getLong("last_success", 0)
        val localVersion = StableVersion.parse(current)
        val newer = release != null && localVersion != null && release.version > localVersion
        val state = when {
            release?.isUpdateFor(current) == true -> "发现新版本 ${release.version} · 当前 $current"
            newer -> "GitHub 已发布 ${release!!.version}，暂未提供 APK"
            lastSuccess > 0 && release == null -> "当前 $current · 暂无正式发布版本"
            lastSuccess > 0 -> "当前 $current · 暂无新版本"
            else -> "当前版本 $current"
        }
        val detail = when {
            checking -> "正在连接 GitHub…"
            prefs.getBoolean("failed", false) -> prefs.getString("last_error", "上次检查未完成，可稍后重试").orEmpty()
            else -> "打开言外时检查新版，点击后前往 GitHub 下载。"
        }
        val checked = if (lastSuccess > 0) "\n上次成功检查：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastSuccess))}" else ""
        binding.textUpdateStatus.text = "$state\n$detail$checked"
        binding.buttonReleasePage.text = if (release?.isUpdateFor(current) == true) "去 GitHub 下载" else "GitHub 发布页"
    }

    private fun promptIfNeeded() {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val release = cachedRelease() ?: return
        if (!release.isUpdateFor(BuildConfig.VERSION_NAME)) return
        val version = release.version.toString()
        if (prefs.getString("prompted_version", null) == version) return
        MaterialAlertDialogBuilder(activity).setTitle("发现新版本 $version")
            .setMessage("前往 GitHub 下载新版 APK，覆盖安装后打开一次言外，并重新启动微信。")
            .setPositiveButton("去 GitHub 下载") { _, _ -> openPage(release.pageUrl) }
            .setNegativeButton("稍后", null).show()
        prefs.edit().putString("prompted_version", version).apply()
    }
}
