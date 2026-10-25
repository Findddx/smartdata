import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const contractsDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const schema = JSON.parse(await readFile(resolve(contractsDir, "workspace/workspace.schema.json"), "utf8"));
const ajv = new Ajv2020({ allErrors: true, strict: true, validateFormats: true });
addFormats(ajv);
ajv.addSchema(schema);
const validator = (name) => {
  const validate = ajv.getSchema(`${schema.$id}#/$defs/${name}`);
  assert.ok(validate, `missing workspace definition ${name}`);
  return validate;
};
for (const name of Object.keys(schema.$defs)) validator(name);
const at = "2026-10-25T08:00:00Z";
const sha256 = "a".repeat(64);
const source = {
  id: "source-1", name: "脱敏订单样本", kind: "JSON_SNAPSHOT", provenance: "USER", status: "SCANNED",
  endpointLabel: null, lastScannedAt: at, assetCount: 1, credentialConfigured: false, stateVersion: 2, lastError: null,
};
const evidence = { sha256, sampledRows: 2, totalRows: 2, sampleLimit: 1000, observedAt: at, sampling: "UPLOADED_SNAPSHOT_COMPLETE" };
const finding = {
  id: "finding-1", sourceId: source.id, assetId: "asset-1", scanId: "scan-1", provenance: "USER",
  ruleId: "DQ.DUPLICATE_PRIMARY_KEY.v1", title: "业务唯一键出现重复", severity: "HIGH", column: "order_id",
  observed: "非空键值额外重复 1 次（仅当前样本）", expected: "已声明的业务唯一键在观察范围内不重复", evidence,
  suggestion: "核对来源记录，不自动合并订单。", active: true,
};
const example = {
  stateVersion: 4, mode: "LOCAL_PERSISTENT", sources: [source],
  assets: [{ id: "asset-1", sourceId: source.id, name: "脱敏订单样本", provenance: "USER", rowCount: 2, totalRows: 2,
    sampleLimit: 1000, sampled: false, columns: [{ name: "order_id", inferredType: "STRING", missingCount: 0, distinctCount: 1 }],
    observedAt: at, evidenceSha256: sha256, sampling: "UPLOADED_SNAPSHOT_COMPLETE" }],
  scans: [{ id: "scan-1", sourceId: source.id, provenance: "USER", status: "SUCCEEDED", startedAt: at, completedAt: at,
    assetIds: ["asset-1"], findingIds: [finding.id], method: "READ_ONLY_RULE_SCAN_V1", error: null }],
  findings: [finding],
  assistants: [{ id: "assistant-analysis", role: "ANALYSIS", name: "数据分析助手", responsibility: "核对样本中的数据问题。",
    status: "READY", method: "DETERMINISTIC_RULES", findingIds: [finding.id], taskIds: ["task-1"] }],
  tasks: [{ id: "task-1", assistantId: "assistant-analysis", findingId: finding.id, title: finding.title, description: finding.suggestion,
    status: "PROPOSED", ownerId: null, createdAt: at, updatedAt: at, completionNote: null, evidenceRefs: [finding.id], stateVersion: 1, verifiedBy: null }],
  comments: [{ id: "comment-1", taskId: "task-1", actorId: "operator", body: "已核对入口记录。", evidenceRefs: [finding.id], createdAt: at }],
  messages: [{ id: "message-1", assistantId: "assistant-analysis", actorId: "operator", question: "先检查什么？", answer: finding.suggestion,
    evidenceRefs: [finding.id], createdAt: at, method: "DETERMINISTIC_RULES" }],
  audit: [{ id: "audit-1", actorId: "admin", event: "source.scanned", resourceId: source.id, occurredAt: at, stateVersion: 4 }],
  capabilities: { sourceKinds: ["JSON_SNAPSHOT", "HTTP_JSON", "JDBC_POSTGRES_READONLY"], httpAllowedHosts: [], postgresAllowedHosts: [],
    maxRowsPerAsset: 1000, maxResponseBytes: 1048576, mutationsAffectSourceSystems: false, assistantMethod: "DETERMINISTIC_RULES" },
};
function accepted(name, value) {
  const validate = validator(name);
  assert.equal(validate(value), true, `${name} rejected valid fixture: ${ajv.errorsText(validate.errors)}`);
}
let rejectedCount = 0;
function rejected(name, value, reason) {
  assert.equal(validator(name)(value), false, `${name} accepted invalid fixture: ${reason}`);
  rejectedCount += 1;
}
accepted("WorkspaceView", example);
// Rules remain the default and old persisted messages remain readable. Model
// responses carry explicit outcome and provenance rather than impersonating rules.
const modelExample = structuredClone(example);
modelExample.capabilities.assistantMethod = "SPRING_AI_EVIDENCE_V1";
modelExample.assistants[0].method = "SPRING_AI_EVIDENCE_V1";
Object.assign(modelExample.messages[0], {
  method: "SPRING_AI_EVIDENCE_V1", status: "SUCCEEDED", uncertainty: ["仅覆盖已观察的脱敏样本。"], errorCode: null,
  trace: { providerProfile: "private-vllm", modelId: "customer-model", modelVersion: "2026-10-25", promptVersion: "evidence-v1",
    contextSha256: sha256, responseId: "response-1", inputTokens: 120, outputTokens: 40 },
});
accepted("WorkspaceView", modelExample);
const modelFailure = structuredClone(modelExample.messages[0]);
Object.assign(modelFailure, { status: "FAILED", answer: "模型服务未完成，需人工处理。", evidenceRefs: [], errorCode: "PROVIDER_TIMEOUT" });
accepted("AssistantMessage", modelFailure);
accepted("CreateSourceCommand", { expectedStateVersion: 0, name: "订单", kind: "JSON_SNAPSHOT", observedAt: at, snapshotRows: [{ order_id: "O-1", amount: 10 }] });
accepted("CreateSourceCommand", { expectedStateVersion: 0, name: "业务 API", kind: "HTTP_JSON", endpoint: "https://shop.example.test/orders", credentialEnvRef: "SMARTDATA_CONNECTOR_SHOP_TOKEN" });
accepted("CreateSourceCommand", { expectedStateVersion: 0, name: "只读业务库", kind: "JDBC_POSTGRES_READONLY", endpoint: "jdbc:postgresql://db.example.test/commerce", usernameEnvRef: "SMARTDATA_CONNECTOR_DB_USER", credentialEnvRef: "SMARTDATA_CONNECTOR_DB_PASSWORD", schema: "public" });
accepted("ReplaceSnapshotCommand", { expectedStateVersion: 2, snapshotRows: [{ order_id: "O-1" }], observedAt: at });
accepted("TaskTransitionCommand", { expectedStateVersion: 2, status: "COMPLETED", note: "已提交处理结果。", evidenceRefs: [finding.id] });
accepted("TaskCommentCommand", { expectedStateVersion: 2, body: "请核对来源。", evidenceRefs: [finding.id] });
accepted("AssistantMessageCommand", { expectedStateVersion: 2, question: "先检查什么？" });
accepted("WorkspaceReport", { generatedAt: at, stateVersion: 4, method: "DETERMINISTIC_EVIDENCE_REPORT_V1", title: "范围明确的规则报告", sections: [{ title: "观察范围", text: "2 行脱敏快照。" }], evidenceRefs: [finding.id] });

const clone = (value) => structuredClone(value);
const leaked = clone(example); leaked.sources[0].credentialEnvRef = "SMARTDATA_CONNECTOR_SHOP_TOKEN";
rejected("WorkspaceView", leaked, "credential reference leaked into public response");
const rawRows = clone(example); rawRows.assets[0].snapshotRows = [{ customer: "private" }];
rejected("WorkspaceView", rawRows, "raw rows leaked into public response");
const fabricated = clone(example); fabricated.assistants[0].method = "AUTONOMOUS_LLM";
rejected("WorkspaceView", fabricated, "deterministic adapter presented as LLM");
const missing = clone(example); delete missing.findings[0].evidence;
rejected("WorkspaceView", missing, "finding lacks provenance evidence");
const unknown = clone(example); unknown.assets[0].totalRows = -1;
rejected("WorkspaceView", unknown, "unknown size represented as negative row count");
rejected("VersionCommand", { expectedStateVersion: -1 }, "negative optimistic version");
rejected("VersionCommand", { expectedStateVersion: 0, tenantId: "other-tenant" }, "client supplied tenant scope");
rejected("CreateSourceCommand", { expectedStateVersion: 0, name: "订单", kind: "JSON_SNAPSHOT", snapshotRows: [] }, "missing actual collection time");
rejected("CreateSourceCommand", { expectedStateVersion: 0, name: "订单", kind: "JSON_SNAPSHOT", observedAt: at, snapshotRows: [{ customer: { name: "nested" } }] }, "nested/unbounded row value");
rejected("CreateSourceCommand", { expectedStateVersion: 0, name: "API", kind: "HTTP_JSON", endpoint: "https://shop.example.test", credentialEnvRef: "AWS_SECRET_ACCESS_KEY" }, "unrelated server credential reference");
rejected("CreateSourceCommand", { expectedStateVersion: 0, name: "db", kind: "JDBC_POSTGRES_READONLY", endpoint: "jdbc:postgresql://db.example.test/app", credentialEnvRef: "SMARTDATA_CONNECTOR_DB_PASSWORD" }, "missing bound database username reference");
rejected("TaskTransitionCommand", { expectedStateVersion: 2, status: "VERIFIED", note: " " }, "blank verification note");
rejected("TaskTransitionCommand", { expectedStateVersion: 2, status: "EXECUTE_SQL", note: "run" }, "arbitrary action state");
const noTrace = structuredClone(modelExample.messages[0]); delete noTrace.trace;
rejected("AssistantMessage", noTrace, "model result has no reproducible context trace");
const implicitModelOutcome = structuredClone(modelExample.messages[0]); delete implicitModelOutcome.status;
rejected("AssistantMessage", implicitModelOutcome, "model result omits its outcome");
const noModelEvidence = structuredClone(modelExample.messages[0]); noModelEvidence.evidenceRefs = [];
rejected("AssistantMessage", noModelEvidence, "successful model result has no evidence references");
const contradictoryModelOutcome = structuredClone(modelExample.messages[0]); contradictoryModelOutcome.errorCode = "PROVIDER_FAILURE";
rejected("AssistantMessage", contradictoryModelOutcome, "model failure code disguised as success");
const missingFailureCode = structuredClone(modelFailure); missingFailureCode.errorCode = null;
rejected("AssistantMessage", missingFailureCode, "model failure lacks a safe error code");
const negativeUsage = structuredClone(modelExample.messages[0]); negativeUsage.trace.inputTokens = -1;
rejected("AssistantMessage", negativeUsage, "model trace contains negative usage");
const leakedModelSecret = structuredClone(modelExample.messages[0]); leakedModelSecret.trace.apiKey = "not-a-real-secret";
rejected("AssistantMessage", leakedModelSecret, "model trace exposes credentials");
console.log(`Workspace schema compiled all ${Object.keys(schema.$defs).length} definitions; response/command fixtures passed and ${rejectedCount} unsafe fixtures were rejected.`);
