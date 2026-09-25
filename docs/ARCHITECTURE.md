# 1.3.0 架构检查与优化

本轮保留现有消息识别和两轮模型分析协议，集中拆开阻塞界面、请求调度与缓存所有权。没有将整个 Hook 层重写，也不以编译通过代替微信实机验收。

## 当前职责

| 层 | 主要模块 | 职责 |
| --- | --- | --- |
| 微信适配 | MessageSniffer / MessageMetadata / MessageContext | 生命周期、可见消息、真实消息字段和前文；不生成模型提示内容 |
| 显示和入口 | HostUi / BubbleDecorator / MessageIntentMenu | 会话开关、消息卡、单条菜单；不直接调用模型 HTTP |
| 状态 | ConversationSwitches / ManualAnalysis / SettingsSession | 会话记忆、手动选择、已验证配置快照 |
| 跨进程桥 | SettingsBridge / ModulePrefs / SettingsProvider | 异步调度、广播版本校验、Android IPC 与持久设置 |
| 请求调度 | AnalysisQueue / SignalAnalyzer | 两并发、可见性、取消、重试冷却与应用接线 |
| 模型分析 | ChatAnalysis / JevHttpClient | 两轮分析、同一配置快照、可取消 HTTP 和协议解析 |
| 缓存 | MoodStore | 结果缓存与任务 Claim 所有权 |
| 独立设置页 | MainActivity / SettingsUiState / UpdateNotice | 配置、状态、版本及更新入口 |

## 已处理的问题

- 原微信扫描同步读取 Provider、上报状态，慢 IPC 会阻塞界面。现在只申请异步工作，同类任务合并；状态锁不等待 IPC。分析任务也不再同步等待设置读取，避免占住请求名额。
- 离开页面、滑出消息、关闭会话时，原 HTTP 仍占用请求名额。现在取消直接传到 OkHttp，旧回调不恢复已取消任务；正常取消不生成失败卡或冷却。
- 原缓存占位与结果检查分离，旧任务结束时可能干扰同键重试。现在原子获取 Claim，完成和释放都校验所有权；缓存清空也撤销旧 Claim。
- 同一消息字段重复反射查找，改为按 Class 缓存 Field（包括父类）；仍实时读取字段值。设置页同一批保存触发的多次刷新合并为一次。
- 版本从 Gradle 常量迁到唯一文件，APP 顶部直接展示，提交钩子检查两个版本字段同时增加。图标采用本次生成的原始 PNG，由 Android 自适应图标背景承载。

## 检查结果与后续边界

队列和设置桥现在可脱离 Android 单独测试。已覆盖慢 Provider 与广播交错、配置重置、取消和同键重试、晚到结果以及底层 HTTP 取消。具体构建和安装记录见 [VERIFY.md](VERIFY.md)。

- MessageSniffer 仍负责较多微信生命周期/反射接线；MainActivity 仍集中配置与展示。继续拆分应跟随具体功能变化，避免只搬文件而增加间接调用。
- 手动选择和成功结果当前为进程内缓存，没有总量上限。后续应先明确淘汰规则，再做容量限制，避免手动选择意外消失或重复调用模型；本轮未擅自改变缓存语义。
- 原生菜单和列表布局依赖微信版本，需用户测试长按、快速切换聊天、滚动与关闭后的卡片清理。
- Lint 仍有存量警告，包括资源整理、硬编码文案、宿主 View 生命周期等；没有为了消除提示更换微信内部控件或扩展本轮改动。未进行真机性能测量，也未声称所有潜在问题均已消除。
