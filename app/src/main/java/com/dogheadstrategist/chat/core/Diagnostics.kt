package com.dogheadstrategist.chat.core

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import com.dogheadstrategist.chat.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Host-side collection and export deliberately do not call SettingsProvider. */
object Diagnostics {
    fun environment(context: Context): String = buildString {
        appendLine("module=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("process=${context.packageName} uid=${Process.myUid()} user=${Process.myUserHandle()} pid=${Process.myPid()}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        for (pkg in listOf("com.tencent.mm", "com.dogheadstrategist.chat")) {
            val version = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0).let { "${it.versionName} (${it.longVersionCode})" }
            }.getOrElse { "当前进程查询不可见：${it.javaClass.simpleName}（不等于没有安装）" }
            appendLine("$pkg=$version")
        }
    }

    fun collect(context: Context): String = MoodLog.sanitize(buildString {
        appendLine("狗头军师运行诊断 ${Date()}")
        appendLine(environment(context))
        appendLine("verifiedSettings=${ModulePrefs.bridgeAvailable}")
        appendLine("replyConfigured=${ModulePrefs.replySettings().isConfigured} manualReplyReady=${ModulePrefs.canGenerateReply}")
        appendLine("bridgeError=${ModulePrefs.lastBridgeError ?: "无已记录错误"}")
        appendLine("--- 当前进程日志（有容量上限，包含重启前保留记录） ---")
        appendLine(MoodLog.read().ifBlank { "尚无记录" })
        if (context.packageName == "com.dogheadstrategist.chat") {
            val prefs = context.getSharedPreferences(SettingsProvider.RUNTIME_FILE, 0)
            val last = prefs.getLong("host_log_at", 0L)
            appendLine("--- 微信最近上报日志（缓存，不代表当前连接正常） ---")
            if (last == 0L) appendLine("未收到微信日志。请在微信内的狗头军师设置入口选择导出运行日志；也可导出 LSP 日志。")
            else {
                appendLine("上报时间=${Date(last)}")
                appendLine(prefs.getString("host_log", ""))
            }
        }
    })

    fun showFailure(activity: Activity, title: String, detail: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = "${MoodLog.sanitize(detail).take(700)}\n\n如果从桌面能打开狗头军师，但微信里无法打开或保存，请检查隐藏应用列表（HMA）是否对微信隐藏了狗头军师，以及微信分身和狗头军师是否在同一空间。允许微信访问狗头军师后重启微信。\n可直接在此导出日志，无需进入狗头军师设置。"
        AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton("导出运行日志") { _, _ -> export(activity) }
            .setNeutralButton("复制诊断") { _, _ -> copy(activity, collect(activity)) }
            .setNegativeButton("关闭", null).show()
    }

    fun showSettingsConnectionHelp(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle("暂时无法连接狗头军师")
            .setMessage("自动连接未成功，不需要反复测试模型。\n\n如果经常出现，请在手机设置中允许狗头军师自启动、关联启动和后台运行；不同手机的入口名称可能不同。\n\n如果使用隐藏应用列表，请不要对 wx 隐藏狗头军师；分身 wx 与狗头军师也需要在同一空间。设置后回到聊天重试。")
            .setPositiveButton("打开应用设置") { _, _ ->
                runCatching {
                    activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${BuildConfig.APPLICATION_ID}")))
                }.onFailure { Toast.makeText(activity, "请在系统设置中找到狗头军师，检查后台运行权限", Toast.LENGTH_LONG).show() }
            }
            .setNeutralButton("导出日志") { _, _ -> export(activity) }
            .setNegativeButton("稍后", null).show()
    }

    fun show(activity: Activity) {
        val report = collect(activity)
        val view = TextView(activity).apply {
            text = report; textSize = 12f; setTextIsSelectable(true); setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(activity).setTitle("运行日志")
            .setView(ScrollView(activity).apply { addView(view) })
            .setPositiveButton("保存日志文件") { _, _ -> export(activity) }
            .setNeutralButton("复制全部") { _, _ -> copy(activity, report) }
            .setNegativeButton("关闭", null).show()
    }


    /** 复制诊断报告到剪贴板。对话框里的「复制诊断 / 复制日志」都走这里。 */
    fun copy(activity: Activity, report: String = collect(activity)) {
        runCatching {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("狗头军师诊断", report))
            Toast.makeText(activity, "运行日志已复制", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(activity, "复制失败，请改用导出", Toast.LENGTH_SHORT).show()
        }
    }

    fun export(activity: Activity) {
        val report = collect(activity)
        if (Build.VERSION.SDK_INT < 29) {
            // Android 9 has no permission-free Downloads insertion; retain full selectable text.
            copy(activity, report)
            return
        }
        val context = activity.applicationContext
        val name = "yanwai-${if (activity.packageName == "com.tencent.mm") "wechat" else "app"}-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"
        Thread({
            val result = runCatching {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Goutoujunshi")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "无法创建日志文件" }
                try {
                    requireNotNull(resolver.openOutputStream(uri)) { "无法写入日志文件" }.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                    check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) > 0) { "日志文件未完成发布" }
                    uri
                } catch (error: Exception) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw error
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { uri ->
                    AlertDialog.Builder(activity).setTitle("日志已保存")
                        .setMessage("下载目录 / Goutoujunshi / $name")
                        .setPositiveButton("分享文件") { _, _ ->
                            runCatching { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("狗头军师日志", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "分享运行日志")) }.onFailure {
                                MoodLog.e("LOG_SHARE_FAILED 文件已保存在下载目录", it)
                                Toast.makeText(activity, "无法打开分享，请从下载目录取文件", Toast.LENGTH_LONG).show()
                            }
                        }.setNegativeButton("完成", null).show()
                }.onFailure {
                    MoodLog.e("LOG_EXPORT_FAILED", it)
                    AlertDialog.Builder(activity).setTitle("文件保存失败")
                        .setMessage("${it.javaClass.simpleName}；可复制完整日志，关键错误也已尝试写入 LSP 日志。")
                        .setPositiveButton("复制日志") { _, _ -> copy(activity, collect(activity)) }
                        .setNegativeButton("查看日志") { _, _ -> show(activity) }.show()
                }
            }
        }, "yanwai-log-export").start()
    }
}
