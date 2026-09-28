package dev.jev.wechatmood.reply

import java.lang.ref.WeakReference

/** Pure data policy for the verified native extension list. */
internal object ReplyPlusItems {
    fun supports(versionName: String?, versionCode: Long) = versionName == "8.0.71" && versionCode == 3080L

    fun <T> appendOnce(items: List<T>, owns: (T) -> Boolean, create: () -> T): ArrayList<T> {
        val entry = items.firstOrNull(owns) ?: create()
        return ArrayList(items.filterNot(owns)).apply { add(entry) }
    }

    fun canAppend(otherItemsEnabled: Boolean, otherCount: Int) = otherItemsEnabled || otherCount == 0
}

/** Host models compare by appId; equal models are not necessarily our own instance. */
internal class ReplyPlusOwnership {
    private val items = mutableListOf<WeakReference<Any>>()
    @Synchronized fun owns(item: Any?): Boolean {
        items.removeAll { it.get() == null }
        return item != null && items.any { it.get() === item }
    }
    @Synchronized fun remember(item: Any) {
        if (!owns(item)) items += WeakReference(item)
    }
}
