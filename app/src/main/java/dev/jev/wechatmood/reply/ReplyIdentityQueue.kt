package dev.jev.wechatmood.reply

/** Dispatch must be FIFO and serial. Writes outlive drawers; callbacks never change identity state. */
class ReplyIdentityQueue(private val dispatch: (() -> Unit) -> Unit,
    private val read: (ReplyContactKey) -> ReplyIdentitySetting,
    private val write: (ReplyContactKey, ReplyIdentitySetting) -> Unit) {
    fun load(key: ReplyContactKey, complete: (Result<ReplyIdentitySetting>) -> Unit) = dispatch {
        complete(runCatching { read(key) })
    }
    fun save(owner: ReplyIdentityOwner, value: ReplyIdentitySetting, verify: () -> String?, complete: (Boolean) -> Unit) {
        val key = owner.key ?: run { complete(false); return }
        dispatch {
            val saved = runCatching {
                check(owner.acceptsAccount(verify()))
                write(key, value)
            }.isSuccess
            complete(saved)
        }
    }
}
