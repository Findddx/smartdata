# 0005：按官方安全公告更新依赖补丁

状态：已实施；构建、回归与复扫结果见
[阶段验证记录](../verification/2026-10-25-workspace-release.md)。

首轮公开仓库的安全扫描发现：原计划锁定的 JSON 解析库和 Web 服务容器已经有
已知高危漏洞。因此不能继续把最初的精确版本当成永久要求。本次保持框架主版本、
业务接口和两套 JSON 包名不变，升级同一维护分支的安全补丁。

JSON 是系统之间常用的数据格式；Jackson 负责读写这种格式，Tomcat 负责接收
浏览器和 API 的网络请求。它们属于产品底层，补丁应在发布前统一更新。

## 改了什么，为什么

| 依赖 | 原版本 | 新版本 | 对普通使用者的含义 |
|---|---|---|---|
| 业务 Jackson 3 | 3.2.1 | 3.2.4 | 修复 JSON 处理与类型检查中已公开的问题 |
| SDK/工作进程的 Jackson 2 | 2.21.5 | 2.21.8 | 同类修复同时覆盖 OpenAI SDK 与 Temporal 工作进程 |
| 嵌入式 Tomcat 全组件 | 11.0.24 | 11.0.27 | 修复认证、权限检查及请求/响应处理中的已知问题 |

原始 Trivy 扫描有 23 条高危/严重记录，包含多个模块对同一漏洞的重复引用。
Jackson 涉及 `CVE-2026-89407`、`CVE-2026-89425`、`CVE-2026-68497`、
`CVE-2026-91776`、`CVE-2026-91777`，已对照维护者的
[core 公告清单](https://github.com/FasterXML/jackson-core/security/advisories) 和
[databind 公告清单](https://github.com/FasterXML/jackson-databind/security/advisories)。
最初扫描给出的 Tomcat 最低修复版为
11.0.25；但 Apache 官方公告已说明 11.0.27 修复了影响至 11.0.26 的问题，
包括 `CVE-2026-103119`、`CVE-2026-101356` 和 `CVE-2026-101296`。
所以本次采用同一 11.0.x 分支的 11.0.27，避免只满足滞后的扫描数据库。

进一步遍历 FasterXML 官方全部公开安全公告后，又发现 10 月 20 日公布的严重
问题 `CVE-2026-110879`：某些按输入中的类名创建程序对象的配置可绕过类型校验，影响
Jackson 3 至 3.2.3、Jackson 2 至 2.21.7。因此没有停在扫描器建议的这两个版本，
而是升至同分支的 3.2.4、2.21.8。3.2.4 还包含 `CVE-2026-110729` 的修复。
这说明自动扫描必须与维护者的原始公告交叉核对；扫描数据库会有时间差。

这些记录证明依赖版本受到官方公告影响，不表示本项目已经被攻击，也不表示
每个漏洞所需的特殊配置都在本项目启用。已有安全补丁可用时仍统一更新，减少
未来配置变化重新暴露旧问题的机会。

## 怎样保持兼容和一致

版本集中在 `gradle/libs.versions.toml`。业务 Jackson 3 使用统一 BOM，即官方
提供的配套版本清单。SDK 和工作进程单独引入 Jackson 2 的 2.21.8 BOM，保留
`com.fasterxml.jackson` 包名；业务 DTO 继续使用 `tools.jackson` 包名。
两代库不能靠替换类名强行合并。

`jackson-annotations` 是两代共享的注解包，版本遵循相应官方 BOM。它采用
2.21、2.22 这样的独立版本号，不要求改成不存在的 2.21.8 注解制品。

Tomcat 的 `core / el / websocket` 三个嵌入式组件一起更新为 11.0.27，防止
容器内部混版。Spring Boot 4.1.1、Spring AI 2.0.1 及业务实现本次不作连带升级。
CI 读取构建后的 SBOM（实际制品清单），检查所有 Jackson 3 模块、Jackson 2
编解码模块和 Tomcat 组件分别使用对应的固定补丁版本。

## 验证要求

新增依赖哈希须逐项与 Maven Central 官方 HTTPS 制品比较。重新生成依赖锁，
然后以严格依赖校验运行完整 87 项测试、SBOM、启动包构建及真实 HTTP 协作与
重启恢复检查。独立扫描人员随后对准备公开的工作树和生成的 SBOM/启动包复扫。
不能通过忽略这些漏洞、降低风险阈值或跳过测试让发布门变绿。
新增 30 项制品的官方 URL、SHA-256 和逐项比较结果见
[安全补丁依赖核验明细](../verification/2026-10-25-security-dependencies.json)。

官方依据：

- [FasterXML 类型校验绕过严重漏洞公告](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph)
- [Jackson core 3.2.4 修复公告](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-vvjm-vc27-799r)
- [Jackson 3.2.4 官方 BOM 标签](https://github.com/FasterXML/jackson-bom/tree/jackson-bom-3.2.4)
- [Jackson 2.21.8 官方 BOM 标签](https://github.com/FasterXML/jackson-bom/tree/jackson-bom-2.21.8)
- [Apache Tomcat 11 官方安全公告](https://tomcat.apache.org/security-11.html)
- [Jackson 3.2.4 官方 BOM](https://repo.maven.apache.org/maven2/tools/jackson/jackson-bom/3.2.4/jackson-bom-3.2.4.pom)
- [Jackson 2.21.8 官方 BOM](https://repo.maven.apache.org/maven2/com/fasterxml/jackson/jackson-bom/2.21.8/jackson-bom-2.21.8.pom)
