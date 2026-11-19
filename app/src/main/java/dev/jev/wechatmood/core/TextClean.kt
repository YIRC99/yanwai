package dev.jev.wechatmood.core

/** 模型输出里常见的 Markdown 标记，展示和复制前统一去掉。 */
object TextClean {
    private val MARKERS = Regex("[*_`#>]+")
    private val BULLET = Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE)
    private val BLANK_LINES = Regex("\\n{3,}")

    fun plain(text: String): String = text
        .replace(BULLET, "")
        .replace(MARKERS, "")
        .replace(BLANK_LINES, "\n\n")
        .trim()
}
