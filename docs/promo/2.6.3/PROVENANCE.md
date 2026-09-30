# 素材来源与交付记录

- 日期：2026-09-30（Asia/Shanghai）。
- 功能基线：`66858e6`，2.6.2 / 56。本次按每提交升级版本的仓库规则，升至 2.6.3 / 57。
- 2.6.3 不修改应用功能逻辑；新增宣传文案、实机图片、素材生成工具，并纠正 README 中与当前功能不一致的旧说明。
- 设备：已连接的 Android 实机；微信 8.0.71；覆盖安装 2.6.3 后重启微信，再获取微信内素材。

## 原始截图

`docs/images/2.6.3/` 中的 PNG 均由手机原生截图直接取得，没有生成或重绘界面。

| 文件 | 页面 | 内容边界 |
| --- | --- | --- |
| home.png | 言外首页 | 首页入口、历史运行记录 |
| analysis.png | 聊天分析 | 实际已保存的 JEV 情绪 + LLM 意图配置，不展示密钥 |
| reply.png | 回复与话题 | 模型名称、角色管理入口；未展示已存角色详情 |
| role-editor.png | 添加角色表单 | 空白表单，随后取消，未保存 |
| chat-entry.png | 微信「＋」面板 | 微信团队系统欢迎消息、模块横条 |
| reply-drawer.png | 帮我回抽屉 | 实际读取 1 条系统欢迎消息，未生成回复 |
| save-identity.png | 自定义身份表单 | 空白身份、背景、本次要求与保存按钮，未保存 |

没有使用私人聊天、联系人详情、API Key、二维码或模型实际输出作为宣传素材；没有向联系人发送消息。

## 图片与视频

- 六张 1080×1920 宣传图：真实截图与独立标题排版，原始截图另附。
- 视频：六段截图编排、轻微镜头运动及淡入淡出，并非连续手机操作录屏。
- 旁白：Windows 本地 Microsoft Huihui Desktop 合成，非作者真人录音；无背景音乐。
- 字幕：画面内为每段要点，另附旁白 SRT（按句长度近似分配时间，不是逐字强制对齐）。
- 模型能力说明来自当前源码和近期更新记录。画面展示入口与表单，不宣称真实模型效果已经验证。

## 验证

- `:app:testDebugUnitTest :app:assembleDebug --offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process` 成功。
- 60 个测试类，356 项通过，0 failures / errors / skipped。
- 新 APK 已在连接手机覆盖安装成功，应用页与微信抽屉均显示 2.6.3。
- 构建仍有已有的 SDK/AGP 兼容及弃用提示；未影响本次测试和打包。
- 交付文件的包信息、签名、媒体时长/轨道及 SHA-256 由本次命令核对，结果随交付包的 `交付说明.txt` 和 `SHA256SUMS.txt` 保存。

## 重新生成

在仓库根目录使用 Node、Playwright CLI、FFmpeg、Windows System.Speech：

1. `node tools/promo/prepare.mjs 2.6.3` 准备 HTML、文案和图片。
2. `powershell -File tools/promo/narrate.ps1 -Version 2.6.3` 生成本地旁白。
3. 临时通过本机静态服务器打开 `output/yanwai-2.6.3/cards.html`，用 Playwright CLI 获取 snapshot，再执行 `run-code --filename tools/promo/render.js`。
4. `node tools/promo/video.mjs 2.6.3` 合成视频与 SRT。
5. 复制对应 APK，重新生成校验清单；检查最终画面，关闭本次浏览器与静态服务器。

`output/` 是本地交付目录，不纳入 Git；原始截图、文案、分镜和生成工具纳入本地提交。未推送、未发布。
