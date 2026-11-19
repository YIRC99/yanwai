package com.dogheadstrategist.chat.feature

import org.json.JSONObject

/**
 * 通知代办：「重要事项」的一个类型定义。
 *
 * 类型是数据不是代码——用户可以在设置页自己加，模型下一轮就认识。
 * 这条思路来自 Pinme：把「识别什么」从 prompt 里抽出来变成可配置项，
 * 比每次改硬编码的描述稳得多。
 *
 * [preset] 为 true 的类型不可删除，只能改描述或恢复默认。留这个限制是因为
 * 用户把类型清空之后模型会退化成什么都认不出来，那时候再去查为什么反而更麻烦。
 *
 * 和 Pinme 一样带一行 [outputExample]：光有描述，模型知道「要找什么」，
 * 但不知道「写多长、什么格式」；给一行示例，输出立刻规整。
 * 示例只会出现在批量提取的 prompt 里，闲聊走预筛直接不进这条路。
 */
data class MatterType(
    /** 稳定标识，同时也是模型回填的 title 的匹配依据。 */
    val key: String,
    /** 展示名，也是 prompt 里给模型看的类型名。 */
    val title: String,
    /** 一句话说明这类信息长什么样，模型主要靠它判断。 */
    val desc: String,
    /**
     * 这一类的 content 该长什么样，一行示例。
     *
     * 这是 Pinme 效果好的一大半原因：光有描述，模型知道「要找时间」，
     * 但不知道「写多长、用什么格式」。给一行示例，输出立刻规整。
     */
    val outputExample: String,
    /** 列表和小组件上显示的图标。 */
    val emoji: String,
    /** 类型色，用于列表左侧色条。 */
    val color: String,
    /** 多少小时后失去意义。0 表示不过期。 */
    val expireHours: Int,
    /** 预置类型不可删除。 */
    val preset: Boolean,
    /**
     * 重要性门槛，0-10。
     *
     * 模型会给每条提取结果打分，分数低于这个值就不收录。
     * 0 表示不筛——保持旧配置的行为不变，用户没调过就跟以前一样。
     *
     * 为什么按类型分而不是全局一个门槛：「取件码」这种漏掉代价很大，
     * 门槛该低；「其他要事」本来就模糊，门槛该高。一个数管不了两种情况。
     */
    val minImportance: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("title", title)
        put("desc", desc)
        put("outputExample", outputExample)
        put("emoji", emoji)
        put("color", color)
        put("expireHours", expireHours)
        put("preset", preset)
        put("minImportance", minImportance)
    }

    companion object {
        /** 兜底类型的 key，模型什么都没提取到时用它。 */
        const val NO_MATCH_KEY = "other"

        fun fromJson(obj: JSONObject): MatterType? {
            val key = obj.optString("key").trim()
            if (key.isEmpty()) return null
            return MatterType(
                key = key,
                title = obj.optString("title").trim().ifBlank { key },
                desc = obj.optString("desc").trim(),
                outputExample = obj.optString("outputExample").trim(),
                emoji = obj.optString("emoji").trim().ifBlank { "\uD83D\uDCCC" },
                color = obj.optString("color").trim().ifBlank { "#607D8B" },
                expireHours = obj.optInt("expireHours", 0),
                preset = obj.optBoolean("preset", false),
                // 老配置没有这个字段，读不到就 0：保持旧行为，不能因为升级就把已记的事挡掉
                minImportance = obj.optInt("minImportance", 0).coerceIn(0, 10),
            )
        }

        /**
         * 预置的六种。
         *
         * 和 Pinme 的取件码 / 火车票 / 验证码不是一回事——那些是截图里才有的东西，
         * 微信通知绝大多数是对话。这里按「对话里可能出现什么要记的事」来划分。
         */
        val PRESETS: List<MatterType> = listOf(
            MatterType(
                key = "time",
                title = "时间约定",
                desc = "具体的日期或钟点，连同要做什么事，例如「明天下午3点开会」",
                outputExample = "明天15:00 部门例会",
                emoji = "\uD83D\uDCC5",
                color = "#2196F3",
                expireHours = 48,
                preset = true,
                minImportance = 4,
            ),
            MatterType(
                key = "place",
                title = "地点",
                desc = "要去的地方或房间号，例如「302会议室」「三号楼」",
                outputExample = "302会议室",
                emoji = "\uD83D\uDCCD",
                color = "#FF9800",
                expireHours = 48,
                preset = true,
                minImportance = 3,
            ),
            MatterType(
                key = "todo",
                title = "待办",
                desc = "要你去做的事，有截止时间的一起带上，例如「周五前交材料」",
                outputExample = "周五前交材料",
                emoji = "\u2705",
                color = "#4CAF50",
                expireHours = 168,
                preset = true,
                minImportance = 4,
            ),
            MatterType(
                key = "money",
                title = "金额",
                desc = "转账、收款、要付的数目，例如「转你200」「会费50」",
                outputExample = "转你200",
                emoji = "\uD83D\uDCB0",
                color = "#FFC107",
                expireHours = 72,
                preset = true,
                minImportance = 5,
            ),
            MatterType(
                key = "code",
                title = "取件验证码",
                desc = "取件码、验证码、取餐号这类需要照抄的数字或字母组合",
                outputExample = "取件码 8-2-3012",
                emoji = "\uD83D\uDD11",
                color = "#9C27B0",
                expireHours = 24,
                preset = true,
                minImportance = 3,
            ),
            MatterType(
                key = "other",
                title = "其他要事",
                desc = "不属于上面几类、但明显值得记一笔的信息",
                outputExample = "下周三体检，空腹",
                emoji = "\uD83D\uDCCC",
                color = "#607D8B",
                expireHours = 24,
                preset = true,
                minImportance = 6,
            ),
        )

        /** 按 key 找预置类型，用于「恢复默认」。 */
        fun presetOf(key: String): MatterType? = PRESETS.firstOrNull { it.key == key }
    }
}
