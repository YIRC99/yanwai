package dev.jev.wechatmood.analysis

/** Independent observations, not assumed causes or relationship scores. */
object ChatFacts {
    data class Question(val instructions: String, val options: Map<String, String>)
    val questions = linkedMapOf(
        "target" to Question("当前情绪或评价主要针对谁或什么？listener 指正在看助手、准备回复的我方，不是当前消息的发送者。抱怨承办方、同事或服务不等于对我方不满；身体疼痛属于自身遭遇。",
            linkedMapOf("experience" to "发送者自己的遭遇、身体状态或事情结果",
                "third_party" to "聊天双方以外的人、机构或服务", "listener" to "聊天中的我方及我方行为",
                "none" to "没有明确评价或情绪对象", "unknown" to "对象不明确")),
        "speech_act" to Question("独立判断当前主要交流意图，不依赖场景或情绪答案。平静也可能拒绝，感谢之后仍可能拒绝邀约。" +
            "以当前真正要求对方回应的内容为主；明确拒绝或暂停优先于客套感谢。区分直接提问和转述。" +
            "事情完成不等于告别；忙碌疲惫不等于对我方不满。",
            linkedMapOf("share" to "分享经历、近况或计划", "vent" to "吐槽经历或表达不满",
                "question" to "向我方直接询问事实或意见", "request" to "明确请求我方行动或帮助",
                "confirm" to "确认已经讨论的具体内容", "play" to "友好玩笑或打趣",
                "pause" to "明确要求暂停交流或独处", "goodbye" to "明确告别或结束聊天",
                "refuse" to "明确拒绝提议、邀约或要求，或设定不愿接受的边界",
                "apologize" to "承认自己的具体问题或向我方道歉", "thank" to "主要在感谢我方帮助或回应",
                "clarify" to "澄清误解、纠正事实或解释原意", "busy" to "说明忙碌或疲惫，暂时无暇回应",
                "unknown" to "不能确定")),
        "topic_relation" to Question("当前消息与较早前文是什么关系？依据原话中的指代、问题和新内容判断。" +
            "时间间隔只作背景：隔夜不表示消气，跨午夜两分钟也不等于换话题。时间未知不能自行补造。",
            linkedMapOf("continuing" to "仍在讨论同一件事", "reopened" to "明确重新提起先前的问题或约定",
                "new" to "转向新的话题或计划，没有继续追问旧事", "unknown" to "无法确定与旧事的关系")),
        "emotion_shift" to Question("只比较当前发送者自己在前文与当前表达的情绪，是否有明确变化？" +
            "必须有同一发送者的可比表达；不能把我方的道歉当成对方已经缓和，也不能仅凭时间推断消气。",
            linkedMapOf("easing" to "原话明确表示缓和或好转", "intensifying" to "原话显示不满或紧张明显加剧",
                "steady" to "可比表达显示情绪仍在延续", "unknown" to "没有足够的可比表达，或变化不明确")),
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
