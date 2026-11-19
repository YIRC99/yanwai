package com.dogheadstrategist.chat.feature

import android.content.Context
import com.dogheadstrategist.chat.core.ModulePrefs
import com.dogheadstrategist.chat.core.MoodLog
import com.dogheadstrategist.chat.core.SettingsTransport
import com.dogheadstrategist.chat.reply.ReplyHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * 通知代办的图片入口：在聊天里长按一张图，走一次提取。
 *
 * 和文字那条线（[AideDigest]）是同一套东西——同一份类型清单、同一个协议、
 * 同一个 request 通道、同一个统计口径、同一个落库通道。只有两点不同：
 * 触发方式（手动长按 vs 通知自动）和输入源（图片 vs 文本）。
 *
 * 这个类跑在**微信进程**，原因有两层：
 * 1. 图片在微信的私有目录下，只有微信进程读得到（分区存储对别的 app 是关着的）；
 * 2. 长按这个动作本身就发生在微信进程里。
 *
 * 但重活的终点——[MatterStore] 的文件在 app 的 filesDir 下，微信进程够不着。
 * 所以流程是「微信进程把图读完、压缩、请求完、解析好，把结果 JSON 通过
 * aide_items_put 推过去」，由 app 进程负责落盘。这条通道本来就是为通知代办
 * 建好的（见 SettingsProvider 里 aide_items_put 那段注释）。
 */
object AidePicture {

    /**
     * 一次识别最长等多久。
     *
     * 比文字链路宽松：要传几百 KB 的图，而纯文本只是一个几十 KB 的 JSON。
     * 90 秒是给慢速网关留的余量，再长用户会以为没反应。
     */
    private const val TIMEOUT_MS = 90_000L

    /** Toast 里最多显示多少个字，多余的截断，免得一条消息撑满屏幕。 */
    private const val PREVIEW_CHARS = 18

    /**
     * 一次识别的结果。
     *
     * [ok] 为 true 不代表一定新增了条目——图里确实没有要记的事也是正常结局，
     * 这种时候 [message] 会说明白，调用方照样应该把它当成「跑通了」来提示。
     */
    data class Outcome(val ok: Boolean, val added: Boolean, val message: String)

    /**
     * 跑一次图片识别。
     *
     * 调用方只需要负责在自己的线程上发起来，里面的重活会切到 IO。
     *
     * @param imagePath 消息对象的 field_imgPath，微信已解好的明图路径
     * @param senderHint 图片所在会话的对方昵称，作为落库时的 source
     * @param talker 会话 id，点开记录能跳回这个会话
     */
    suspend fun extract(
        context: Context,
        imagePath: String,
        senderHint: String = "",
        talker: String = "",
        /** 长按到的那个 View。能从它身上拿到正在显示的图，就优先用。 */
        imageView: android.view.View? = null,
    ): Outcome {
        // 配置从快照读：微信进程读不到 app 的 SharedPreferences，
        // ModulePrefs 里那份是 app 进程同步过来的。
        // 通知代办总开关关着就明确拒绝：同一个功能，不该出现「文字不提取、
        // 长按图片却能记下来」这种前后不一致的行为
        if (!AidePrefs.enabledForRequest()) return Outcome(false, false, "通知代办没启用")

        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) return Outcome(false, false, "还没配置回复模型")

        val types = AidePrefs.typesForRequest()
        if (types.isEmpty()) return Outcome(false, false, "没有可用的类型")

        val encoded = withContext(Dispatchers.IO) {
            runCatching {
                // 优先要屏幕上那张：微信本地图片多是加密 .dat 或没下载，
                // 但既然能长按到，图一定已经解码画出来了。drawable 拿不到
                // （比如只是占位色块）再退回读文件。
                if (imageView != null) {
                    runCatching { PictureSource.encodeFromView(imageView) }.getOrElse {
                        PictureSource.encode(imagePath)
                    }
                } else {
                    PictureSource.encode(imagePath)
                }
            }
        }.getOrElse { error ->
            // 文件不存在、没下载、是 .dat、太长——都是可预期的，直接说原因
            return Outcome(false, false, error.message ?: "图片读取失败")
        }
        MoodLog.i(
            "AIDE_PICTURE_COMPRESS src=" + encoded.originalBytes + "B dst=" + encoded.encodedBytes +
                "B " + encoded.width + "x" + encoded.height,
        )

        val result = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                ReplyHttpClient().extractPicture(
                    settings, types, encoded.base64, senderHint,
                    keyUsers = AidePrefs.keyUsersForRequest(),
                )
            }.onFailure { error ->
                // runCatching 会把 CancellationException 一起吞掉，超时就会
                // 变成一条普通的「识别失败」，还会让取消传不出去。重抛一次，
                // 交回 withTimeoutOrNull 判定成 null，下面才能给出「超时」。
                if (error is kotlinx.coroutines.CancellationException) throw error
            }
        }
        if (result == null) {
            MoodLog.w("AIDE_PICTURE_TIMEOUT")
            return Outcome(false, false, "识别超时，稍后再试")
        }
        val draft = result.getOrElse { error ->
            MoodLog.w("AIDE_PICTURE_FAILED " + error.javaClass.simpleName)
            return Outcome(false, false, "识别失败：" + (error.message ?: error.javaClass.simpleName))
        }.firstOrNull()

        // null 表示模型没按格式回，空 typeKey 表示它明确说「这里面没有要记的事」，
        // 两者要分开：前者是失败，后者是正常结论
        if (draft == null) return Outcome(false, false, "模型没给出可解析的结果")
        if (draft.typeKey.isBlank()) return Outcome(true, false, "这张图里没有要记的事")

        val now = System.currentTimeMillis()
        val item = MatterItem(
            // 文字链路用 notice.id 派生，图片没有通知 id，用毫秒时间戳即可：
            // 同一个人不可能在一毫秒内长按两次，不会和 a*10+n 撞上
            id = now,
            typeKey = draft.typeKey,
            content = draft.content,
            emoji = draft.emoji,
            source = senderHint,
            talker = talker,
            // 留个来路，排查时能知道这条是从哪张图出来的
            rawText = "[图片] " + imagePath,
            createdAt = now,
        )

        val payload = JSONArray().put(item.toJson()).toString()
        val bundle = withContext(Dispatchers.IO) {
            runCatching { SettingsTransport.call(context, "aide_items_put", payload, null) }
        }.getOrNull()
        val saved = bundle?.getBoolean("saved") == true
        if (!saved) {
            MoodLog.w("AIDE_PICTURE_PUT_FAILED")
            return Outcome(false, false, "保存失败")
        }

        MoodLog.i("AIDE_PICTURE_SAVED type=" + draft.typeKey + " note=" + draft.content.take(PREVIEW_CHARS))
        return Outcome(true, true, "已记下：" + draft.content.take(PREVIEW_CHARS))
    }
}
