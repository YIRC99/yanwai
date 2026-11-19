package com.dogheadstrategist.chat.feature

/**
 * 狗头军师的四个主功能。
 *
 * 这里只定义"是什么"，不定义"怎么实现"：每个功能的具体能力由对应的
 * [StrategistModule] 承载，界面与状态位统一从这里取文案，避免多处硬编码。
 */
enum class StrategistFeature(
    /** 稳定标识，写入日志与后续的开关配置。 */
    val key: String,
    /** 展示名，例如「自动起草」。 */
    val title: String,
    /** 首页与页面标题用的图标。 */
    val emoji: String,
    /** 首页一行说明：一句话讲清楚干什么。 */
    val tagline: String,
    /** 详情页说明：触发方式与适用场景。 */
    val detail: String,
    /** 当前阶段：已可用 / 框架已就位但功能未实现。 */
    val stage: Stage,
) {
    /** 代笔：在聊天里手动触发，读取前文后生成可直接发送的回复与话题。 */
    STRATEGIST(
        key = "strategist",
        title = "帮我回复",
        emoji = "✍️",
        tagline = "手动触发 · 生成回复与话题",
        detail = "在微信聊天里点「＋」里的「帮我回复 · 找话题」，或长按对方消息选择「帮我回复」。\n" +
            "选择对方身份和参考条数后生成，结果填入输入框，由你决定是否发送。",
        stage = Stage.AVAILABLE,
    ),

    /** 自动起草：监听通知，在你打开聊天之前就把可能的回复准备好。 */
    SENTINEL(
        key = "sentinel",
        title = "自动起草",
        emoji = "🎯",
        tagline = "自动读通知 · 提前准备回复",
        detail = "收到消息通知时自动判断是否需要回复，并提前准备好建议。\n" +
            "你打开聊天时回复已经在手边，不需要再手动触发。",
        stage = Stage.AVAILABLE,
    ),

    /** 续话：在输入框里按聊天记录和已输入内容预测接下来要说什么。 */
    RELAY(
        key = "relay",
        title = "自动填充",
        emoji = "💬",
        tagline = "输入框预测 · 接着往下说",
        detail = "你在输入框里打字时，输入框内、已输入文字的后面会以灰色接着显示预测内容。\n" +
            "点一下灰色部分即填入；不点就继续打你的，灰字会自动消失。\n" +
            "预测依据是最近的聊天记录加上你已经打出的内容。",
        stage = Stage.AVAILABLE,
    ),

    /** 人物档案：管理角色与背景，给每个人建立人设卡，供回复建议校准分寸。 */
    INTELLIGENCE(
        key = "intelligence",
        title = "人物档案",
        emoji = "👤",
        tagline = "角色与背景 · 人物卡",
        detail = "为每个聊天对象建立角色卡：对方是谁、什么关系、说话什么风格、有什么背景。\n" +
            "回复与话题生成时会读取这张卡来校准分寸与称呼。",
        stage = Stage.AVAILABLE,
    ),

    /** 通知摘要：用 AI 归纳替换微信的通知，不用打开 app 也能看到重点。 */
    COMMUNICATE(
        key = "communicate",
        title = "通知摘要",
        emoji = "📰",
        tagline = "AI 总结 · 接管微信通知",
        detail = "把微信的消息通知换成 AI 归纳后的一句话。\n" +
            "不用切回 app，扫一眼通知栏就知道谁说了什么、要不要回。",
        stage = Stage.AVAILABLE,
    ),

    /** 通知代办：从通知里把值得记一笔的事挑出来，随时能翻回去看。 */
    AIDE(
        key = "aide",
        title = "通知代办",
        emoji = "📒",
        tagline = "提炼要事 · 时间地点待办都不漏",
        detail = "从微信通知里挑出该记的事，随时能翻回去看。\n" +
            "时间约定、地点、待办、金额、取件码各成一类，可以自己加类型。\n" +
            "提取结果进列表，也能放到桌面上看。",
        stage = Stage.AVAILABLE,
    ),
;

    enum class Stage {
        /** 能力已可用。 */
        AVAILABLE,

        /** 框架已就位，具体逻辑尚未实现。 */
        FRAMEWORK,
    }
}
