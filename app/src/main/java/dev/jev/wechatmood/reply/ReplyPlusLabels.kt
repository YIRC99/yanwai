package dev.jev.wechatmood.reply

/** DEX optimization can remove label constructors; the host item already owns these objects. */
internal object ReplyPlusLabels {
    private val fields = listOf("n2", "o2", "p2", "q2")

    fun populate(item: Any, labelType: Class<*>) {
        // Validate every existing instance before changing any of them.
        val labels = fields.map { name ->
            item.javaClass.getField(name).get(item).also {
                check(labelType.isInstance(it)) { "Native reply label $name is missing or incompatible" }
            }
        }
        val title = labelType.getField("a")
        val subtitle = labelType.getField("b")
        check(title.type == String::class.java && subtitle.type == String::class.java)
        for (label in labels) {
            title.set(label, "帮我回")
            subtitle.set(label, "")
        }
    }
}
