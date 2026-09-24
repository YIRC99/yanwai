package dev.jev.wechatmood.ui

enum class ProbeState { UNTESTED, CHECKING, PASSED, FAILED }
enum class SetupAction { CONFIGURE, TEST, GUIDE, OPEN_WECHAT }
enum class StatusTone { NEUTRAL, SUCCESS, WARNING, ERROR }
data class SetupOverview(val title: String, val description: String, val action: SetupAction,
    val actionLabel: String, val tone: StatusTone, val modelLabel: String, val modelTone: StatusTone,
    val hostLabel: String, val hostTone: StatusTone)

object SetupPresenter {
    fun resolve(hasKey: Boolean, dirty: Boolean, probe: ProbeState,
        lastSeen: Long, now: Long): SetupOverview {
        val host = when {
            lastSeen <= 0 -> "尚无运行记录" to StatusTone.WARNING
            now - lastSeen in 0..300_000 -> "最近有运行记录" to StatusTone.SUCCESS
            else -> "有历史记录" to StatusTone.NEUTRAL
        }
        val model = when {
            dirty -> "有未保存修改" to StatusTone.WARNING
            !hasKey -> "未配置" to StatusTone.WARNING
            probe == ProbeState.CHECKING -> "正在检测" to StatusTone.NEUTRAL
            probe == ProbeState.PASSED -> "本次检测通过" to StatusTone.SUCCESS
            probe == ProbeState.FAILED -> "检测失败" to StatusTone.ERROR
            else -> "已保存 · 待检测" to StatusTone.NEUTRAL
        }
        fun view(title: String, description: String, action: SetupAction, label: String, tone: StatusTone) =
            SetupOverview(title, description, action, label, tone, model.first, model.second, host.first, host.second)
        return when {
            dirty -> view("修改还没有保存", "保存并检测后，微信才会使用这次填写的配置。", SetupAction.TEST, "保存并检测连接", StatusTone.WARNING)
            !hasKey -> view("先连接你的模型", "选择服务渠道，填入对应的 API Key。言外会用一条示例消息检测连接。", SetupAction.CONFIGURE, "开始配置模型", StatusTone.WARNING)
            probe == ProbeState.CHECKING -> view("正在检测模型连接", "正在使用示例消息检测，不会读取你的微信聊天。", SetupAction.TEST, "正在检测…", StatusTone.NEUTRAL)
            probe == ProbeState.FAILED -> view("模型连接需要处理", "下方已显示失败原因。检查渠道、Key 或网络后，可以重新检测。", SetupAction.TEST, "查看失败原因", StatusTone.ERROR)
            probe != ProbeState.PASSED -> view("配置已保存，检测一下", "保存不代表连接成功。先检测模型，再回微信查看实际效果。", SetupAction.TEST, "检测模型连接", StatusTone.NEUTRAL)
            lastSeen <= 0 -> view("模型已连接，去启用微信模块", "还没有收到微信运行记录。按步骤在 LSPosed 中启用，再重启微信。", SetupAction.GUIDE, "查看微信启用步骤", StatusTone.WARNING)
            else -> view("回到微信，试试聊天", "每个聊天默认关闭，打开右上角「分析」后会记住选择。模型本次检测通过，运行记录不代表微信当前在线。", SetupAction.OPEN_WECHAT, "打开微信", StatusTone.SUCCESS)
        }
    }
}
