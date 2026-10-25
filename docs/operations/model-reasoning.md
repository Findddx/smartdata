# 为证据助手接入真实模型

默认助手使用本地检查规则整理已有发现，不会联网调用模型。现在可由服务端运维人员
显式启用 Spring AI 模型适配器，让助手解释已获授权的问题摘要。三种职责仍是运维、
分析和决策；它们共用受控的解释接口，不是能自行调用企业系统的自治团队。

规则负责找出重复、缺值、过期等可复现的问题。模型负责解释这些问题、列出人工
核对步骤和局限。模型不能创建来源、查询任意数据、执行 SQL、修改任务状态、
修改来源系统或认定修复成功。任务核验仍由独立规则检查更晚的新观测。

## 数据到底会发到哪里

每次最多选五项属于该助手的问题。出站内容只有用户问题、职责、问题摘要、
规则要求、建议、证据 ID、内容哈希、观测时间和样本范围。原始记录、SQL、
来源端点、来源凭据、租户 ID 和完整工作区都不进入模型提示词。
来源必须仍处于盘点成功状态，且问题来自其最新成功盘点。已断开、配置已改变但
尚未重扫、最近盘点失败的来源只保留历史记录，不进入新请求的模型上下文。
撤销授权不能追回先前已经发出的摘要；进行中的请求最多在本次时限内结束。

用户问题也会发送，因此请在提问中使用业务描述，不粘贴客户名单、口令或原始表。
服务端要求同时批准租户和精确来源 ID，不接受通配符。默认 `export-approved`
为 `false`。本版本没有声称已接通 OPA 数据分类审批或企业密钥管理；机密摘要应
使用企业私有模型，并由数据责任人批准其实际网络位置和授权范围。

模型端点与密钥引用只能配置在服务端，工作台没有修改它们的接口。密钥正文只从
服务进程的 `SMARTDATA_MODEL_` 环境变量读取，不写入工作区文件、API 返回或追踪。
模型连接不会注册工具。即使提示词被恶意数据干扰，返回的文字也不会变成执行命令。

## 本机配置示例

先按 [工作区快速开始](workspace-quickstart.md) 接入来源、盘点并分配助手。在
工作台或以下只读请求中找到来源 ID；本地演示的租户固定为 `demo-cn-001`：

```powershell
$view = Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/workspace' -Headers @{'X-Demo-Actor'='admin'}
$view.sources | Select-Object id, name, provenance
```

停止本机服务，在不会提交到 Git 的 `.local/model.yml` 中保存配置。下面的来源
ID 和模型标签需要换成实际值；`model-version` 是操作者冻结的部署标签，便于
追查与复测，并不能证明供应商始终使用同一组模型权重。

```yaml
smartdata:
  workspace:
    reasoning:
      mode: SPRING_AI_EVIDENCE_V1
      profile: PRIVATE_COMPATIBLE
      endpoint: http://127.0.0.1:8000/v1
      model-id: your-served-model-name
      model-version: private-deployment-2026-10-25
      credential-env-ref: SMARTDATA_MODEL_API_KEY
      export-approved: true
      allowed-tenants: [demo-cn-001]
      allowed-source-ids: [source-replace-with-actual-id]
      timeout-seconds: 20
      max-input-bytes: 16384
      max-output-tokens: 1024
      max-calls-per-day: 100
      max-reserved-tokens-per-day: 200000
```

私有 vLLM/OpenAI-compatible 服务必须接受 Bearer 凭据，且支持 Chat Completions、
`response_format: json_schema` 和 `tool_choice: none`。不兼容时会明确失败，不会
悄悄改成无结构文本、改变协议或换供应商。

在同一 PowerShell 终端设置服务进程环境，然后启动：

```powershell
$env:SMARTDATA_MODEL_API_KEY = '<填写自己的模型服务密钥>'
$env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'optional:file:./.local/model.yml'
.\scripts\start-local.ps1
```

密钥只在自己的私有终端中设置，不要放进示例文件或截图。更新或撤销授权后，
重启服务使新配置生效。要回到本地规则，把 `mode` 改为 `RULES` 后重启。

## 火山与百炼配置档

适配器统一走 OpenAI-compatible Chat Completions。配置档标记用于来源追踪和
网络边界；不会自动挑选模型、开通服务或宣称已经验证某个云模型。

| 配置档 | 基地址示例 | 模型 ID 从哪里取 |
|---|---|---|
| `PRIVATE_COMPATIBLE` | `https://models.example.internal/v1` 或本机 `http://127.0.0.1:8000/v1` | 私有服务公布的模型名称 |
| `VOLCENGINE` | `https://ark.cn-beijing.volces.com/api/v3` | 火山方舟中有权调用的模型或接入点 ID |
| `BAILIAN` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 百炼同地域中可用且支持所需结构输出的模型 ID |

不要在基地址末尾再加 `/chat/completions`，客户端会补上。端点不能包含密码、
查询参数或片段。火山示例来自 [官方端点与认证文档](https://docs.volcengine.com/docs/ark/base-url-and-authentication?lang=en)。
百炼需使用与账户和地域相符的地址；官方目前推荐工作空间专属域名，示例中的
共享域名仍可用。以 [官方 Base URL 文档](https://www.alibabacloud.com/help/en/model-studio/base-url)
和账户控制台为准。本文没有选定或认证具体云模型。

## 失败、额度和追踪怎样核对

模型必须返回严格的 `answer / evidenceRefs / uncertainty` 三字段 JSON。未知引用、
空引用、重复 JSON 键、多余字段、无局限说明、过长答案、工具调用和被截断的结果
都被拒绝。引用合法仅证明它引用了本次允许的证据，不能证明解释的业务结论正确。
试点仍需业务专家检查解释质量。

每条消息有 `status`。失败保存为 `FAILED`，保留稳定 `errorCode` 和安全说明，
不会用规则答案覆盖失败。成功模型消息标记 `SPRING_AI_EVIDENCE_V1`；默认规则消息
标记 `DETERMINISTIC_RULES`。追踪只保存配置档、模型 ID、部署标签、提示词版本、
上下文哈希，以及供应商给出的可选回执 ID 和用量；不采集提示词或模型正文到遥测。
本地协作记录本身仍按功能保存用户问题和经过校验的答复。

网络请求的总预算为 1–30 秒，包括 DNS 解析和响应读取；SDK 与底层网络均不重试，
也不跟随重定向。HTTP 明文只允许私有配置档的字面 `127.0.0.1`，DNS 解析到本机
不能放宽 TLS。实际连接固定使用本次核对的地址；禁止元数据常用的链路本地地址、
多播和未指定地址。云配置档进一步禁止私网、本机和 IPv6 私有地址。私有配置档
允许企业私网，仍由服务端固定精确端点并正常校验证书。

出站前必须先在 `.local/workspace/model-budget/YYYY-MM-DD` 持久预留额度，按 UTC
日期、租户记账。重启不清空当天占用，失败请求也不退回。`max-calls-per-day` 限制
次数；用量额度采用“提示词 UTF-8 字节数 + 最大输出 token 数”的保守准入单位，
不是供应商账单或跨模型分词器保证。实际输出受配置的最大 token 数约束。不要删除
当天账本来重置额度；企业部署还需供应商侧预算、并发限流和正式持久化适配器。

输入最多 32 KiB，输出最多 2,048 tokens（模型处理和计费采用的文本单位），
供应商 HTTP 响应正文最多 128 KiB。
存储不可用、凭据缺失、未授权、无证据或超额都会先停下，已有任务不会因此成功。
同一命令成功保存失败记录后，用相同幂等键重放不会再消耗一次模型请求。并发不同
命令仍各自占用额度；若工作区在请求期间变化，旧结果拒绝提交。

## 本轮实际验证范围

定向命令使用真实的 Spring AI 2.0.1 和官方 OpenAI Java SDK，与本机 HTTP 模型桩
（返回受控响应的测试服务）通信：

```powershell
.\gradlew.bat --no-daemon --no-parallel --max-workers=1 :runtime-api:test --tests 'io.smartdata.foundation.runtime.workspace.*'
```

测试覆盖实际请求路径、Bearer 认证、无工具、结构输出、引用拒绝、超时零重试、
重定向拒绝、响应大小、出口授权、密钥缺失、额度失败、重启额度保留和失败记录
幂等恢复。2026-10-25 严格全模块验证通过 87 项测试，其中工作区 43 项、真实
HTTP 模型桩 14 项；失败、错误和跳过均为 0。尚未使用真实火山、百炼或客户
vLLM 密钥验收，因此不能据此宣称具体
云模型的结构输出、时延、成本或业务解释质量已经达标。真实试点需冻结具体模型、
地域、策略和样本，再按相同契约加入小规模的只读解释验收。

Spring AI 2.0.1 使用官方 SDK 的依据见 [对应版本源码](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)。
SDK transport 必须保留 Jackson 2；应用 DTO 和工作区持久化继续使用 Jackson 3，
两者包名不同，不能把 SDK 的 `com.fasterxml.jackson.databind` 强制替换成
`tools.jackson.databind`。依赖锁和校验文件同时记录这两个明确的边界。
