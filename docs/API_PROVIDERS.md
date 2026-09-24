# 选择渠道与申请 API Key

打开言外，选择「模型渠道」，按页面步骤申请 Key，再粘贴到输入框，点击「保存并检测连接」。预设渠道不需要填写模型参数。各渠道独立收费，Key 不能混用。

## OpenRouter

1. 打开 [Keys 页面](https://openrouter.ai/settings/keys)，注册或登录，点击 Create Key。
2. 确认账户有可用额度；无需申请 TypeSafe 账号或 Key。
3. 言外选择 OpenRouter，粘贴 Key 并检测。

- [官方 Jev 说明](https://openrouter.ai/docs/guides/community/jev)
- [当前采用的 TypeSafe 兼容接口](https://openrouter.ai/docs/guides/community/typesafe-sdk)
- POST `https://openrouter.ai/api/v1/systemone`，模型 `typesafe/jev-1.13`。
- OpenRouter 另有 `/api/alpha/decisions`，本应用预设使用前述兼容路径，不使用 `/chat/completions`。

## Vercel AI Gateway

1. 打开 [AI Gateway API Keys](https://vercel.com/d?title=AI+Gateway+API+Keys&to=%2F%5Bteam%5D%2F~%2Fai-gateway%2Fapi-keys)，注册或登录并选择团队，点击 Create key。
2. 创建 **AI Gateway API Key**，不是普通 Vercel Access Token。按平台提示绑定信用卡并确认额度；拥有 Key 不代表账户验证已完成。
3. 言外选择 Vercel AI Gateway，粘贴 Key 并检测。

- [官方认证说明](https://vercel.com/docs/ai-gateway/authentication-and-byok)
- [当前采用的 TypeSafe 兼容接口](https://vercel.com/docs/ai-gateway/sdks-and-apis/typesafe)
- POST `https://ai-gateway.vercel.sh/typesafe/v1/systemone`，模型 `typesafe-ai/jev`。
- 原生 `/v1/evaluate` 与兼容路径的命名存在差异；本应用保留 Jev `criteria`、`probabilities`、`confidence` 的原生格式。
- 不将限时免费活动写成永久承诺，以账户控制台计费为准。

## Jev 官方 · TypeSafe

1. 打开 [TypeSafe 控制台](https://console.typesafe.ai/)，注册或登录。
2. 在控制台申请 API Key；如果账号仍待开通，可以先选择其他渠道。
3. 言外选择 Jev 官方，粘贴 Key 并检测。

- [官方快速开始](https://docs.typesafe.ai/introduction/quickstart)
- POST `https://api.typesafe.ai/v1/systemone`，模型 `jev-1.13.0`。

## 自定义 Jev 兼容服务

向服务商获取 Key、完整 HTTP(S) 接口地址与模型名。选择自定义后填写；接口必须接收 `model/state/questions` 并返回 `answers` 下完整的选择、概率和置信度。只声明 OpenAI 兼容不足以证明支持 Jev。

Cloudflare 已有 [Jev 模型与调用文档](https://developers.cloudflare.com/ai/models/typesafe/jev/)，注册入口为 [Cloudflare 控制台](https://dash.cloudflare.com/)，需要 Account ID 和 API Token。**1.1.0 尚未适配 Cloudflare 的请求封装，不在可用预设中，也不能直接填地址使用。**

## 验证边界与复测

2026-09-24：OpenRouter 通过三组中文样例的两轮真实分析（六次请求），所有请求的完整答案均通过应用现有解析校验。Vercel 返回 HTTP 403、`customer_verification_required`，应用会显示绑卡验证指引，未把这一响应算作推理成功。TypeSafe 保留原协议，本轮没有新增官方 Key 实测。

开发者可在进程环境中配置 `OPENROUTER_API_KEY`、`AI_GATEWAY_API_KEY` 后显式运行以下命令，复用应用实际的传输、请求构造、两轮调度和概率解析。测试可能计费，仅使用代码内置的虚构聊天；普通构建不运行网络测试。Key 不写入任何源码或构建参数。

```powershell
& ./tools/gradle.ps1 -GradleArgs @(':app:testDebugUnitTest', '-PjevLiveTest=true', '--no-daemon', '--no-build-cache')
```

Vercel 的上述账户限制会明确记录为 BLOCKED / skipped，其他调用失败仍让验证失败。
