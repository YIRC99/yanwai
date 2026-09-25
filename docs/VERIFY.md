# 顶部关闭同时清除单条分析（2026-09-25）

- 根据手机反馈调整交互：先手动分析、再打开顶部分析、再关闭时，当前聊天全部卡片立即隐藏，并清除该聊天的手动选择；其他聊天不受影响。开关关闭后仍可再次长按单条分析。
- 清除只发生在当前会话开关成功保存为关闭后；切换页面、界面同步开关状态或保存失败不清除手动选择。已有模型结果保留为内存缓存，但不会自行恢复显示。
- 回归测试先复现旧行为失败，再修复；全套离线单测 **116/116 通过**。Debug APK、lintDebug 通过，Lint **0 errors、90 warnings**。
- ADB 覆盖安装返回 **Success**，核对包信息 **1.2.0 / 16**、更新时间 **2026-09-25 23:28:19**。未主动启动或停止微信，重启微信后的操作效果由用户验收。

---

# 单条消息翻译意图（2026-09-25，本地开发包）

- 在微信原生消息长按菜单追加图标与「翻译意图」，只处理对方合格纯文字。单条分析沿用消息下方的卡片，聊天自动分析开关保持原状。
- 手动选择按会话和消息身份隔离，固定首次选择的上下文和缓存键；支持多条选择、切聊天后恢复、已有结果复用与失败卡点击重试。选择和结果均为进程内缓存，微信重启后清空。
- 菜单项关联实际消息 View 的弱引用，点击时重新校验身份；菜单与聊天绑定定位共用一次 APK 解析，分别处理定位失败。静态审查提出的绑定查找异常跳过菜单安装问题已修复。
- 新增 3 项行为测试，先确认 3 项断言失败，再实现；全套离线单测 **115/115 通过，0 failures / 0 errors / 0 skipped**。Debug 构建与 lintDebug 通过，Lint **0 errors、90 warnings**；未调用真实付费模型接口。
- 设置页及 README 更新单条入口与关闭自动分析后的行为说明。本地包沿用 **1.2.0 / 16**，未发布新版本。
- 未安装或操作手机；原生菜单实例、图标、气泡布局与滚动等实际效果由用户验收，步骤见 [单条分析验收](MANUAL_ANALYSIS.md)。编译和单测不代表微信版本适配已在真机验证。

---

# 1.2.0 作者联系、免费说明与手机安装（2026-09-24）

- versionName=1.2.0、versionCode=16，汇总此前 UI 与诊断改进。页面底部加入作者微信二维码、微信号 YIRC99、一键复制、开源仓库链接，以及项目完全免费且第三方模型另行计费的说明；README 引用同一张打包资源。
- 二维码来源为用户桌面的 `author_code.jpg`，原样复制为 `app/src/main/res/drawable-nodpi/author_wechat_qr.jpg`。实际读取 APK 内该图片，SHA-256 与来源一致，未生成或重绘二维码。
- Debug、未签名 Release、lintDebug 构建成功；禁用构建缓存与 Kotlin 增量编译，实际 APK 包信息为 1.2.0 / 16。Lint **0 errors、93 warnings**。本轮为资源、文案与简单入口变更，未新增或重复执行 UI 测试。
- 经用户授权，ADB 覆盖安装返回 **Success**；安装前手机为 1.1.2 / 13，安装后再次读取系统包信息确认为 **1.2.0 / 16**。未卸载应用、清理数据或强制停止微信，未主动打开应用；微信需重新启动以加载新版模块，二维码扫码与界面效果交由用户验收。
- 构建后无遗留 Java 编译进程，未启动项目后台服务；本轮未推送或发布 GitHub Release。

---

# 1.1.4 设置页状态与使用引导（2026-09-24）

- versionName=1.1.4、versionCode=15。首屏改为使用概览、分别标注的模型/微信状态及下一步操作；保留日常开关，模型配置、微信启用、日志排查和更新依次排列。预设地址隐藏，自定义保留完整字段；Key 教程、启用步骤和详细日志按需展开，导出日志入口直接可见。
- 区分未配置、未保存、待检测、检测中、通过、失败，以及分析暂停、结果隐藏。检测时锁定配置并显示进度，结果行内展示，失败按钮跳到具体原因。模型检测通过不代表微信在线，历史运行记录带时间和明确说明。
- 补充浅深色语义配色、字号与间距规范，保留原生控件、字体缩放和 48dp 以上触控区域，设计约定见 [DESIGN.md](../DESIGN.md)。更新仅涉及设置界面与状态呈现，未改动聊天分析、模型协议或微信注入路径。
- 新增 6 项状态判定测试，先确认失败再实现；全套离线单测 **106/106 通过**，0 failures / 0 errors。Debug 与未签名 Release 构建、lintDebug 通过，Lint **0 errors、81 warnings**。最后调整失败按钮滚动目标后重新构建两种 APK 并执行 lint。
- 未启动手机应用、模拟器或后台项目服务。浅深色实际观感、字体放大和手机交互由用户安装后验收，构建通过不代表视觉验收完成。

---

# 1.1.3 运行日志与设置桥接诊断（2026-09-24）

- versionName=1.1.3、versionCode=14。新增微信聊天开关长按菜单、微信设置入口长按、失败对话框与桌面排查面板的日志导出。Android 10+ 直接存入下载目录，Android 9 复制文本；失败时保留复制/查看入口。
- 微信私有目录持久化日志，磁盘失败时保留内存副本，关键错误和堆栈镜像到 LSP；桌面显示注明时间的微信上报片段或明确缺失。增加设置读取、保存、广播、授权和页面打开的错误码及堆栈，开关保存要求完整且匹配的回执。
- 用户 LSP 日志确认微信访问言外 Provider 和 Activity 不可见，桌面可打开相同 Activity；HMA 系统作用域存在，但缺少其规则及放行后的对照。不能声称已证实微信 8.0.77 不兼容或已彻底修复用户环境，证据与实机验收见 [DIAGNOSTICS.md](DIAGNOSTICS.md)。
- 新增 6 项测试，先确认 6 项断言失败，再实现脱敏、堆栈保留、重启读取、轮转及磁盘故障内存回退。完整离线单测 **100/100 通过**，0 failures / 0 errors；Debug、未签名 Release、lintDebug 构建成功，Lint **0 errors、58 warnings**。
- Debug APK 输出元数据核对为 1.1.3 / 14。未安装或启动微信，未代替用户进行手机导出、分享、隐藏规则放行及 8.0.77 验收；未发布线上 Release。
- 使用 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process` 完成构建，构建后确认无 Java 编译进程；没有启动项目后台服务。

---

# 1.1.2 GitHub Release 更新提醒（2026-09-24）

- versionName=1.1.2、versionCode=13。打开设置页后检查 GitHub 最新正式 Release，增加手动检查、发布页入口及同版本一次弹窗。检查正常间隔 24 小时，失败间隔 15 分钟，手动至少间隔 1 分钟，并处理 API 限流及 ETag / 304。无后台更新服务，不在微信进程检查。
- 对照官方 Release API 与匿名限流文档；对 `YIRC99/yanwai` 的真实匿名 GET 返回 HTTP 200，tag `1.0.0`、非草稿/非预发布，附件 `yanwai1_0_0.apk`。仅 push 代码不会发布新版；发布规则见 [UPDATES.md](UPDATES.md)。
- 新增 6 项自动化测试，覆盖数字版本顺序、草稿/预发布/无 APK 排除、固定仓库跳转、检查间隔、匿名 GET、ETag / 304、404、429 等待、重定向和损坏 JSON。HTTP 异常及高版本场景由本地 MockWebServer 注入，不冒充 GitHub 实际发布结果。
- 完整离线测试 **94/94 通过**，0 failures / 0 errors；Debug、未签名 Release 和 lintDebug 成功，Lint **0 errors、57 warnings**。实际检查两份 APK 均为 versionCode=13 / versionName=1.1.2。
- 静态复查后修正缓存提醒时机：设置页恢复后将检查投递到主线程下一轮，等待 Activity 生命周期进入 RESUMED；网络结果返回时也检查生命周期，销毁时取消页面任务。未进行手机安装、弹窗或浏览器跳转验收；已有 1.1.1 的划卡验收不等同于本轮更新提醒验收。
- 构建已退出，无遗留本项目服务或 Gradle / Kotlin 编译进程。本轮仅本地提交，未推送或创建线上 Release。

---

# 1.1.1 划掉最近任务后的配置恢复（2026-09-24）

- versionName=1.1.1、versionCode=12。用户确认操作是从最近任务划掉言外。旧代码在 Provider 读取失败时清空配置的路径已确认；尚未捕获手机划卡瞬间的系统原因，不把推断写成实机复现结论。
- 对照 WeKit 源码后，保留宿主内存中的可信配置，保存时用受签名权限保护的定向广播同步；首次无配置仍停用。revision 防止迟到消息覆盖新设置，generation 处理清除数据后的版本重置，Provider 读取与广播处理串行，待验证期间不允许广播恢复旧 Key。没有新增前台保活服务。来源和边界见 [BACKGROUND.md](BACKGROUND.md)。
- 新增 8 项设置状态测试，覆盖首次失败、连续断连、关闭、清空 Key、旧广播、恢复连接、清除数据及待验证时旧广播。补充待验证旧广播用例后先确认旧实现断言失败，再修复；最终完整离线单测 **88/88 通过**，0 failures / 0 errors。
- `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug` 成功。Lint **0 errors、53 warnings**；使用进程内 Kotlin 编译与 no-daemon。首轮 lint 曾指出旧 Android 广播注册分支缺少导出标记，改为 ContextCompat 带显式导出和发送者权限后通过。
- 独立静态复查发现的配置重置与旧在途读取问题均已修复并复查。实际检查 Debug 与未签名 Release APK 均为 versionCode=12 / versionName=1.1.1，Debug APK 包含同步权限声明。
- 仅只读检查已连接设备状态（当时安装的是 1.0.0）；未安装或启动手机应用、未停止微信、未读取聊天内容、未调用真实模型。划卡后新消息分析、关闭/清空 Key 后停止、广播权限及重启首次读取由用户实机验收。
- 构建已退出，检查未遗留本项目服务、Gradle 或 Kotlin 编译进程。仅本地提交，不推送。
- 后续经用户授权，ADB 覆盖安装返回 Success，手机包信息确认 versionCode=12 / versionName=1.1.1。打开言外并重启主用户 0 的微信，均返回 Status: ok；未操作分身或发送聊天消息。用户按划掉言外后继续使用的验收场景反馈“可以了”。此反馈不扩展为所有厂商后台策略、清空 Key、清除数据或微信被杀后恢复均已实测。
- 用户确认可用后授权推送 main，并要求准备新版 Release 说明，文案见 [1.1.1 发布说明](releases/1.1.1.md)。

---

# 1.1.0 多渠道与 Key 申请指引（2026-09-24）

- versionName=1.1.0、versionCode=11，保留 applicationId。设置页提供 Jev 官方、OpenRouter、Vercel AI Gateway、自定义 Jev 兼容接口，预设自动匹配接口与模型，自定义支持填写模型名。
- 每个预设显示简短申请步骤、申请 Key 的网页入口和官方协议文档；Vercel 使用 AI Gateway 专用 Key，并注明账户绑卡验证。页面只打开官方网页，不替用户注册、充值或发送消息。更完整资料见 [渠道教程](API_PROVIDERS.md)。
- 精确域名和已知路径用于旧配置迁移；陌生地址、特殊路径和自定义参数保持不变。各渠道单独保存 Key，首次切换前的旧活动配置在同次原子提交中保留。独立审查发现的旧 Key 丢失问题，已先复现两项失败用例，再修复并复查。
- 同一条两轮分析使用同一份渠道、模型、地址与 Key 快照；只调整平台接入，不改情绪问题、候选模板、动作条件或概率校验。没有自动改用普通聊天模型。
- 先观察旧 OpenRouter / Vercel 地址适配的两项测试失败，再实现兼容。最终 **80/80 离线测试通过**（无失败、错误或跳过），覆盖配置迁移、概率原样传递、HTTP 错误、HTTP 200 错误封装、禁止自动重定向和原分析回归。
- 使用生产 `JevHttpClient`、`ChatAnalysis`、`JevProtocol` 和用户授权的 Key 进行显式真实验证。OpenRouter 三组虚构中文对话均完成两轮，共六次 HTTP 请求；每轮完整答案数量与所有概率/置信度通过应用校验。实际响应模型 `typesafe/jev-1.13-20260917`，三组耗时约 1484 / 769 / 717 ms，仅代表本机当次测量，不作为手机网络或长期性能承诺。
- Vercel 兼容接口返回 HTTP 403、`customer_verification_required`；测试明确记录 BLOCKED / skipped，应用提示去 AI Gateway 控制台绑定信用卡。用户确认按文档接入即可，本轮不声称 Vercel 推理成功。TypeSafe 官方保留原协议，本轮未新增官方 Key 实测。在线验证与离线套件分开，普通构建不发送模型请求。
- 完整无缓存构建：Debug、未签名 Release、lintDebug 均完成。Lint **0 errors、54 warnings**，包括硬编码文案、未使用资源和现有结构提示，未声称零警告。
- 实际检查两份 APK：versionCode=11 / versionName=1.1.0，DEX 中含 `Jev 1.1.0` 和两个新渠道的正确接口，不含 `Jev 1.0.0`；仓库输入文件和两份 APK 均未匹配本轮两枚真实 Key。Key 未进入源码、BuildConfig 或命令行构建参数，测试仅由进程环境读取；本机临时加密凭证在测试后清理。
- 未安装或启动手机应用，没有进行 UI 自动化；渠道切换、申请网页跳转、跨进程读取和微信气泡效果由用户实机验收。没有启动本项目后台服务；使用 no-daemon 与进程内 Kotlin 编译，结束时检查无遗留 Gradle / Kotlin 编译进程。本轮仅本地提交，不推送。

最终构建命令：

```powershell
& ./tools/gradle.ps1 -GradleArgs @(':app:clean', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleRelease', ':app:lintDebug', '--no-build-cache', '-Pkotlin.incremental=false', '-Pkotlin.compiler.execution.strategy=in-process', '--no-daemon', '--max-workers=2')
```

安装试用使用 `app/build/outputs/apk/debug/app-debug.apk`。Release 包未签名，公开发布前仍需维护者固定签名。

# 1.0.0 言外与用户 API 配置（2026-09-23）

- 应用显示名为「言外」，versionName=1.0.0、versionCode=10，保留原 applicationId 以支持覆盖升级。
- 设置页提供 API 地址和掩码 Key，默认 Jev 官方地址，可保存自定义完整地址；连接检测先保存输入。配置经仅允许本应用/微信 UID 的设置桥读取；同一条分析的两轮请求使用同一份配置快照。
- 移除构建时读取个人密钥和导入脚本；新安装或从个人版升级需手动填写 Key。自定义地址仍需兼容 Jev 协议，本轮未实现 OpenRouter 协议适配。
- 新增 5 项配置校验测试，完整单测 **64/64 通过**。Debug、未签名 Release 构建及 lintDebug 通过；没有启动项目服务、安装到手机或调用真实模型，界面与跨进程保存效果待用户实机验收。
- 首次增量构建曾复用包含旧版本字符串的 Kotlin 编译结果；普通 clean 仍从构建缓存恢复旧结果。禁用构建缓存与 Kotlin 增量编译后完整构建通过。实际检查两份 APK：均包含 `Jev 1.0.0`，均不含 `Jev 0.5.2` 或本机原先那枚 Key。当前已跟踪文件和 Git 历史也未匹配该 Key；这是针对已知 Key 的检查，不代表穷尽所有秘密。
- 最终构建命令（PowerShell）：

```powershell
& ./tools/gradle.ps1 -GradleArgs @(':app:clean', ':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleRelease', ':app:lintDebug', '--no-build-cache', '-Pkotlin.incremental=false', '--no-daemon')
```

Release 产物尚未签名，发布前需使用维护者自己的固定签名。

# 0.5.2 对话对象、建议条件和独立动作选择（2026-09-23）

- 保留情绪提示词和概率展示。第一轮增加针对对象、交流行为、是否求建议、待兑现约定、我方未处理过错、新话题六项判断；不增加第三轮调用。
- 第三方/自身遭遇吐槽可纠正旧的冲突分流；新话题解除旧进展的收尾限制，同时保留明确的邀约等场景。分享学习工作经历不因话题名词直接排除。场景不明确但交流行为明确时仍可选择动作。
- 新增独立动作选择与条件过滤：道歉要求我方过错，兑现要求具体待兑现约定，告别要求明确结束交流。条件性接受不算约定；接受解释可产生缓和解读，无需捏造待兑现安排。条件是模型估计，不能声称已经证明对方真实心理。
- 第二轮分别选择解读与动作，保留 none；解读无可靠结果时也可独立展示可靠动作。移除模板原先绑定的 64 条建议文案，避免兜底时偷偷沿用旧建议。无候选的问题不发送，不生成只有 none 的问题；实际请求的每个答案都必须通过严格校验。
- 先观察 5 项新策略用例失败；审查发现的分享回应、保留新邀约、接受解释三项回归先失败后修复；无候选问题的两项协议用例先失败后修复。独立只读复核确认三项问题已处理，未发现新的重要问题。
- 最终 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`：BUILD SUCCESSFUL；59 项测试，0 failures / 0 errors；Lint 0 errors、47 warnings（既有工具链/资源警告）。构造的模型响应只验证工程条件、路由和协议，不能证明真实 Jev 分析质量。
- ADB 覆盖安装返回 Success；手机包信息 versionCode=9、versionName=0.5.2。打开助手后停止并重新启动主用户 0 的微信，启动 Status: ok；未操作分身、未发送消息。未读取聊天页面或主动调用真实 Jev，实际建议质量和新版卡片由用户验收。
- 本次范围为三项优先改进；连续短消息合并与原文片段填充回复尚未实现。1000 字符过滤、前文范围、缓存、两轮之间的取消检查保持不变。
- 构建已退出，结束前检查未留本项目后台服务；只本地提交，不推送。

---
# 0.5.1 精简卡片与情绪概率（2026-09-23）

- 按用户反馈将首行统一为 `Jev 0.5.1`，恢复开心、平静、生气概率；失落、委屈、缓和或不明确有非零显示概率时追加，不强行并入生气。低置信度仍展示原始情绪分布。
- 移除好感/互动线索、原话引用、修辞标题和「先别急着猜」占位话，同时移除不再使用的 warmth/evidence 问题。前文及两层判断保留；无可靠解读时只显示版本与情绪概率，不填通用建议。
- 调整 32 套模板的两类动作文案，压缩成一句具体动作；没有改变模板数量或声称真实建议质量已验证。
- 新增精简卡片和不确定回退测试，先确认旧实现两项失败，再完成修改。最终执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`：BUILD SUCCESSFUL；48 项测试，0 failures / 0 errors；Lint 0 errors、47 warnings。
- ADB 覆盖安装返回 Success；手机包信息确认 versionCode=8、versionName=0.5.1。打开助手后停止并重新启动主用户 0 的微信，启动返回 Status: ok；未操作分身、未发送聊天消息。未检查新版聊天卡片或真实 Jev 输出，实际文案与建议效果交由用户在手机中验收。
- 构建已退出，未启动项目后台服务；保留工作前已有进程。仅本地提交，不推送。

---
# 0.5.0 闲聊潜台词与两层 Jev（2026-09-23）

- 新增 8 类场景、32 套分析卡，包含问题、两种竞争解释、信息不足选项、分支动作及适用/停止阶段。第一层判断场景、情绪、互动线索和对话阶段；第二层选择分析卡、判断解读并选择原话参考，代码组合展示。
- 生气/委屈与愿意交流分别判断，不给恋爱好感打分；概率注明模型推测。普通解释不沿用戏剧化标题和动作，群聊使用「互动线索」。第一层不适用或不明确时只调用一次；第二层不确定时显示克制提示。
- 两轮之间及第二轮返回时重新检查分析开关和目标可见性；取消释放占位，第二轮失败不缓存半成品。正常每条最多两轮，每轮 HTTP 最长 30 秒，并发仍最多两条流水线。保留前文、1000 字符过滤、缓存隔离和重试入口。
- 先看到新路由测试失败、等待行动/接受阶段模板过滤测试失败、两轮编排与第二轮协议测试 10 项失败，再完成实现并验证通过。
- 最终执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`：BUILD SUCCESSFUL；46 项单元测试 0 failures / 0 errors；Lint 0 errors、47 warnings（既有资源/原生控件与工具链警告）。
- 独立只读代码审查未发现 Critical / Important 回归；核对双轮顺序、失败与取消、阶段过滤以及原有缓存和长度限制。
- 沿用用户已授权的 ADB 覆盖安装方式，`adb install -r` 返回 Success；手机包信息确认 versionCode=7、versionName=0.5.0。本轮未启动应用、未操作聊天或发送消息、未真实调用 Jev；用户重启微信后验证模型质量、卡片长度、等待时间及实际聊天效果。离线测试使用人工构造的模型响应，不作为模型质量证明。
- 构建已退出，未启动项目后台服务；保留工作开始前就存在的进程。只本地提交，不推送。

---
# 0.4.1 上下文与长度限制（2026-09-23）

- 分析当前可见的对方纯文本时，读取同一列表中它之前最多 10 行，筛选同会话纯文本作为约 5 对双方前文；区分我/对方，群聊保留发送者标识，按时间顺序提交。不读取目标之后的消息，不扫描整库；前文缺失、非文本、未知方向或读取失败时可不足 10 条。
- 正文超过 1000 个 Unicode 码点直接跳过，不截断，不显示分析卡，也不进入前文；长度包含正文空白，不包含群聊发送者前缀。恰好 1000 字符仍可分析。
- 仍然一次 Jev 请求同时询问情绪、意图、风险、固定选项建议。缓存键增加消息 ID、发送者及有序前文，防止同样文字在不同语境下复用结果；请求、可见性判断、卡片及手动重试使用同一键。
- 先验证新增长度测试在旧行为下失败；上下文、协议和缓存测试在空上下文/旧缓存行为下 7 项失败，随后实现通过；补充空白长度边界测试先失败再修复。
- 最终执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`：BUILD SUCCESSFUL，38 项单元测试全部通过；Lint 0 errors、47 warnings，保留既有原生控件/资源和工具链警告。
- 已生成 versionCode=6、versionName=0.4.1 的 debug APK。本轮未安装手机、未启动应用、未调用真实 Jev 接口；上下文对判断的实际改善、微信列表读取和滚动体验由用户覆盖安装并重启微信后验收，不把单测通过视为实机验证。
- 构建已退出，未启动项目后台服务。仅本地提交，不推送。

---
# 0.4.0 气泡内绘制核验（2026-09-23）

- 用户在风险说明后明确选择继续实现。账号风险不因 WeKit 可以实现而归零；评估见 INLINE_RENDERING_RISK.md。
- 保留微信消息行和 ViewHolder，在已识别的对方纯文本气泡下追加分析卡；重新绑定、离开页面和关闭绘制时清理。语音、图片、文件、视频、引用卡片和未知类型均不进入分析。
- 聊天标题栏右侧增加「绘制」开关，长按提供状态、分析本屏和助手设置。显示开关仅隐藏结果，总分析开关才停止新请求。
- 真机发现 LauncherUI 的内嵌 ChattingUILayout 位于 android.R.id.content 外层，旧扫描范围漏掉该模式；扫描入口改为 decorView，保留当前可见行过滤。这是本轮实测确认的漏识别场景，不宣称解释了所有先前现象。
- 新增缓存键碰撞测试：旧实现对 Aa/BB 测试失败，改为带会话边界的 SHA-256 后通过，防止不同文字显示同一份结果。
- 最后一次完整构建：testDebugUnitTest + assembleDebug + lintDebug，BUILD SUCCESSFUL；28 项单元测试全部通过；Lint 0 errors、47 warnings（包含原生控件及既有工具链/资源警告）。
- 安装版本 versionCode=5、versionName=0.4.0。升级后先启动助手重新建立设置授权，再重启微信；期间曾看到“设置连接失败”，重新打开助手并重启后恢复，未绕过 UID 白名单。
- 微信 8.0.71 / Android 16 真机截图确认：标题栏有绘制开关；收到的纯文本下有真实 Jev 结果；同屏引用卡片未加分析卡；关掉绘制后卡片移除、恢复原布局，再开可恢复结果。长按能打开本屏状态和操作。截图保存在忽略目录 verification/inline-ready.png、inline-off.png、inline-scrolled.png，不将私人聊天截图提交到 Git。
- 审查发现并修复：旧标题栏仍 attached 但已隐藏时需向当前聊天重新挂载；不支持的气泡布局应报告，不能静默称绘制成功。
- 限制：当前逐条分析，非多轮上下文推理；未全面覆盖所有微信布局、所有媒体样本、键盘或长期滚动。已有网络请求不主动取消，最长可能继续约 30 秒。没有发送聊天消息。
- Gradle 使用 no-daemon，已退出；未启动本项目后台服务；保留其他会话已有进程。仅本地提交，不推送。

---
# 0.3.0 交付核验（2026-09-23）

## 本次修改

- 直接使用传统 Xposed 包加载入口，移除 KSP 生成入口对 initZygote 状态的依赖；补 Activity 前台初始化、首次加载提示和初始化失败提示。
- 微信旧/新设置页增加助手入口，聊天底部增加独立占位的状态条，点击查看本屏分析；不再替换消息行，避免干扰微信回收机制。
- 在原有 ListView 之外接入新版 MvvmChattingItem 消息绑定；仅观察当前可见行。对照 WeKit 源码版本 `bdc7f18033d87a2f6307d2caedfdc6502986a401` 的 WeChatMessageViewApi / MessageInfo / MessageType 确认绑定参数和字段。
- 白名单过滤 `field_type == 1 && field_isSend == 0`。全部媒体、文件名、卡片和未知消息跳过；时间样式和方括号纯文本保留；群聊去除发送者前缀。
- 模型失败原因可见，排队任务开始前检查消息是否仍在屏幕中。已发出的请求仍可能占用槽位至多 30 秒，返回结果不混入其他页面。
- 设置 provider 保留调用 UID 白名单；首次打开独立 APP 时向微信建立 URI 访问授权以处理包可见性。

## 实际验证

- 修改前手机安装微信 8.0.71，独立 APP 没有 wechat_runtime.xml，Logcat 中没有模块加载记录；只看到旧版模型连接成功日志。因此无法从这些证据确认旧版是否注入，不把源码推测称作已证实的设备根因。
- 先添加 MessageMetadata 行为测试，用空实现运行，7 项中 3 项断言失败；实现后全部通过。
- 最终运行 `powershell -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --max-workers=2`：BUILD SUCCESSFUL。
- 单元测试共 26 项：协议 5、既有文本规则 14、消息元数据 7；失败/错误均为 0。既有文本规则已不用于线上识别，仅保留旧测试。
- Lint：0 errors、44 warnings。未声称消除硬编码文案、既有资源及工具链兼容性警告。
- APK 入口为 `dev.jev.wechatmood.xposed.HookEntry`，不含旧 `META-INF/yukihookapi_init`。
- `adb install -r`：Success。手机包信息：versionCode=3、versionName=0.3.0。
- `adb shell am start -W -n dev.jev.wechatmood/.MainActivity`：Status: ok。助手启动日志中未见授权失败；包可见性查询中微信的一个可见关系块包含本模块。
- 独立代码审查指出键盘 inset 可能挤压宿主页面，已修为只采用导航栏 inset，并重新完成构建、单测和静态检查。

## 用户验收边界

未操作用户聊天、未重启微信、未触发真实聊天分析。请用户彻底结束微信后重新打开，确认首次加载提示、我→设置底部入口、聊天状态条、键盘与滚动显示，以及媒体跳过。

没有后台启动本项目服务；Gradle 以 no-daemon 运行且已结束。其他会话已有的进程未操作。只本地提交，不推送。

---
# 0.2.0 交付核验（2026-09-23）

## 用户目标与实现差距

用户要求参考聊天内 Jev 提示图继续开发，模型信息复用 jev_demo，不再手填；修复设置页后构建并安装 APK。

本次实现：个人配置内置、原生 Jev 请求、情绪/意图概率、风险、固定建议选项、消息行提示承载、设置页遮挡与配色修复、真实跨进程状态。
未实现参考图的完整对话推理及任意自然语言建议生成；当前只分析单条文字消息。微信实机注入和布局需用户验收。

## 已执行

- `powershell -File tools/import-jev-config.ps1`：从用户指定的 jev_demo 导入配置，终端不输出密钥。
- `powershell -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug --no-daemon`：构建通过。
- 单测报告：JevProtocolTest 5 项、MessageTextPickerTest 14 项，失败和错误均为 0。
- 新增协议测试覆盖原生请求、概率/建议呈现、缺字段、概率越界/不归一、未知建议拒绝。
- 通过 jev_demo 现有 evaluate 客户端真实调用 Jev：返回模型 jev-1.13.0，四类问题均通过原项目响应验证；示例耗时约 3.3 秒。
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`：Success。
- `adb shell am start -W -n dev.jev.wechatmood/.MainActivity`：Status: ok。
- `adb shell dumpsys package dev.jev.wechatmood`：versionCode=2、versionName=0.2.0。
- `powershell -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon`：BUILD SUCCESSFUL，静态检查无阻断错误，保留原生资源、固定中文文案等警告；导出的设置接口通过 UID 白名单控制，静态检查不能识别该运行时校验。
- `adb shell content call --uri content://dev.jev.wechatmood.settings --method config`：非白名单 shell UID 被拒绝，返回 `SecurityException: Caller is not allowed`。Lint 报告为 0 errors、42 warnings。
- APK 内容核验：存在 KSP 生成的 Xposed 入口，个人密钥确已写入 DEX；仅输出布尔结果。手机已安装 APK 的 SHA-256 与本机构建产物一致。

## 验证边界

- 模型真实请求在电脑侧验证，不是手机网络连通性证明；手机首页提供检测按钮。
- 启动命令成功不等于页面已完成视觉验收。检查时手机前台已经是微信，未继续操作聊天或代发消息。
- 没有在用户聊天里验证模块启用、方向识别、消息捕获和提示卡回收。
- 只修改并提交当前 Android 项目；未修改 Bond_Y_agent 或 jev_demo；没有推送、部署或启动常驻项目服务。
- 既有 AGP 8.5.2 对 compileSdk 35 提示兼容性警告，SDK XML 版本及 YukiHookAPI 生成代码也有既有警告。未为本次迭代升级构建工具链。

# 2026-09-24 会话级分析开关

- 联系人和群聊默认关闭；右上角「分析」同时控制请求与显示，开启/关闭在微信本地记忆，重启后恢复。APP 页移除两个全局开关，旧全局值不再参与判断。
- 单元测试覆盖联系人/群聊隔离、关闭后重载、存储失败不改变已确认状态、未知会话拒绝保存、切换期间标识冲突及页面引导。已有两轮分析取消与配置同步回归继续通过。
- 验证：`tools/gradle.ps1 :app:clean :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-build-cache '-Pkotlin.incremental=false' --no-daemon` 成功；112 项单元测试通过，Debug APK 已生成，Lint 通过（仍有警告）。
- 初次增量测试混入旧版本号常量，禁用构建缓存并完整重编译后消失，未修改版本号断言。
- 未安装、未启动微信。手机验收：开启联系人 A，进入联系人 B 和群聊应保持关闭；返回 A 及重启微信后 A 仍开启；关闭 A 后重进仍关闭，卡片消失且不再发送新请求。已发出的请求可能继续返回。
- 空聊天的标识读取依赖微信版本；无法确认当前会话时开关禁用，不继承上一聊天的状态。此适配边界交由用户实机验收。
