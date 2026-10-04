package dev.jev.wechatmood.analysis

/** Interpretations of expression, not emotions, personality labels or verified intentions. */
data class ExpressionSignals(val probabilities: Map<String, Double> = emptyMap()) {
    init {
        require(probabilities.keys.all { it in definitions })
        require(probabilities.values.all { it.isFinite() && it in 0.0..1.0 })
    }
    data class Definition(val label: String, val criterion: String)
    fun labeled(): Map<String, Double> = probabilities.mapKeys { definitions.getValue(it.key).label }
    companion object {
        val definitions = linkedMapOf(
            "sarcastic" to Definition("反话", "字面夸奖或赞同与上下文立场形成反差，实际表达不同或相反意思；区分亲近玩笑与攻击，不默认恶意。"),
            "perfunctory" to Definition("敷衍", "对前文具体问题回避投入或以空泛回应应付；短回复、忙碌、普通确认本身不成立。"),
            "probing" to Definition("试探", "用假设、侧面提问或留有余地的说法探查对方态度；正常询问信息不成立。"),
            "holding_back" to Definition("欲言又止", "表达想说却收回、留白或难以开口，相关前文支持还有未说完的内容；省略号本身不成立。"),
            "defensive" to Definition("嘴硬", "口头否认或逞强与自己已明确表达的在意、需要或事实有反差；不能把明确拒绝改读成愿意。"),
            "self_mocking" to Definition("自嘲", "用夸张、反讽或玩笑调侃自己；区分认真自责与批评他人。"),
            "seeking_comfort" to Definition("求安慰", "在表达处境或感受时向交流对象寻求被理解、陪伴或安抚；仅陈述困难不能自动判定。"),
            "seeking_validation" to Definition("求认可", "借分享或提问期待肯定自己的努力、判断或价值；区分一般信息核对。"),
            "soft_refusal" to Definition("委婉拒绝", "借理由、推迟或缓和措辞表达不接受当前提议；真实未定不能直接判为拒绝。"),
            "boundary" to Definition("划清界限", "对话题、行为或互动明确设定可接受范围；表达边界本身不代表生气或关系破裂。"),
            "repair" to Definition("递台阶", "用缓和、让步或留余地帮助双方从尴尬或争执中继续交流；不编造双方已和好。"),
            "acting_cute" to Definition("撒娇", "以亲昵、示弱或俏皮语气争取关照或配合；不能仅凭语气词判定，不据此推断恋爱关系。"),
        )
    }
}
