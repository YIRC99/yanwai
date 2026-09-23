package dev.jev.wechatmood.analysis

data class ChatTemplate(
    val id: String,
    val scene: String,
    val title: String,
    val question: String,
    val signal: String,
    val ordinary: String,
    val onlyDuring: Set<String> = emptySet(),
    val stopDuring: Set<String> = setOf("accepted", "closing"),
) {
    val options: Map<String, String> get() = linkedMapOf(
        "signal" to signal, "ordinary" to ordinary, "unclear" to "信息不足或此问题不适用，不能确定")
}

/** Each entry is a question with competing readings and eligibility conditions. Actions are independent. */
object ChatTemplates {
    val scenes = linkedMapOf(
        "daily" to "日常分享：见闻、喜事、疲惫与普通闲聊",
        "invite" to "邀约安排：一起吃饭、见面、活动、时间地点的讨论",
        "care" to "关心靠近：询问近况、表达挂念、寻求陪伴",
        "tease" to "玩笑试探：打趣、轻松调侃、试探对方的反应",
        "promise" to "约定记忆：提醒之前说过的事、核对承诺是否记得或落实",
        "friction" to "委屈不满：表达受伤、不满、压力或需要空间",
        "repair" to "解释修复：就误会或不满解释、道歉、提出补救并回应",
        "closing" to "缓和收尾：接受回应、气氛缓和、告别或结束当前话题",
        "other" to "纯事务指令或通知、无适合分类或信息不足；向朋友分享工作学习经历仍属于闲聊")

    val all = listOf(
        ChatTemplate("daily_share", "daily", "日常小事，也是在递话题", "这次分享更想得到什么？",
            "想让你参与这段经历", "只是告知一件事",
        ),
        ChatTemplate("daily_celebrate", "daily", "这份开心，想有人一起接住", "是在分享成就和期待回应吗？",
            "期待你一起开心或肯定", "只是平常的情况更新",
        ),
        ChatTemplate("daily_vent", "daily", "先当听众，还是先当军师？", "这会儿更需要倾听还是办法？",
            "想先吐槽、被理解", "明确在问解决办法",
        ),
        ChatTemplate("daily_bridge", "daily", "话题还留着一扇门", "对方在主动延续聊天吗？",
            "在追问或提供新话题", "只是礼貌回应上一句",
        ),

        ChatTemplate("invite_probe", "invite", "这句可能在给见面留位置", "是在试探一起活动的意愿吗？",
            "在试探能否一起去", "只是分享活动信息",
        ),
        ChatTemplate("invite_plan", "invite", "好意向，差一个具体安排", "现在是否在等时间地点？",
            "已有一起去的意向，等具体安排", "还没确认愿不愿意去",
        ),
        ChatTemplate("invite_space", "invite", "给邀约留个舒服的出口", "对方是在婉拒吗？",
            "表达不愿意或希望保留空间", "只是当前时间不合适",
        ),
        ChatTemplate("invite_confirm", "invite", "约好了，就别无限加码", "这次安排是否已经确认？",
            "时间或安排得到明确同意", "还有关键细节没定",
            stopDuring = emptySet()),

        ChatTemplate("care_checkin", "care", "这句关心，落在你的近况上", "是在认真关心你的状态吗？",
            "根据具体近况主动关心", "普通寒暄或顺口问候",
        ),
        ChatTemplate("care_company", "care", "可能想要的，是你陪一会儿", "对方是在寻求陪伴吗？",
            "希望有人陪聊或陪着做事", "只是描述当前状态",
        ),
        ChatTemplate("care_reciprocal", "care", "对话有来有回，是个好信号", "对方是否在主动了解你？",
            "具体追问你的经历或偏好", "只是礼貌地回问一句",
        ),
        ChatTemplate("care_reassure", "care", "可能在等一句被放在心上的回应", "是在确认自己有没有被重视吗？",
            "对被忽略表达担心，期待确认", "仅询问一个事实",
        ),

        ChatTemplate("tease_play", "tease", "这个梗，可以轻轻接一下", "这句调侃是在轻松互动吗？",
            "双方语境支持友好的玩笑", "包含认真不满或不舒服",
        ),
        ChatTemplate("tease_probe", "tease", "玩笑里，可能藏着一个小问题", "是在借玩笑试探你的态度吗？",
            "围绕明确话题试探你的态度", "单纯逗趣，没有足够试探线索",
        ),
        ChatTemplate("tease_compliment", "tease", "这句夸奖，可以大方收下", "这次夸奖带有主动靠近的信号吗？",
            "具体而主动地表达欣赏", "礼貌客套或熟人打趣",
        ),
        ChatTemplate("tease_boundary", "tease", "梗到这里，要不要收一收？", "对方是否已经不想继续这个玩笑？",
            "明确表现出不适或要求停止", "仍然在愉快接梗",
        ),

        ChatTemplate("promise_care", "promise", "这次考的，可能不只是记忆力", "更在意记没记住，还是有没有被重视？",
            "期待之前说的事被认真对待", "单纯核对是否记得",
            stopDuring = setOf("act", "accepted", "closing")),
        ChatTemplate("promise_detail", "promise", "现在轮到具体内容了", "对方是在等你说出那件事吗？",
            "正在要求具体内容或细节", "只需要简单确认",
            stopDuring = setOf("act", "accepted", "closing")),
        ChatTemplate("promise_followup", "promise", "记得和做到，中间还差一步", "是在追问之前约定的进展吗？",
            "希望知道约定是否落实", "只是回忆过去的事情",
            stopDuring = setOf("accepted", "closing")),
        ChatTemplate("promise_action", "promise", "解释到这里，该接行动了", "现在更需要实际安排吗？",
            "前文已解释清楚，正在等行动", "仍有事实或误会需要澄清",
            onlyDuring = setOf("act", "explain", "unknown")),

        ChatTemplate("friction_hear", "friction", "先听见委屈，再处理事情", "这句不满更需要先被理解吗？",
            "在表达受伤或被忽略的感受", "主要在指出具体事实问题",
        ),
        ChatTemplate("friction_space", "friction", "这会儿，留点空间也算回应", "对方是否明确想暂停聊天？",
            "表达了暂停或独处的需要", "仍在等待你回应问题",
        ),
        ChatTemplate("friction_boundary", "friction", "情绪可以接住，边界也要说清", "对话里是否出现明显越界要求？",
            "有明确辱骂、强迫或不合理要求", "只是意见不同或语气较重",
        ),
        ChatTemplate("friction_ask", "friction", "别急着替一句短话写剧情", "这句短回应的意思足够明确吗？",
            "前文支持具体不满或落差", "短句本身有多种普通解释",
        ),

        ChatTemplate("repair_listen", "repair", "解释之前，先把问题对齐", "双方是否还没说清在意的点？",
            "仍在澄清误会或表达受伤", "问题已经明确，在等下一步",
            stopDuring = setOf("act", "accepted", "closing")),
        ChatTemplate("repair_apology", "repair", "一句具体的道歉，比一串保证有用", "是否需要承认一个明确的疏漏？",
            "前文有自己明确的疏漏待承认", "责任不清或不需要重复道歉",
            stopDuring = setOf("act", "accepted", "closing")),
        ChatTemplate("repair_action", "repair", "这次回应，落到一件能做的事", "对方现在更期待行动还是解释？",
            "已经听过解释，正在等补救", "还没理解事情的经过",
        ),
        ChatTemplate("repair_accept", "repair", "回应被接住，就不用再追着解释", "对方是否已经接受这次回应？",
            "明确认可解释或补救安排", "只是暂时回应，问题仍未解决",
            onlyDuring = setOf("accepted", "closing", "unknown"), stopDuring = emptySet()),

        ChatTemplate("closing_relief", "closing", "气氛松了，可以轻轻落地", "这句回应是否在释放缓和信号？",
            "明确接受或语气明显缓和", "礼貌应付，不能确定已缓和",
            stopDuring = emptySet()),
        ChatTemplate("closing_goodnight", "closing", "晚安之后，也给对话一个晚安", "对方是在准备结束聊天吗？",
            "明确告别、休息或去忙", "只是提到休息，并未结束话题",
            stopDuring = emptySet()),
        ChatTemplate("closing_done", "closing", "这一页说清了，就翻过去吧", "当前话题是否已经得到确认？",
            "关键内容已确认，无需再追问", "还有具体问题没有回答",
            stopDuring = emptySet()),
        ChatTemplate("closing_pause", "closing", "留白，也可以是舒服的节奏", "这会儿适合让聊天自然停一停吗？",
            "话题自然结束，没有待回应的问题", "对方还有明确问题或话题邀请",
            stopDuring = emptySet()),
    )

    fun candidates(profile: ChatProfile): List<ChatTemplate> {
        val externalVenting = profile.has("target", "experience", "third_party") && profile.has("speech_act", "vent")
        val scene = when {
            externalVenting -> "daily"
            profile.newTopic && (!profile.canSpecialize || profile.scene.choice == "closing") -> "daily"
            profile.canSpecialize -> profile.scene.choice
            profile.has("speech_act", "share") -> "daily"
            else -> return emptyList()
        }
        val progress = when {
            profile.newTopic || externalVenting -> "sharing"
            profile.progress.clear -> profile.progress.choice
            else -> "unknown"
        }
        return all.filter { it.scene == scene && progress !in it.stopDuring &&
            (it.onlyDuring.isEmpty() || progress in it.onlyDuring || progress == "unknown") &&
            allowed(it.id, profile) }
    }

    private fun allowed(id: String, p: ChatProfile): Boolean = when {
        id.startsWith("promise_") -> p.hasAgreement
        id == "repair_apology" || id == "repair_action" -> p.personalConflict && p.has("own_fault", "yes")
        id == "repair_accept" -> p.acceptsResponse || (p.personalConflict && p.has("commitment", "accepted"))
        id == "repair_listen" || id in setOf("friction_hear", "friction_ask", "friction_boundary") -> p.personalConflict
        id == "friction_space" || id == "closing_pause" -> !p.newTopic && p.has("speech_act", "pause")
        id == "closing_goodnight" -> !p.newTopic && p.has("speech_act", "goodbye")
        id == "closing_done" || id == "closing_relief" -> p.acceptsResponse || (p.hasAgreement && p.has("commitment", "accepted"))
        id == "invite_confirm" -> p.hasAgreement && p.has("commitment", "accepted")
        else -> true
    }
}
