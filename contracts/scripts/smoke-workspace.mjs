import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

// Run against a deliberately disposable, initially empty local workspace.
// This script does not delete existing user data or reset non-empty workspaces.
const base = new URL(process.env.SMARTDATA_WORKSPACE_SMOKE_URL ?? "http://127.0.0.1:18080");
assert.ok(["127.0.0.1", "localhost", "[::1]"].includes(base.hostname), "smoke target must be a loopback local service");
assert.equal(base.pathname, "/", "smoke URL must contain only scheme, host and port");
assert.equal(base.username + base.password + base.search + base.hash, "", "smoke URL must not embed credentials or query parameters");
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const schema = JSON.parse(await readFile(resolve(root, "workspace/workspace.schema.json"), "utf8"));
const ajv = new Ajv2020({ strict: true, allErrors: true }); addFormats(ajv); ajv.addSchema(schema);
const viewValidator = ajv.getSchema(`${schema.$id}#/$defs/WorkspaceView`);
const reportValidator = ajv.getSchema(`${schema.$id}#/$defs/WorkspaceReport`);
const historyValidator = ajv.getSchema(`${schema.$id}#/$defs/BusinessBriefHistory`);
let state;
async function call(path, { actor = "admin", body, key = randomUUID(), expectedStatus = 200 } = {}) {
  const headers = { Accept: "application/json", "X-Demo-Actor": actor };
  if (body !== undefined) { headers["Content-Type"] = "application/json"; headers["Idempotency-Key"] = key; }
  const response = await fetch(new URL(`/api/v1/workspace${path}`, base), {
    method: body === undefined ? "GET" : "POST", headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(45000),
  });
  const value = await response.json();
  assert.equal(response.status, expectedStatus, `${path}: expected ${expectedStatus}, got ${response.status}: ${JSON.stringify(value)}`);
  if (expectedStatus !== 200) return value;
  if (path === "/business-brief/history") {
    assert.ok(historyValidator(value), `business history violated schema: ${ajv.errorsText(historyValidator.errors)}`);
  } else if (path === "/report") {
    assert.ok(reportValidator(value), `report violated schema: ${ajv.errorsText(reportValidator.errors)}`);
  } else {
    assert.ok(viewValidator(value), `workspace violated schema: ${ajv.errorsText(viewValidator.errors)}`);
    state = value;
  }
  return value;
}
const version = () => ({ expectedStateVersion: state.stateVersion });
await call("");
if (process.argv.includes("--verify-restored")) {
  const restoredSource = state.sources.find(item => item.name === "HTTP 冒烟 · 合成订单样本");
  assert.ok(restoredSource, "restart lost the source registered by the prior smoke run");
  const restoredFinding = state.findings.find(item => item.sourceId === restoredSource.id && item.ruleId === "DQ.DUPLICATE_PRIMARY_KEY.v1");
  assert.ok(restoredFinding, "restart lost historical finding evidence");
  const restoredTask = state.tasks.find(item => item.findingId === restoredFinding.id);
  assert.equal(restoredTask?.status, "VERIFIED", "restart lost verified collaboration state");
  assert.equal(restoredTask.verifiedBy, "sales-owner");
  assert.ok(state.comments.some(item => item.taskId === restoredTask.id), "restart lost collaboration comments");
  assert.ok(state.messages.some(item => item.method === "DETERMINISTIC_RULES"), "restart lost evidence-based assistant messages");
  assert.ok(state.scans.filter(item => item.sourceId === restoredSource.id).length >= 2, "restart lost scan history");
  assert.equal(state.businessBrief?.enterpriseName, "HTTP 冒烟合成企业", "restart lost business context");
  assert.equal(state.businessBrief?.metrics[0]?.status, "DRAFT", "restart lost the changed metric's required reapproval");
  assert.equal(state.businessBrief?.metrics[0]?.calculationStatus, "NOT_COMPUTED");
  const history = await call("/business-brief/history", { actor: "auditor" });
  assert.equal(history.length, 4, "restart lost immutable business brief versions");
  assert.equal(history[1].metrics[0].status, "CONFIRMED", "restart altered historical confirmation");
  await call("/report", { actor: "auditor" });
  console.log(JSON.stringify({ result: "PASS_RESTART_RESTORE", stateVersion: state.stateVersion, taskId: restoredTask.id,
    coverage: "persisted local source, historical evidence, messages, comments and verified task survived process restart" }, null, 2));
} else {
assert.equal(state.sources.length, 0, "smoke requires an empty disposable workspace; no user data was changed");
assert.equal(state.tasks.length, 0, "smoke requires no existing tasks");
const createdAt = new Date().toISOString();
const create = { ...version(), name: "HTTP 冒烟 · 合成订单样本", kind: "JSON_SNAPSHOT", observedAt: createdAt, primaryKey: "order_id", snapshotRows: [{ order_id: "SMOKE-1", amount: 10, channel: "web" }, { order_id: "SMOKE-1", amount: 10, channel: null }] };
await call("/sources", { actor: "order-specialist", body: create, expectedStatus: 403 });
const key = randomUUID();
await call("/sources", { body: create, key });
const source = state.sources[0];
assert.equal(source.provenance, "USER", "explicitly uploaded synthetic snapshot uses the upload path, not hidden DEMO fallback");
await call("/sources", { body: create, key });
assert.equal(state.sources.length, 1, "idempotent replay duplicated a source");
await call(`/sources/${source.id}/scan`, { body: version() });
assert.equal(state.scans.at(-1).status, "SUCCEEDED");
const finding = state.findings.find(item => item.active && item.ruleId === "DQ.DUPLICATE_PRIMARY_KEY.v1");
assert.ok(finding, "actual duplicate rows did not generate expected finding");
assert.equal(finding.evidence.sampledRows, 2);
assert.ok(state.findings.some(item => item.active && item.ruleId === "DQ.MISSING_VALUES.v1"), "null cells must survive persistence and generate observable missingness");
assert.equal(state.assets[0].sampled, false, "full tiny snapshot should not be presented as partial sampling");
const metric = { id: "paid-orders", name: "支付订单数", businessDefinition: "按支付完成时间归属，取消与退款另行核对。", owner: "合成业务负责人", timeZone: "Asia/Shanghai", currency: "CNY", formulaDescription: "按 order_id 去重计数；这里只登记定义，不执行查询。", sourceIds: [source.id] };
const brief = { ...version(), expectedBriefVersion: 0, enterpriseName: "HTTP 冒烟合成企业", businessDescription: "仅验证业务背景影响工作安排，不代表真实企业或经营收益。", goals: [{ id: "trust-analysis", title: "先核对订单分析数据", type: "ANALYTICS_TRUST", expectedOutcome: "定位重复与缺失，保留来源证据。", priority: "HIGH", sourceIds: [source.id] }], includedSourceIds: [source.id], metrics: [metric] };
await call("/business-brief", { actor: "auditor", body: brief, expectedStatus: 403 });
const briefKey = randomUUID();
await call("/business-brief", { actor: "order-specialist", body: brief, key: briefKey });
const savedBriefVersion = state.stateVersion;
await call("/business-brief", { actor: "order-specialist", body: brief, key: briefKey });
assert.equal(state.stateVersion, savedBriefVersion, "brief replay changed the workspace twice");
assert.equal(state.businessBrief.version, 1);
assert.equal(state.businessBrief.metrics[0].status, "DRAFT");
assert.equal(state.businessBrief.modelContextExportEnabled, false, "business form must not grant model egress");
await call("/business-brief", { body: brief, expectedStatus: 409 });
const confirm = { ...version(), expectedBriefVersion: 1, expectedMetricVersion: 1 };
await call("/business-brief/metrics/paid-orders/confirm", { actor: "order-specialist", body: confirm, expectedStatus: 403 });
await call("/business-brief/metrics/paid-orders/confirm", { actor: "sales-owner", body: confirm });
assert.equal(state.businessBrief.version, 2);
assert.equal(state.businessBrief.metrics[0].confirmedBy, "sales-owner");
assert.equal(state.businessBrief.metrics[0].calculationStatus, "NOT_COMPUTED", "confirmed text must not become a calculated business metric");
await call("/team/provision", { actor: "auditor", body: version(), expectedStatus: 403 });
await call("/team/provision", { body: version() });
assert.equal(state.assistants.length, 3);
const task = state.tasks.find(item => item.findingId === finding.id);
assert.ok(task);
assert.ok(task.goalIds.includes("trust-analysis") && task.inCurrentScope, "business goal did not bind real assistant work");
assert.equal(task.currentFindingId, finding.id);
await call(`/assistants/${task.assistantId}/messages`, { actor: "order-specialist", body: { ...version(), question: "这个重复问题应该先检查什么？" } });
assert.equal(state.messages.at(-1).method, "DETERMINISTIC_RULES");
assert.ok(state.messages.at(-1).evidenceRefs.includes(finding.id));
await call(`/tasks/${task.id}/transition`, { actor: "order-specialist", body: { ...version(), status: "ACCEPTED", note: "领取合成样本中的重复键问题。" } });
await call(`/tasks/${task.id}/comments`, { actor: "order-specialist", body: { ...version(), body: "已核对入口记录，下一步提供更正后的合成快照。", evidenceRefs: [finding.id] } });
await call(`/tasks/${task.id}/transition`, { actor: "order-specialist", body: { ...version(), status: "COMPLETED", note: "已准备更正后的来源快照，等待独立复查。", evidenceRefs: [finding.id] } });
await call(`/tasks/${task.id}/transition`, { actor: "sales-owner", body: { ...version(), status: "VERIFIED", note: "没有新观测时不能通过。", evidenceRefs: [finding.scanId] }, expectedStatus: 400 });
// A source re-observation must occur after the original observation, even on fast local hardware.
await new Promise(resolveDelay => setTimeout(resolveDelay, 20));
await call(`/sources/${source.id}/snapshot`, { body: { ...version(), observedAt: new Date().toISOString(), snapshotRows: [{ order_id: "SMOKE-1", amount: 10, channel: "web" }, { order_id: "SMOKE-2", amount: 12, channel: "web" }] } });
await call(`/sources/${source.id}/scan`, { body: version() });
const latest = state.scans.at(-1);
assert.equal(latest.status, "SUCCEEDED");
assert.ok(!state.findings.some(item => item.active && item.ruleId === finding.ruleId && item.assetId === finding.assetId));
await call(`/tasks/${task.id}/transition`, { actor: "sales-owner", body: { ...version(), status: "VERIFIED", note: "不同本地演示身份已核对更新后的完整合成快照；原唯一键规则不再触发。", evidenceRefs: [latest.id] } });
const verified = state.tasks.find(item => item.id === task.id);
assert.equal(verified.status, "VERIFIED");
assert.equal(verified.verifiedBy, "sales-owner");
await call("/business-brief", { body: { ...brief, ...version(), expectedBriefVersion: state.businessBrief.version, metrics: [{ ...metric, businessDefinition: "改为按订单创建时间归属，必须重新审核。" }] } });
assert.equal(state.businessBrief.metrics[0].status, "DRAFT", "changed definition retained obsolete approval");
assert.equal(state.businessBrief.metrics[0].confirmedBy, null);
assert.equal(state.businessBrief.metrics[0].calculationStatus, "NOT_COMPUTED");
await call("/business-brief", { body: { ...brief, ...version(), expectedBriefVersion: state.businessBrief.version, goals: brief.goals.map(goal => ({ ...goal, sourceIds: [] })), includedSourceIds: [], metrics: [{ ...metric, businessDefinition: "改为按订单创建时间归属，必须重新审核。", sourceIds: [] }] } });
assert.ok(state.tasks.every(item => !item.inCurrentScope), "empty explicit scope continued assigning work");
assert.equal(state.tasks.find(item => item.id === task.id).status, "VERIFIED", "scope change rewrote historical verification");
const businessHistory = await call("/business-brief/history", { actor: "auditor" });
assert.equal(businessHistory.length, 4);
assert.equal(businessHistory[1].metrics[0].status, "CONFIRMED", "new draft overwrote historical confirmation");
const report = await call("/report", { actor: "auditor" });
assert.ok(report.sections.some(item => item.title === "能力边界"));
assert.ok(!JSON.stringify(state).includes("snapshotRows"), "public view leaked source bodies");
console.log(JSON.stringify({ result: "PASS", mode: state.mode, stateVersion: state.stateVersion, sourceId: source.id, findingId: finding.id,
  taskId: task.id, verifiedBy: verified.verifiedBy, businessBriefVersions: businessHistory.length,
  coverage: "explicit synthetic full JSON snapshot, versioned business goals and scope, reviewed metric definitions, changed-definition reapproval, preserved history; local demo identities only", reportMethod: report.method }, null, 2));
}
