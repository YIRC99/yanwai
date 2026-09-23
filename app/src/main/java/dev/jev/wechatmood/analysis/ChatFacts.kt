package dev.jev.wechatmood.analysis

/** Independent observations, not assumed causes or relationship scores. */
object ChatFacts {
    data class Question(val instructions: String, val options: Map<String, String>)
    val questions = linkedMapOf(
        "target" to Question("当前情绪或评价主要针对谁或什么？listener 指正在看助手、准备回复的我方，不是当前消息的发送者。抱怨承办方、同事或服务不等于对我方不满；身体疼痛属于自身遭遇。",
            linkedMapOf("experience" to "发送者自己的遭遇、身体状态或事情结果",
                "third_party" to "聊天双方以外的人、机构或服务", "listener" to "聊天中的我方及我方行为",
                "none" to "没有明确评价或情绪对象", "unknown" to "对象不明确")),
        "speech_act" to Question("当前主要在做什么？区分向我方直接提问和转述别人的问话。比赛结束、事情完成不等于告别；同一句话可以结束旧事又开启新话题，new_topic 会另行判断。",
            linkedMapOf("share" to "分享经历、近况或计划", "vent" to "吐槽经历或表达不满",
                "question" to "向我方直接询问事实或意见", "request" to "明确请求我方行动或帮助",
                "confirm" to "确认已经讨论的具体内容", "play" to "友好玩笑或打趣",
                "pause" to "明确要求暂停交流或独处", "goodbye" to "明确告别或结束聊天",
                "unknown" to "不能确定")),
        "advice_need" to Question("当前是否明确向我方求办法或建议？倾诉疼痛、抱怨倒霉、叙述困难本身不是求解决方案。",
            linkedMapOf("yes" to "明确求办法或建议", "no" to "没有明确求办法或建议", "unknown" to "不能确定")),
        "commitment" to Question("原文是否有与当前话题相关、需要我方兑现的具体承诺或双方约定？必须能在前文或当前原话定位具体内容，不能凭接受、好、结束等单词判断。接受遭遇或结果不等于同意我方安排；只要拿奖就接受属于条件态度；已经完成的旧约定不算待兑现。",
            linkedMapOf("pending" to "存在明确具体且尚待落实的我方承诺或双方约定",
                "accepted" to "对方已明确同意具体安排，仍需我方兑现",
                "conditional" to "仅为条件性态度或假设，不是已确认约定",
                "none" to "没有相关待兑现约定，或仅谈自身经历或已完成的事", "unknown" to "缺少依据")),
        "own_fault" to Question("原文是否明确显示我方做错了具体事情且尚需回应或补救？第三方态度差、发送者摔伤、对结果不满均不能推定我方有错。已经接受道歉或解决的问题不要重复认错。",
            linkedMapOf("yes" to "有明确未处理的我方疏漏或过错", "no" to "没有这样的依据或已经处理", "unknown" to "责任不清")),
        "new_topic" to Question("当前是否提供了可继续聊的新话题或新计划？例如已经比完了，这两天在外面玩，既结束旧事又开启新话题，应选 yes。不是每条补充细节都算换话题。",
            linkedMapOf("yes" to "明确开启新话题或新计划", "no" to "仍在原话题或单纯告别", "unknown" to "不能确定")),
    )
}
