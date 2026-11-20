package dev.jev.wechatmood.hook

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge

/**
 * 通知上的三个动作：进聊天、标记完成、稍后再提醒。
 *
 * 不放在清单里：模块代码跑在微信进程，动态注册才收得到；广播由自己创建的
 * PendingIntent 发出，用 setPackage 限定到微信。别的应用要伪造也只能改「已完成」
 * 这种可逆状态，而且会留日志。
 */
internal object RelationTodoActions {
    const val ACTION_DONE = "dev.jev.wechatmood.action.RELATION_TODO_DONE"
    const val ACTION_OPEN = "dev.jev.wechatmood.action.RELATION_TODO_OPEN"
    const val ACTION_SNOOZE = "dev.jev.wechatmood.action.RELATION_TODO_SNOOZE"
    const val EXTRA_KEY = "contact_key"
    const val EXTRA_IDS = "fact_ids"
    const val EXTRA_TALKER = "talker"
    const val EXTRA_NOTICE = "notice_id"
    const val EXTRA_MINUTES = "minutes"

    private val FACT_ID = Regex("[0-9a-f]{16}")
    private val CONTACT_KEY = Regex("[0-9a-f]{64}")
    private val TALKER = Regex("[A-Za-z0-9_@.\\-]{1,256}")

    fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DONE -> markDone(c, intent)
                    ACTION_OPEN -> openChat(c, intent)
                    ACTION_SNOOZE -> snooze(c, intent)
                }
            }
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(ACTION_DONE)
                addAction(ACTION_OPEN)
                addAction(ACTION_SNOOZE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { MoodLog.w("RELATION_TODO_RECEIVER_FAIL " + it.javaClass.simpleName) }
    }

    private fun markDone(c: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.matches(CONTACT_KEY) } ?: return
        val ids = intent.getStringExtra(EXTRA_IDS).orEmpty().split(",")
            .map { it.trim() }.filter { it.matches(FACT_ID) }.distinct()
        if (ids.isEmpty()) return
        // 只改「已完成」这个可逆状态；标记错了可以在「附加 · 待办」里恢复。
        val changed = RelationFactBridge.markDone(c, key, ids)
        cancelNotice(c, intent)
        MoodLog.i("RELATION_TODO_DONE_BY_NOTICE asked=" + ids.size + " changed=" + changed)
    }

    /** 稍后提醒：先撤通知，到点后由下一次检查重新弹；不依赖微信进程一直活着。 */
    private fun snooze(c: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY)?.takeIf { it.matches(CONTACT_KEY) } ?: return
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 60)
        RelationReminder.snooze(c, key, minutes, intent.getIntExtra(EXTRA_NOTICE, 0))
        MoodLog.i("RELATION_TODO_SNOOZE minutes=" + minutes)
    }

    /**
     * 打开对应会话。
     *
     * 这版微信的 LauncherUI 不认带 Chat_User 的 extra，点了只会回到首页；
     * ChattingUI 才是真正的聊天页入口（WeKit 的 openContact 也是这么做的）。
     * 放在这里是因为接收器跑在微信进程，startActivity 用的是微信自己的身份。
     */
    private fun openChat(c: Context, intent: Intent) {
        val talker = intent.getStringExtra(EXTRA_TALKER)?.takeIf { it.matches(TALKER) }
        cancelNotice(c, intent)
        if (talker == null) {
            MoodLog.w("RELATION_OPEN no_talker")
            start(c, Intent().setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
            return
        }
        val opened = start(c, Intent().apply {
            setClassName("com.tencent.mm", "com.tencent.mm.ui.chatting.ChattingUI")
            putExtra("Chat_User", talker)
        })
        if (!opened) {
            start(c, Intent().apply {
                setClassName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
                putExtra("Chat_User", talker)
                putExtra("Chat_Mode", 1)
            })
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(intent)
        MoodLog.i("RELATION_OPEN target=" + intent.component?.className?.substringAfterLast('.'))
        true
    }.onFailure {
        MoodLog.w("RELATION_OPEN_FAILED " + it.javaClass.simpleName + " " + (intent.component?.className ?: ""))
    }.getOrDefault(false)

    private fun cancelNotice(context: Context, intent: Intent) {
        val notice = intent.getIntExtra(EXTRA_NOTICE, 0)
        if (notice <= 0) return
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(notice)
        }
    }
}
