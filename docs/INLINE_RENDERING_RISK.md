# 聊天气泡内绘制：可行性与风险评估

日期：2026-09-23。用户明确要求先评估风险：每条对方文字气泡下直接显示 Jev 分析，聊天标题栏右上角提供绘制开关；底部面板不是最终需求。

## 判断

技术上可以实现，不能承诺为账号低风险功能。风险说明后，用户已明确选择继续实现；不把对 UI 的修改当成已验证可安全长期使用。

- **账号风险：概率未知、后果可能较重。** 微信官方协议 8.2.1.4–8.2.1.7 限制未经授权的插件接入、对运行数据和客户端的读取或干预；8.5.1 的处理包括功能限制、账号封禁等。不能由“只读、不发消息”推导出不会封号。现有 LSPosed 版本同样存在此风险，绘制开关不能消除已注入模块的风险。没有可靠材料可给出具体封禁概率，也不能声称一定是高概率。
- **稳定性风险：中等，属于工程判断。** 微信版本、消息行布局、行高重算、列表回收和异步返回都会影响气泡附加卡。可能出现错位、遮挡、结果绑定到回收后的错误行或闪退。WeKit 的源码可参考，但不能证明本项目已适配。
- **隐私风险：聊天文字会发送至外部 Jev 服务。** 仅处理纯文本不等于不包含敏感内容；绘制与是否发送模型请求必须分别定义开关语义。

若优先保护日常主账号，不建议继续以 LSPosed 注入方式推进此功能。手动复制选定文字到独立助手，可避开微信进程注入这一类风险，但不会得到原生聊天气泡内自动绘制效果。

## 技术参考与实现边界

核对 WeKit 源码提交 `bdc7f18033d87a2f6307d2caedfdc6502986a401`：

- `features/api/ui/WeChatMessageViewApi.kt`：消息绑定、附着、分离和回收监听。
- `features/items/chat/AntiMessageRecall.kt`：找到语义消息内容容器，在消息行增加提示控件并随布局更新位置；这一小徽标做法不能原样代替多行分析卡，后者需要额外行高。
- `features/items/chat/FloatingChatHeader.kt`：明确处理标题栏重挂的 LayoutParams 崩溃问题，不能简单把“右上角看起来空着”当成可任意添加控件的保证。

如未来继续实现，需保留微信原消息行及 ViewHolder 身份，只对确认兼容的纯文本行附加独立子 View；按消息身份更新和回收；关闭绘制时删除附加控件并恢复原布局；未知布局停止绘制。右上角控件应挂在确认的工具栏容器中，不能用固定屏幕坐标遮挡微信菜单。以上仅是降低稳定性风险的边界，不解决账号风险。

## 本次实机证据与处理

- 手机仍安装 0.3.0。LSP 界面截图确认模块开关开启，微信作用域勾选。
- 随后实机聊天截图出现“Jev · 本屏 2 条已分析 · 点击查看”。这是旧版链路当时运行的证据，未能确定早先无显示的具体原因。独立 APP 回传文件和常规 Logcat 仍未提供相应记录，不能据此宣称已定位加载故障。
- 用户明确要求改为气泡内绘制并先评估风险后，停止安装本轮底部面板改动，撤回该方向的源码修改；工作草稿保存在 Git 忽略的 verification 目录。
- 本轮底部面板草稿的构建曾通过，但此结果不用于证明气泡内绘制功能或安全性。未安装该草稿，不推送，不启动后台项目服务。

## 来源

- [微信软件许可及服务协议](https://weixin.qq.com/cgi-bin/readtemplate?t=weixin_agreement&lang=zh_CN)：2026-09-23 通过官方页面直接读取，核对 8.2 与 8.5；网页搜索工具打开失败后，用本机 HTTP 请求成功获取。
- [WeKit 消息 View 生命周期](https://github.com/Ujhhgtg/WeKit/blob/bdc7f18033d87a2f6307d2caedfdc6502986a401/app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt)
- [WeKit 消息行提示控件](https://github.com/Ujhhgtg/WeKit/blob/bdc7f18033d87a2f6307d2caedfdc6502986a401/app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/AntiMessageRecall.kt)
- [WeKit 标题栏布局处理](https://github.com/Ujhhgtg/WeKit/blob/bdc7f18033d87a2f6307d2caedfdc6502986a401/app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/FloatingChatHeader.kt)
