package dev.jev.wechatmood.analysis

data class ChatAction(val id: String, val condition: String, val text: String)

/** Only eligible actions reach Jev. Jev chooses among them using the original conversation. */
object ChatActions {
    fun candidates(p: ChatProfile): List<ChatAction> = buildList {
        fun offer(id: String, condition: String, text: String) { add(ChatAction(id, condition, text)) }
        val active = !p.has("speech_act", "pause", "goodbye") || p.newTopic
        if (p.newTopic) {
            offer("follow_new_topic", "对方提供新话题或新计划，顺着新的内容聊", "顺着新话题，问问接下来的打算。")
        }
        if (active && p.has("speech_act", "share", "vent")) {
            offer("ask_detail", "对方分享经历，可问原文尚未说明的具体细节；不重复问已回答内容", "顺着刚提到的事，问一个还没说到的细节。")
            offer("acknowledge_share", "对方分享了一件事，回应已说到的具体内容即可，不一定需要追问", "接着对方说的那件事，回应一下自己的感受。")
            if (p.has("speech_act", "share") && p.emotion.clear && p.emotion.choice == "happy") {
                offer("celebrate", "原文明确在分享好消息或取得的成果，普通开心闲聊不选此项", "先祝贺，再夸一个具体做得好的地方。")
            }
            if (p.has("speech_act", "vent") && p.has("target", "experience", "third_party")) {
                offer("listen", "对方在倾诉遭遇且没有直接问题需要回答，适合让对方继续说", "回应最让对方难受的那一点，让对方接着说。")
            }
            if (p.has("target", "experience") && p.has("speech_act", "vent")) {
                offer("check_condition", "对方在讲自身不舒服或倒霉经历，适合关心眼下状态", "先问现在好点没有，别急着说没事。")
            }
            if (p.has("target", "third_party") && p.has("speech_act", "vent")) {
                offer("acknowledge_third_party", "对方吐槽第三方，回应具体不合理之处，不转成双方矛盾", "回应对方提到的那件糟心事，别急着说只是小事。")
            }
        }
        if (active && p.has("speech_act", "question")) {
            offer("answer_question", "对方有直接问题，先回答，不能答非所问", "先回答对方刚问的事，拿不准的部分直说。")
        }
        if (active && p.has("advice_need", "yes")) {
            offer("offer_solution", "对方明确求办法，当前有足够事实讨论可行下一步", "围绕对方问的困难，先给一个能马上做的办法。")
        }
        if (active && p.has("speech_act", "request")) {
            offer("respond_request", "明确向我方请求帮助或行动，回应能否做到", "说清能不能帮上忙，能做的部分再答应。")
        }
        if (active && p.personalConflict && p.has("own_fault", "yes") &&
            !(p.progress.clear && p.progress.choice in setOf("accepted", "closing"))) {
            offer("apologize", "原文明确存在我方未处理的过错，不适用于第三方过错", "为具体做错的事道歉，再说怎么补救。")
        }
        if (active && p.hasAgreement) {
            offer("fulfill", "原文有具体待兑现承诺或双方安排，当前仍在讨论它", "说清约定的事准备怎么做、什么时候做。")
        }
        if (active && p.personalConflict && p.has("speech_act", "vent")) {
            offer("clarify_concern", "对方直接对我方表达不满，具体在意的点尚不清楚", "问清对方具体在意哪一点，再回应。")
        }
        if (active && p.has("speech_act", "play")) {
            offer("play_along", "原文支持友好的玩笑，没有明确不适或要求停止", "顺着玩笑接一句，不拿对方难受的事打趣。")
        }
        if (active && p.has("speech_act", "confirm")) {
            offer("acknowledge", "只是确认具体信息，不额外推断约定或关系变化", "简短确认对方说的内容即可。")
        }
        if (!p.newTopic && p.has("speech_act", "pause")) {
            offer("give_space", "对方明确要求暂停或独处", "让对方先缓一缓，别连续发消息。")
        }
        if (!p.newTopic && p.has("speech_act", "goodbye")) {
            offer("goodbye", "对方明确告别，没有另开话题", "回一句告别，先让对方去忙。")
        }
    }

    fun options(profile: ChatProfile): Map<String, String> = candidates(profile).associate {
        it.id to "${it.condition}；建议：${it.text}"
    } + ("none" to "都不贴合、前提不成立、已经回应过或信息不足，不给建议")
}
