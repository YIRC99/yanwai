package dev.jev.wechatmood.hook

/**
 * 从一个 item view 里抠出消息文本的**纯逻辑部分**。
 *
 * 拆出 [pickMessageBody] 是有意的：遍历 View 树必须跑在设备上，但「哪段文字才是正文」
 * 这条规则最容易出错、也最值得单测。纯函数化之后，规则可以被 JVM 单测直接覆盖，
 * 不需要模拟器。
 *
 * 刻意**不依赖资源 id**——那才是随版本失效最快的东西——只按文本本身的特征判断。
 */
object MessageTextPicker {

    /**
     * 时间戳、已读状态这类「界面装饰文字」，不是消息正文。
     * 单独成一条消息时没意义，混在气泡里也不该被当成正文去分析。
     */
    private val CHROME_LIKE = Regex(
        """^\s*(\d{1,2}[:：]\d{2}|\d+月\d+日|昨天|今天|星期[一二三四五六日天]|已读|Read)\s*$"""
    )

    /**
     * 从一行消息渲染出的所有文本里挑出正文。
     *
     * 规则：去掉空白和界面装饰后，**最长的那个**就是正文。
     * 聊天行里的文字无非正文、昵称、时间、状态，正文几乎总是最长的——
     * 这个启发式比不上按资源 id 精确，但它不会因为微信改 id 就失效。
     *
     * @return 正文；没有可用文本时返回 null
     */
    fun pickMessageBody(candidates: List<String>): String? =
        candidates
            .filter { it.isNotBlank() }
            .filterNot { CHROME_LIKE.matches(it) }
            .maxByOrNull { it.length }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
