# 聊天加号入口调研（2026-09-26）

## 结论与证据

已有本机微信布局记录 `verification/wechat-view.txt:1280` 显示 ChatFooterBottom 下存在独立的 `com.tencent.mm.pluginsdk.ui.chat.AppPanel`，内部为两层 LinearLayout、MMFlipper 和 MMDotView。这提供了不依赖混淆资源 ID 的结构锚点。记录中的面板处于隐藏状态，不能由此证明展开后的高度与图标布局已适配。

[WeKit 官方使用文档](https://docs.wekit.ujhhgtg.dev/faq) 也说明可以通过长按聊天发送/加号按钮打开扩展操作，但这是长按入口，不能当作原生加号网格新增格子的证明。[Tencent/tinker 的历史问题](https://github.com/Tencent/tinker/issues/505) 包含 AppPanel/AppGrid 类调用信息，仅作为历史存在性参考，不用其中旧混淆方法作为当前 hook。

## 本次实现

在识别到的 AppPanel 内增加一条 48dp 图标入口，包裹原有内容并为其分配剩余高度，保留原生图标数据、分页与监听器。不接管加号按钮本身，不替换照片、视频等功能，不常驻聊天区域。退出时恢复原父子结构和布局参数。没有可靠结构或面板过小时不注入；长按标题「分析」菜单和长按文字保留入口。

## 需要手机确认

展开加号后入口可见；原图标两行及分页无裁剪；照片、拍摄、文件和表情/键盘切换正常。若此版本通过固定高度计算 AppGrid，包裹内容可能仍需针对该布局进一步适配，本轮不宣称已经过实机验证。

GitHub CLI 缺少登录，匿名 API 触发限流，未以搜索结果代替当前版本原生网格的实现证据。本轮不新增依赖混淆列表项的网格劫持。
