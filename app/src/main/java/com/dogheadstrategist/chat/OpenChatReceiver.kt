package com.dogheadstrategist.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.feature.CommunicatePrefs
import com.dogheadstrategist.chat.feature.SentryStore
import java.util.concurrent.TimeUnit

/**
 * 点击通知摘要后落在这里。
 *
 * 用 Receiver 而不是 Activity：Activity 哪怕透明，也会把狗头军师拉进任务栈，
 * 用户会看到一次多余的切换。Receiver 完全在后台跑，点完直接见微信。
 *
 * 真正拉起微信的是 [ChattingUI]，靠 Chat_User 定位会话。
 * 微信这个 Activity 没 exported，普通 uid 起不来，所以开了 root 就走 su 代发。
 */
class OpenChatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nick = intent.getStringExtra(EXTRA_NICK).orEmpty()
        val pending = goAsync()
        Thread {
            try {
                open(context.applicationContext, nick)
            } catch (error: Exception) {
                MoodLog.w("COMMUNICATE_OPEN_ERROR " + error.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun open(context: Context, nick: String) {
        val talker = SentryStore.findTalker(context, nick)
        if (talker.isEmpty()) {
            MoodLog.w("COMMUNICATE_OPEN_NO_TALKER nick=" + nick.take(12))
            openHome(context)
            return
        }
        if (CommunicatePrefs.useRootJump(context)) {
            // 用户选了 root 就只走 root，普通方式必然 SecurityException
            if (startAsRoot(talker)) return
            MoodLog.w("COMMUNICATE_OPEN_FALLBACK nick=" + nick.take(12))
            openHome(context)
            return
        }
        if (startDirect(context, talker)) return
        MoodLog.w("COMMUNICATE_OPEN_FALLBACK nick=" + nick.take(12))
        openHome(context)
    }

    private fun startDirect(context: Context, talker: String): Boolean = runCatching {
        context.startActivity(Intent().apply {
            setClassName(WE_CHAT, CHATTING_UI)
            putExtra("Chat_User", talker)
            putExtra("Chat_Mode", 1)
            // ChattingUI 是 singleTask。不 CLEAR_TASK 的话，它已经存在时只会把
            // task 提到前台，onNewIntent 又不读 Chat_User，结果停在原会话
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        MoodLog.i("COMMUNICATE_OPEN_CHAT talker=" + talker.take(16))
        true
    }.onFailure {
        MoodLog.w("COMMUNICATE_OPEN_CHAT_FAILED " + it.javaClass.simpleName)
    }.getOrDefault(false)

    /**
     * 走 su 让 shell 代发 am start。
     *
     * talker 用单引号包住。wxid 和 xxx@chatroom 里不会有单引号，不用担心注入。
     */
    private fun startAsRoot(talker: String): Boolean = runCatching {
        val command = "am start -n $WE_CHAT/$CHATTING_UI" +
            " --es Chat_User '$talker' --ei Chat_Mode 1 --activity-clear-task"
        // app 进程的 PATH 里不一定有 su，用绝对路径
        val process = Runtime.getRuntime().exec(arrayOf(SU_PATH, "-c", command))
        // goAsync 只给 10 秒，留点余量
        val finished = process.waitFor(6, TimeUnit.SECONDS)
        val ok = finished && process.exitValue() == 0
        MoodLog.i("COMMUNICATE_OPEN_ROOT ok=$ok talker=" + talker.take(16))
        ok
    }.onFailure {
        MoodLog.w("COMMUNICATE_OPEN_ROOT_FAILED " + it.javaClass.simpleName)
    }.getOrDefault(false)

    /** 找不到会话或者微信不认那个 Intent 时的退路：至少把微信打开。 */
    private fun openHome(context: Context) {
        runCatching {
            val launch = context.packageManager.getLaunchIntentForPackage(WE_CHAT) ?: return
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    companion object {
        const val EXTRA_NICK = "nick"
        private const val WE_CHAT = "com.tencent.mm"
        private const val CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI"
        private const val SU_PATH = "/system/bin/su"
    }
}
