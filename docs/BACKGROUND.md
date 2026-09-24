# 划掉言外后继续使用（1.1.1）

## 问题与处理

用户反馈的是从最近任务划掉「言外」，随后微信内分析失效。代码检查发现：旧版每秒读取一次设置 Provider，任何调用失败都会清空已经加载的配置，使分析停止。这是已确认的代码缺陷；尚未在该手机上抓到划卡瞬间的系统日志，不能断言具体是哪项厂商后台策略造成了调用失败。

1.1.1 在微信进程内保留最近一次可信设置。Provider 暂时不可用时继续使用该设置；第一次加载失败仍保持停用，不凭空生成 Key 或开启分析。设置连接恢复后自动更新。

保存开关、Key 或渠道后，言外会向运行中的微信发送完整设置。广播限定微信包名，只接受拥有言外签名权限的发送者；Provider 原有 UID 白名单保留。设置带单调递增版本，延迟到达的旧广播不能覆盖较新的关闭状态或恢复已清空的 Key。配置只缓存于内存，不另写入微信文件或备份。未新增前台服务、通知、开机广播或后台轮询项目进程。

清除言外数据会生成新的配置代际，由 Provider 验证后替换旧缓存；收到尚未验证的新代际时先暂停分析，避免继续使用旧 Key。已经淘汰的代际广播不会恢复旧配置。

## WeKit 对照与来源

对照的是 `Ujhhgtg/WeKit` 提交 `bdc7f18033d87a2f6307d2caedfdc6502986a401`，仅阅读源码，没有复制其实现：

- [NativeLoader.kt](https://github.com/Ujhhgtg/WeKit/blob/bdc7f18033d87a2f6307d2caedfdc6502986a401/app/src/main/java/dev/ujhhgtg/wekit/loader/utils/NativeLoader.kt)：用宿主 Context、宿主 filesDir 初始化 MMKV，功能配置由宿主侧读取。
- [MainActivity.kt](https://github.com/Ujhhgtg/WeKit/blob/bdc7f18033d87a2f6307d2caedfdc6502986a401/app/src/main/java/dev/ujhhgtg/wekit/activity/MainActivity.kt)：启动微信离开页面后会主动 `finishAndRemoveTask()`。无需让独立设置页留在最近任务。
- [AndroidManifest.xml](https://github.com/Ujhhgtg/WeKit/blob/bdc7f18033d87a2f6307d2caedfdc6502986a401/app/src/main/AndroidManifest.xml)：没有常规前台保活服务或开机拉起声明。
- [Android 广播权限文档](https://developer.android.com/develop/background-work/background-tasks/broadcasts)：动态注册接收器时指定权限，用于限制广播发送者；跨应用接收器需要显式导出。

此次借鉴“宿主已加载的功能不依赖独立设置页存活”的方向；没有移植 WeKit 的整个宿主设置页面或 MMKV 存储方案。

## 边界与手机验收

本次解决已加载配置的微信会话。微信本身被杀死、手机重启后，内存配置消失，仍需要首次成功读取 Provider；若系统阻止访问，打开一次言外恢复同步。系统设置中的“强行停止”、禁止自启动与从最近任务划卡也不保证具有相同效果。应用更新后先打开一次言外，再彻底重启微信以加载新版代码。

手机验收交由用户完成：

2026-09-24 已覆盖安装 1.1.1，打开言外并重启主微信后，用户针对划卡继续使用场景反馈“可以了”。其余边界和不同机型仍按以下步骤验证。

1. 确认微信内能够分析，划掉最近任务中的言外，再回微信查看一条尚未分析的对方纯文本；不要只看旧缓存卡片。
2. 打开言外关闭“启用分析”，划掉言外，再看新文字，确认不再发起分析。清空 Key 后也应停止。
3. 重新启用并保存有效配置，回微信应恢复。另行重启微信，确认首次设置读取是否受手机后台策略限制。

自动化测试只验证配置状态转换与旧消息顺序，不等同于厂商划卡行为、广播权限和 LSPosed 的实机验收。
