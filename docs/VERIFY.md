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

