# 微信情绪助手

个人使用的 Android LSPosed / Xposed 模块，当前版本 **0.4.0**。

对微信当前屏幕中**对方的纯文本消息**调用 Jev，提供情绪概率、意图、沟通风险和建议。模型判断仅供参考，不发送消息、不自动回复。

## 安装使用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`，先打开一次「微信情绪助手」，建立微信读取设置的授权。
2. 个人 APK 已内置 Jev 地址、模型和密钥，无需填写。
3. 在 LSPosed 启用本模块，作用域选择微信，**彻底结束微信后重新打开**；升级 APK 也需要重启微信进程。
4. 注入成功后，微信首次进入前台会提示「微信情绪助手已加载」。微信「我 → 设置」底部增加「微信情绪助手」入口，可打开助手设置。
5. 对方纯文本气泡下直接显示 Jev 分析卡。聊天右上角「绘制」开关控制显示，长按打开状态、分析本屏和助手设置。关闭绘制只隐藏结果；关闭助手里的「启用分析」才停止新请求。
6. 独立 APP 的「检测模型连接」只发送内置示例；连接成功不代表微信模块已加载。

## 分析范围

- 只接受微信消息对象 `field_type == 1`、`field_isSend == 0` 的非空文字。无法确认类型或会话的行跳过，不再按最长 TextView 猜正文。
- 语音（包括转写文字）、文件名、图片、视频、表情包、链接卡片、引用卡片和系统消息全部跳过。
- 仅观察屏幕上可见的消息行；不扫描消息数据库或整段历史。尚未开始的排队请求在消息滑出屏幕后跳过，已发送的请求可能正常返回，但不会混进当前页结果。
- 当前仍逐条分析，不是整段多轮对话分析。建议使用固定选项，不是任意自由生成的回复。
- 保留微信原消息行及 ViewHolder，在已识别的文字气泡下追加独立分析卡，列表回收前移除；未知布局跳过并报告。

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
- 页面检测只在微信 Activity 前台期间运行；离开时停止回调并撤下开关与分析卡。

```powershell
adb logcat -s WeChatMood
```

如果没有「已加载」提示，也没有设置入口，先查看 LSPosed 是否出现 `WeChatMood 0.4.0: entered WeChat main process`。没有这条日志说明尚无进入模块入口的证据，不能靠检测模型连接判断注入是否成功。

手机当前微信版本为 8.0.71。已实机确认标题栏开关、气泡下方分析卡、关闭绘制恢复布局；更多聊天类型、键盘与长时间滚动效果仍需用户验收。验证记录见 [docs/VERIFY.md](docs/VERIFY.md)。
