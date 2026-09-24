# GitHub Release 更新提醒

从 1.1.2 开始，言外直接读取公开仓库的 GitHub Release，不需要自己的服务器，也不需要给用户配置 GitHub Token。

## 用户看到的行为

- 打开言外设置页时检查新版，成功检查后 24 小时内复用记录。页面有“检查更新”和“GitHub 发布页”按钮。
- 正式版版本号比当前版本更高，且 Release 中已有上传完成的非空 APK，才提示可下载更新。同一版本自动弹窗一次，关闭后仍可从页面进入下载。
- 点击“去 GitHub 下载”打开本项目的对应 Release 页面，由用户下载 APK 并覆盖安装。应用不代下载、不请求安装权限。
- 网络失败不会影响聊天分析，保留已有版本记录并显示上次成功检查时间。失败后自动检查至少间隔 15 分钟，手动检查至少间隔 1 分钟；遇到 GitHub 限流还遵守返回的等待时间。
- 仅设置页前台触发检查，没有后台定时器、通知权限或常驻服务，不在微信聊天扫描中访问 GitHub。请求不带模型 Key、聊天内容或 GitHub Token。
- 旧版应用没有这段功能，需要先手动升级至 1.1.2 或更高版本，之后才会提醒后续发布。

## 以后怎样发布

1. 修改 `app/build.gradle.kts`，递增 `versionCode` 和 `versionName`，例如 14 / `1.1.3`。
2. 构建 APK，沿用之前公开安装包的签名。Android 覆盖安装需要包名及签名兼容，并使用更高的 versionCode；不要把未签名 Release 产物直接上传给用户。
3. 在 [GitHub Releases](https://github.com/YIRC99/yanwai/releases) 创建正式 Release，tag 使用 `1.1.3` 或 `v1.1.3`，与 APK 的 versionName 对齐，附上 APK 和更新说明。
4. 发布为正式版并设为 Latest。不要勾选 Pre-release；草稿、测试版、只有 Git tag、只有 push 提交均不会产生正式更新提醒。

本实现仅支持三段数字正式版本号（可带 `v` 或 `V` 前缀），按数字比较，`1.10.0` 高于 `1.9.9`。不支持 `latest`、中文 tag 或带 beta 后缀的正式发布 tag。不要把旧版本重新标记为 Latest。

## 实现与验证边界

- 固定接口：`GET https://api.github.com/repos/YIRC99/yanwai/releases/latest`，匿名请求，带 User-Agent、API 版本和 Accept；成功后缓存 ETag，后续使用 If-None-Match。
- 对外跳转 URL 由固定仓库地址和校验后的 tag 构造，不使用响应中的任意跳转链接。HTTP 检查限制超时和响应大小，不跟随重定向。
- 2026-09-24 实际匿名请求返回 HTTP 200、正式 tag `1.0.0`、附件 `yanwai1_0_0.apk`。这只证明当前接口可读；高版本提醒、缓存 304、失败和限流使用本地 HTTP 测试服务验证，不能当作已经发布未来版本或完成手机弹窗验收。

官方依据：[Release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)、[匿名 API 限流](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)。公开资源允许匿名读取；共享出口 IP 的请求会共用匿名限额，用户仍可点击发布页自行查看。
