# 微信情绪助手

个人使用的 Android LSPosed / Xposed 模块，当前版本 **0.3.0**。

对微信当前屏幕中**对方的纯文本消息**调用 Jev，提供情绪概率、意图、沟通风险和建议。模型判断仅供参考，不发送消息、不自动回复。

## 安装使用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，先打开一次「微信情绪助手」，建立微信读取设置的授权。
2. 个人 APK 已内置 Jev 地址、模型和密钥，无需填写。
3. 在 LSPosed 启用本模块，作用域选择微信，**彻底结束微信后重新打开**；升级 APK 也需要重启微信进程。
4. 注入成功后，微信首次进入前台会提示「微信情绪助手已加载」。微信「我 → 设置」底部增加「微信情绪助手」入口，可打开助手设置。
5. 聊天底部显示 Jev 状态条：分析中、完成、无可分析文字、连接失败或未适配。点击查看当前屏幕各条文字的分析结果。滚动后自动更新，退出当前页面后清除页面结果。
6. 独立 APP 的「检测模型连接」只发送内置示例；连接成功不代表微信模块已加载。

## 分析范围

- 只接受微信消息对象 `field_type == 1`、`field_isSend == 0` 的非空文字。无法确认类型或会话的行跳过，不再按最长 TextView 猜正文。
- 语音（包括转写文字）、文件名、图片、视频、表情包、链接卡片、引用卡片和系统消息全部跳过。
- 仅观察屏幕上可见的消息行；不扫描消息数据库或整段历史。尚未开始的排队请求在消息滑出屏幕后跳过，已发送的请求可能正常返回，但不会混进当前页结果。
- 当前仍逐条分析，不是整段多轮对话分析。建议使用固定选项，不是任意自由生成的回复。
- 结果入口独立占据底部空间，不覆盖原消息，不替换微信消息行或修改其 ViewHolder；当前版本不在每个气泡下插卡。

## 构建

```powershell
powershell -File tools/import-jev-config.ps1
powershell -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
```

导入脚本只读取 `D:\code_file\jev_demo\.env` 的 Jev 配置，写入 Git 忽略的 `jev.local.properties`，不修改 jev_demo。
个人 APK 包含密钥，不应公开分发；源码、日志和界面不输出密钥。

## 实现与排查

- 使用直接 `IXposedHookLoadPackage` 入口，`app/src/main/assets/xposed_init` 纳入 Git，不依赖生成入口的 `initZygote` 前置状态。
- 同时监听 Application.attach 和前台 Activity，入口写入 LSPosed 日志，初始化后回传独立 APP。
- 旧 ListView 读取可见行的数据对象；新版通过 DexKit 定位 `MvvmChattingItem` 的绑定方法并读取真实消息对象。新版绑定特征和消息字段核对了 [WeKit 的消息 View 监听](https://github.com/Ujhhgtg/WeKit/blob/master/app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt)及其消息模型。
- SettingsProvider 只接受本应用和微信 UID，接口不暴露密钥或聊天内容。首次打开 APP 时向微信授予此 URI 的读取权限，处理 Android 11+ 的包可见性；[Android 官方说明](https://developer.android.com/training/package-visibility/automatic)。
- 后台最多两条模型请求，失败显示原因，当前可见消息 30 秒后可重试。聊天原文和结果不持久化。
- 页面检测只在微信 Activity 前台期间运行；离开时停止回调并撤下状态条。

```powershell
adb logcat -s WeChatMood
```

如果没有「已加载」提示，也没有设置入口，先查看 LSPosed 是否出现 `WeChatMood 0.3.0: entered WeChat main process`。没有这条日志说明尚无进入模块入口的证据，不能靠检测模型连接判断注入是否成功。

手机当前微信版本为 8.0.71。实机注入、设置入口、键盘与滚动效果仍由用户验收；构建和单元测试不能证明这些场景已适配。验证记录见 [docs/VERIFY.md](docs/VERIFY.md)。
