package dev.jev.wechatmood.hook

internal object ReplyDrawerSizing {
    /** Add a fifth of the page to the previous drawer height, retaining a small top gap. */
    fun height(available: Int, baseHeight: Int): Int {
        val previous = minOf(baseHeight, (available * 0.78f).toInt())
        return minOf(previous + (available * 0.20f).toInt(), (available * 0.95f).toInt())
    }
}
