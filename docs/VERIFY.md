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
