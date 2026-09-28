# 联系人背景与分析来源

## 使用

单聊「帮我回」身份旁的「对方背景」支持查看、保存、取消和清空，最多 2000 字（按 Android 输入长度计数，部分表情占两格）。长期经历、偏好放在这里；临时风格和目标仍填写「本次补充要求」。背景只作为用户参考信息，当前聊天事实、拒绝和边界优先。

「聊天分析 → 情绪分析来源」默认仍为 JEV，升级不修改旧意图选项与三个显示开关。选择 LLM 后，一次 Chat Completions 请求返回情绪标签、意图解析、可能在意、情绪倾向，无需 JEV 地址和 Key。可使用已有分析配置，或主动选择复用「帮我回」模型；后者会跟随回复配置更新，但不会修改回复设置。纯 LLM 时旧 JEV 意图选择暂不使用，切回 JEV 后恢复原选择。

LLM 只显示定性标签，明确标注非概率，不填充 JEV 概率环。显示开关只投影卡片，不删除完整结果、不触发模型请求。解析缺字段、异常格式、截断、超时会失败，可点击卡片重试；取消会撤销网络与队列所有权，不自动切换服务。

## 存储与输入

- 复用 `ReplyContactKey`：已验证账号数据库路径哈希 + 稳定 talker；群聊、昵称、未确认账号无法建立背景保存键。
- 复用 UID 限制的 SettingsProvider / ReplyIdentityProvider 与 `noBackupFilesDir/reply_identities_v1.db`，增加独立背景表，身份编辑不会覆盖背景。背景不进入微信磁盘、日志或网络同步。
- 每次修改/清空产生新背景版本，原值不变则版本不变。背景读取完成前，分析等待；保存完成后新请求使用新版本，旧回调失效，旧回复不可作为新结果复制或继续换话题。
- 内存背景和异步读写同时绑定模块数据代次；重置言外数据后，微信进程里的旧背景不会被重新使用或写回。
- 账号、联系人、配置指纹、背景版本绑定请求。缓存包含实际消息、前文、时间、来源、模型与背景版本；展示选项不参与。进程内快照仍冻结已选择的上下文，重新打开后仅相同输入命中持久缓存。
- 回复和话题将背景放在 user 消息的独立 `contact_background` 字段；LLM 分析复用 `AnalysisState` 的最多 24 条、12000 字符前文与发送时间语义，不复用回复助手的 100 条上下文。
- JEV 的现有协议是 `model/state/questions` 固定选项决策，没有约定联系人背景字段。本版不改 JEV 输入；JEV+智能意图路径只把背景传给后面的 LLM。

## 深度思考适配

仅对核对过的官方域名和明确模型发送参数。自定义代理、未知模型、方舟 ep- 部署别名不猜测能力，显示按服务默认运行；不重试删参数、不偷偷换模型。

核对日期：2026-09-28。

- [DeepSeek 官方](https://api-docs.deepseek.com/guides/thinking_mode/)：已识别模型使用 `thinking.type=disabled`。
- [火山方舟官方](https://docs.volcengine.com/docs/ark/deep-thinking?lang=zh)：白名单 Seed 模型使用 `thinking.type=disabled`。
- [智谱官方](https://docs.bigmodel.cn/cn/guide/capabilities/thinking)：已确认支持的 GLM-4.5/4.6/4.7、GLM-5/5.1/5.2 系列使用关闭参数；GLM-5.3/FLASH 不允许关闭，不能发送 disabled。
- [MiMo 官方](https://platform.xiaomimimo.com/docs/en-US/usage-guide/passing-back-reasoning_content)：MiMo-V2.5 / Pro 使用 `thinking.type=disabled`。
- [Moonshot 官方模型卡](https://huggingface.co/moonshotai/Kimi-K2.5/blob/main/README.md)：Kimi-K2.5 官方 API 使用 `thinking.type=disabled`。
- [OpenAI 官方](https://developers.openai.com/api/docs/guides/reasoning)：支持的 GPT 使用 `reasoning_effort=none`，GPT-6 Astra 不支持 none。本版采用明确模型白名单，不对所有 GPT 盲发参数。

这些是请求参数适配，未经逐个真实模型运行验证；连接检测会显示使用的适配状态。

## 验证

离线：`tools/gradle.ps1 :app:testDebugUnitTest --offline --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'`。

Debug：`tools/gradle.ps1 :app:assembleDebug --offline --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'`。

可选真实 JEV 单例：`tools/gradle.ps1 :app:testDebugUnitTest -PanalysisLiveTest=true --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'`。仅从已忽略的 `jev.local.properties` 读取配置，发送虚构文本，不输出密钥。默认离线测试排除此项。

手机验收：A/B 联系人和不同账号分别填写背景，取消不保存、清空后重启仍为空；生成时改背景或切聊天，旧结果不能覆盖；JEV 空配置下选择 LLM，分别长按单条和打开自动分析；切回 JEV 验证原解读；切换三个显示开关不重发请求；修改模型/背景后重新生成不得使用旧缓存。手机效果需实机确认。
