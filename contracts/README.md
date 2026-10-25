# Public contract validation

The checked-in REST, event and connector contracts are release inputs. Validate
the OpenAPI 3.1 document and the JSON Schema 2020-12 event envelope with the
locked Node toolchain:

```sh
cd contracts
npm ci --ignore-scripts
npm run validate
npm audit --audit-level=high
```

The CI contract job additionally downloads `protoc 35.0`, verifies the upstream
archive against its pinned SHA-256 checksum, and compiles
`connector/v1/connector.proto` to a descriptor set. A local `protoc` installation
can run the equivalent syntax/import check:

```sh
protoc --proto_path=contracts/connector \
  --descriptor_set_out=connector.pb \
  contracts/connector/v1/connector.proto
```

Generated descriptors and `node_modules` are build outputs and are not committed.

## 分层与血缘契约

`workspace/lineage-v1.schema.json` 固定元数据图、字段分页与来源声明的类型；
`openapi/workspace-lineage-v1.yaml` 是新路径的唯一来源，并由工作区 OpenAPI 聚合。
`npm run validate` 会同时检查它们，包括节点类型与层级/形状是否一致、来源声明
是否被冒充已生成数据、线型与依据性质是否矛盾，以及图是否悬空或遗漏覆盖说明。
这些检查不替代服务端的真实租户与授权校验。

新图最多 3,000 节点、6,000 边，默认 256 字段；字段按表分页，每页最多 128。
覆盖分母是本轮可见、已盘点的元数据，不代表企业全量数据或原始记录覆盖率。
映射声明不执行 SQL，指标引用业务约定的现有定义版本，不另造第二套指标权威。

可把实际接口响应保存到私有文件，再直接用同一套消费方检查核对：

```powershell
node scripts/validate-lineage.mjs --response ..\.local\lineage-response.json Graph
node scripts/validate-lineage.mjs --response ..\.local\field-page.json FieldPage
```

## 本地工作区的真实 HTTP 冒烟

`openapi/workspace-v1.yaml` 与 `workspace/workspace.schema.json` 覆盖新增的来源、
盘点、规则发现、助手、协作任务及版本化业务约定。`npm run validate` 编译所有定义，
并用正常请求、凭据泄漏、缺失证据、伪造助手方法、跨租户字段、伪造口径确认和
冒充已计算指标等正反样例检查契约。

HTTP 演练需要一个**专门用于测试、初始为空**的本地工作区。先启动 Runtime：

```powershell
.\gradlew.bat :runtime-api:bootRun --args="--spring.profiles.active=local --server.address=127.0.0.1 --server.port=18080 --smartdata.workspace.storage-dir=.local/workspace-smoke"
```

另开终端：

```powershell
Set-Location contracts
$env:SMARTDATA_WORKSPACE_SMOKE_URL = 'http://127.0.0.1:18080'
npm run smoke:workspace
```

脚本会拒绝已有来源或任务的工作区，不会清空用户数据。它通过真实 HTTP 依次完成：
上传两条合成订单、按幂等键重放、只读盘点、发现重复键、分配规则助手、提问与评论、
提交任务、拒绝“原证据自证”、上传更正快照、重新盘点、由另一**本地演示身份**核验，
再检查业务目标是否绑定真实任务、口径确认权限、定义变化后的重新审核、空范围
暂停与历史保留，最后检查报告。每个成功响应都直接按公开 JSON Schema 验证，
能发现接口与实现漂移。

这只证明完整小快照上的本地链路，不能证明真实双人认证、生产数据库接入或企业收益。
脚本不删除自己创建的合成记录；测试目录由运行者按保留策略处理。

这项冒烟保持默认的 `DETERMINISTIC_RULES`，不会读取 API Key 或向模型服务发送数据。
可选模型消息用 `SPRING_AI_EVIDENCE_V1` 标记；必须明确 `SUCCEEDED/FAILED`、不确定性、
证据引用和模型版本追踪。模型失败不能冒充成功，HTTP 200 也不能作为模型成功的判断。
契约正常与反例覆盖这些字段；真实供应商联调仍需单独授权端点与脱敏测试数据。

停止并重启同一存储目录的 Runtime 后，可用下面的只读检查确认任务、讨论、消息和
历史证据、业务约定及历史确认仍然存在。CI 会自动运行这一步，验证范围包含实际进程重启：

```powershell
npm run smoke:workspace -- --verify-restored
```
