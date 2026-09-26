package dev.jev.wechatmood.voice

/** Identity only. No audio bytes, host objects or raw voice payload enter prompts. */
data class VoiceSource(val talker: String, val id: Long, val time: Long, val sent: Int,
    val fileToken: String = "", val serverId: Long = 0) {
    val key: String get() = listOf(talker, id.toString(), time.toString(), sent.toString(), fileToken, serverId.toString())
        .joinToString("") { "${it.length}:$it" }
    override fun toString() = "VoiceSource(id=$id)"
}

enum class VoiceState { NONE, WAITING, READY, FAILED }

object VoiceText {
    const val WAITING = "【语音，等待转写】"
    const val FAILED = "【语音未能转写，内容未知】"
}
