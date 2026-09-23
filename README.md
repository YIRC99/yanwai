# 微信情绪助手

个人使用的 Android LSPosed / Xposed 模块。当前版本 **0.2.0**，项目目录为 `D:\code_file\demo_wechat_jev`。

在微信聊天中，对已显示的对方文字消息调用 Jev，尝试在消息下方显示情绪概率、可能意图、沟通风险和建议。模型判断仅供参考，不发送消息、不自动回复。

## 安装使用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，打开「微信情绪助手」。
2. 当前个人 APK 已从 `D:\code_file\jev_demo\.env` 内置 Jev 地址、模型和密钥，无需填写。
3. 在 LSPosed 管理器启用本模块，作用域选择微信，重新启动微信。
4. 进入聊天查看对方的文字消息。更改分析或显示开关后重新进入聊天。
5. 首页「检测模型连接」只发送一条内置示例，不读取真实聊天。连接成功不代表微信模块已启用。

分析和显示默认开启；升级到此个人版本时开启提示显示，保留原来的分析总开关。以后保留用户的开关选择。

## 构建

本机使用已有 JDK、Android SDK 和 Gradle 启动脚本：

```powershell
powershell -File tools/import-jev-config.ps1
powershell -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

导入脚本只读取 Jev 的 `TYPESAFE_API_KEY` 和 `TYPESAFE_MODEL`，不修改 jev_demo。
密钥复制到 Git 忽略的 `jev.local.properties`，构建时写入 APK 的 BuildConfig。
个人 APK 包含密钥，不应作为公共安装包分发。源码、日志、界面和提交均不输出密钥。

没有本地密钥也可以编译，APP 会明确提示未配置并拒绝请求。

## 当前实现

- 模型使用 TypeSafe 原生 `POST /v1/systemone`，不是 OpenAI 的聊天补全接口。
- 使用 choice 与 noul 问题，严格检查类型、概率范围、概率归一以及建议选项；异常响应不伪装成低风险。
- 建议从固定沟通建议中选择；当前仅分析单条消息，不是完整多轮对话理解，也不是任意自由生成的回复。
- 设置页采用原生工具栏和系统边距，适配浅深色，移除模型输入表单，折叠调试信息。
- SettingsProvider 只向本应用和微信 UID 提供开关，接收有限长度状态。不返回密钥或聊天记录，不放宽配置文件权限。
- 微信主进程通过 Application.attach 安装钩子，在聊天调用链实际设置 ListView Adapter 时定位 getView。
- 每行独立承载提示卡，回收时移除旧卡并中止过期轮询；不往 ListView 本身插入子 View。
- 根据头像位置识别发送方；无法确认时跳过，不分析方向不明的行。
- 请求在后台执行，同时最多两条，失败后同一消息冷却 30 秒。缓存仅存在微信进程内。
- Xposed 入口由 KSP 生成；模块作用域通过 Manifest 的 xposedscope 和资源数组声明。当前使用传统 Xposed 入口，未声称实现现代 Module API。

## 当前验证范围

2026-09-23：Jev 真实接口调用成功；19 项单元测试通过；APK 已覆盖安装，手机包信息显示 0.2.0，启动命令成功。
完整静态检查和具体命令见 [docs/VERIFY.md](docs/VERIFY.md)。

**未验证**：当前手机微信版本中的实际注入、聊天列表捕获、提示卡布局和滚动回收表现。由用户启用模块后体验。
参考图中按整段对话生成不同问题和自由文案的效果，当前版本尚未实现。

## 排查

首页显示的是微信通过跨进程接口回传的最近记录，不把 APP 自己的空缓存当作微信状态。
「尚未收到微信模块的运行记录」不等于手机没有安装框架。先检查 LSPosed 作用域并重启微信。

```powershell
adb logcat -s WeChatMood
```

启用「记录微信适配信息」后重启微信，可收集适配日志。设置页中的检测日志属于本 APP，微信详细日志使用以上命令查看。

微信版本可能改变布局或调用链；未识别为聊天的列表、RecyclerView、自绘正文、图片和语音不在当前已实现的支持范围内。不得把构建成功当作这些场景已适配。
