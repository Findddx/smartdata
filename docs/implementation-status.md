# 实施状态（2026-09-07）

这是订单履约样板的历史记录。2026-10-25 新增企业数据工作区、模型解释与界面成果
见[本轮阶段报告](reports/2026-10-25-workspace-delivery.md)；完整背景和当前判断以
[项目总纲](../PROJECT_CHARTER.md)为准。下列历史数量与限制保留其原日期。

本仓库当前交付的是可运行、可重复验证的本地纵向切片，以及明确
fail-closed 的生产扩展边界。它不是已经通过真实客户试点或容量验收的
生产系统。

| 里程碑 | 当前状态 | 已交付 | 进入下一门的缺口 |
|---|---|---|---|
| 研究与工程基线 | 完成 | ADR、依赖锁、研究锁、Apache-2.0、CI、CycloneDX SBOM | 为新增依赖持续做许可证与供应链审查 |
| 可信内核 | 本地闭环完成 | 双时态 Claim/Evidence、KnowledgeGap、状态机、租户/ACL、防重放、审计、数据库/RLS/outbox/inbox 模型、Connector SPI | PostgreSQL/jOOQ 仓储、事务 outbox/inbox、身份映射持久化与迁移实测 |
| 只读纵向闭环 | 完成 | 独立 ERP/WMS/TMS/QMS 仿真、32 个模板、500 个确定性案件、类型化建议、证据与冲突视图 | 外部治理的隐藏集和真实历史案件 |
| 审批执行闭环 | 本地闭环完成 | 两项 v1 写能力、申请/审批分离、CAS、执行租约、幂等副作用、回执、独立验证、补偿、人工接管 | OPA/OpenFGA 生产适配、不可变审批与一次性签名 Connector grant、API→Temporal 编排、真实 Connector mTLS |
| 记忆与混合部署 | 本地语义完成 | L0–L3 数据语义、候选审核/撤销、ACL 派生、最小化 MCP DTO | Qdrant 混合检索与删除传播、异步蒸馏、模型路由、目的地感知的数据出口策略 |
| 商业成长级加固 | 基础设施起点 | Compose、Helm、NetworkPolicy、Secret 分离、镜像/SBOM 门、Go race/JVM/前端/契约测试 | Testcontainers 集成矩阵、备份恢复、滚动升级、签名发布、容量/故障注入和渗透测试 |
| 真实试点准备 | 未开始 | 试点边界与指标已写入计划 | 2–3 个真实系统、500 个历史异常、业务负责人、专家审核人与只读/影子期 |

## 已验证

- JVM：44 个测试，0 失败、0 跳过；包含领域编译、并发幂等、执行租约、
  Temporal v2 审批候选验真、补偿验证和跨租户边界。
- MCP Bridge：`go vet` 与 `go test -race` 通过。
- Console：5 个测试通过，TypeScript 与 Vite 生产构建通过。
- OpenAPI 3.1、CloudEvents/JSON Schema 正反样例、Connector Proto、Helm
  strict lint/render、Compose config 均通过。
- 本地 HTTP 冒烟实际完成 `SUPPLIER_DELAY → 建议 → ActionRequest → 审批 →
  WMS 预留 → 独立回读 → 结案 → 记忆发布`，并收到完整 SSE 事件序列。

## 尚未宣称

当前没有声称达到 50 租户、2,000 events/s、1,000 万 Claim、500 万向量、
99.9% 可用性或真实 ROI。Docker daemon 在本机不可用，因此生产依赖容器、
镜像构建和 Testcontainers 语义需要在具备 Docker 的 CI/验收环境继续验证。

生产发布前以 `docs/operations/production-deployment.md` 的门禁为准；缺少任一
持久化、策略、身份、签名授权、mTLS 或独立验证适配器时，不得启用生产写入。
