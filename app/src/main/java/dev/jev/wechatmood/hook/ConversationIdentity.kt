package dev.jev.wechatmood.hook

/** Never carry the previous chat's identity across empty or ambiguous pages. */
object ConversationIdentity {
    fun resolve(visibleTalkers: List<String>, footerTalker: String?, dedicatedChat: String?): String? {
        val candidates = (visibleTalkers + listOfNotNull(footerTalker))
            .filter { it.isNotBlank() }.distinct()
        return when (candidates.size) {
            0 -> dedicatedChat?.takeIf { it.isNotBlank() }
            1 -> candidates.single()
            else -> null
        }
    }
}
