package dev.jev.wechatmood.hook

internal object ReplyDrawerSizing {
    /** Follow content height; only cap overflow, never add a screen-sized empty spacer. */
    fun height(available: Int, contentHeight: Int): Int =
        minOf(contentHeight, (available * 0.95f).toInt())
}
