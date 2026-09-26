package dev.jev.wechatmood.reply

/** Keep all upstream assets intact; load the relevant original references for this request. */
object ReplyKnowledgeCatalog {
    fun paths(relationship: ReplyRelationship): List<String> {
        val common = listOf("goutoujunshi/SKILL.md",
            "goutoujunshi/references/practical/实战话术编排器：从一句回复到后续分支.md",
            "goutoujunshi/references/practical/巧妙接话技巧：让沟通更流畅的实用指南.md")
        val specific = when (relationship) {
            ReplyRelationship.CRUSH -> listOf("knowledge/06-吸引约会与关系启动.md")
            ReplyRelationship.FLIRT -> listOf("knowledge/06-吸引约会与关系启动.md",
                "practical/场景感、松弛感与社交校准：从接话到关系推进.md")
            ReplyRelationship.PARTNER -> listOf("knowledge/02-亲密关系心理学总论.md", "knowledge/07-沟通冲突与修复.md")
            ReplyRelationship.ELDER -> listOf("knowledge/11-婚姻家庭与生命周期.md",
                "practical/高情商拒绝他人：体面护边界的实用指南.md")
            ReplyRelationship.YOUNGER_SIBLING -> listOf("knowledge/11-婚姻家庭与生命周期.md",
                "practical/为他人提供情绪价值：温暖且有效的回应指南.md")
            ReplyRelationship.FAMILY -> listOf("knowledge/12-金钱家务育儿与双方家庭.md", "knowledge/07-沟通冲突与修复.md")
            ReplyRelationship.FRIEND -> listOf("practical/为他人提供情绪价值：温暖且有效的回应指南.md")
            ReplyRelationship.COLLEAGUE -> listOf("practical/提升表达逻辑性：从混乱到清晰的实用指南.md")
            ReplyRelationship.UNSPECIFIED, ReplyRelationship.OTHER -> emptyList()
        }
        return common + specific.map { "goutoujunshi/references/$it" }
    }
}
