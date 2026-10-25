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
  if (path === "/report") {
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
await call("/team/provision", { actor: "auditor", body: version(), expectedStatus: 403 });
await call("/team/provision", { body: version() });
assert.equal(state.assistants.length, 3);
const task = state.tasks.find(item => item.findingId === finding.id);
assert.ok(task);
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
const report = await call("/report", { actor: "auditor" });
assert.ok(report.sections.some(item => item.title === "能力边界"));
assert.ok(!JSON.stringify(state).includes("snapshotRows"), "public view leaked source bodies");
console.log(JSON.stringify({ result: "PASS", mode: state.mode, stateVersion: state.stateVersion, sourceId: source.id, findingId: finding.id,
  taskId: task.id, verifiedBy: verified.verifiedBy, coverage: "explicit synthetic full JSON snapshot; local demo identities only", reportMethod: report.method }, null, 2));
}
