import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const schema = JSON.parse(await readFile(resolve(root, "workspace/lineage-v1.schema.json"), "utf8"));
const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);
ajv.addSchema(schema);
const validator = (name) => {
  const validate = ajv.getSchema(name === "Graph" ? schema.$id : `${schema.$id}#/$defs/${name}`);
  assert.ok(validate, `missing lineage definition ${name}`);
  return validate;
};
for (const name of Object.keys(schema.$defs)) validator(name);

/** Independent consumer checks beyond what JSON Schema can express. */
function graphMeaning(graph) {
  const ids = new Map(graph.nodes.map(node => [node.id, node]));
  assert.equal(ids.size, graph.nodes.length, "node IDs must be unique");
  assert.equal(new Set(graph.edges.map(edge => edge.id)).size, graph.edges.length, "edge IDs must be unique");
  for (const node of graph.nodes) {
    assert.ok(node.sourceIds.every(id => graph.scope.sourceIds.includes(id)), "node escaped graph scope");
    assert.ok(node.evidenceRefs.every(ref => node.sourceIds.includes(ref.sourceId)), "node evidence escaped its sources");
  }
  for (const edge of graph.edges) {
    const input = ids.get(edge.from), output = ids.get(edge.to);
    assert.ok(input && output, "edge has an undisplayed endpoint");
    assert.notEqual(edge.from, edge.to, "self edges are not meaningful lineage");
    assert.ok(edge.evidenceRefs.every(ref => graph.scope.sourceIds.includes(ref.sourceId)), "edge escaped graph scope");
    if (edge.kind === "CONTAINS") {
      assert.ok((input.kind === "SOURCE" && output.kind === "TABLE") || (input.kind === "TABLE" && output.kind === "FIELD"), "containment must describe physical metadata");
    } else {
      assert.ok(Number(input.layer.slice(1)) < Number(output.layer.slice(1)), "derived edges must flow from inputs to later layers");
      if (edge.kind === "MAPPED_TO") assert.equal(output.kind, "BUSINESS_ENTITY");
      if (edge.kind === "FEEDS_INTO") assert.equal(output.kind, "METRIC");
      if (edge.kind === "INFORMS") assert.ok(input.kind === "METRIC" && output.kind === "DECISION_PLAN");
    }
  }
  const coverage = graph.coverage;
  for (const kind of ["Sources", "Tables", "Fields"]) {
    assert.ok(coverage[`shown${kind}`] <= coverage[`total${kind}`], "coverage cannot exceed the observed denominator");
  }
  assert.equal(coverage.shownSources, graph.nodes.filter(node => node.kind === "SOURCE").length);
  assert.equal(coverage.shownTables, graph.nodes.filter(node => node.kind === "TABLE").length);
  assert.equal(coverage.shownFields, graph.nodes.filter(node => node.kind === "FIELD").length);
  if (["Sources", "Tables", "Fields"].some(kind => coverage[`shown${kind}`] < coverage[`total${kind}`]) || coverage.omittedDerivedDefinitions > 0) {
    assert.equal(coverage.partial, true, "omitted lineage must be marked partial");
  }
}
function pageMeaning(page) {
  assert.equal(page.nodes.length, Math.max(0, Math.min(page.limit, page.totalFields - page.offset)), "field page omitted observed fields");
  assert.equal(page.hasMore, page.offset + page.nodes.length < page.totalFields, "field continuation flag is inconsistent");
  const ids = new Set(page.nodes.map(node => node.id));
  assert.equal(ids.size, page.nodes.length);
  for (const node of page.nodes) {
    assert.equal(node.ref.sourceId, page.sourceId);
    assert.equal(node.ref.assetId, page.assetId);
  }
  assert.equal(page.edges.length, page.nodes.length);
  assert.equal(new Set(page.edges.map(edge => edge.to)).size, page.edges.length);
  assert.ok(page.edges.every(edge => ids.has(edge.to)), "field page points to an undisplayed field");
}
function accepted(name, value) {
  const validate = validator(name);
  assert.equal(validate(value), true, `${name}: ${ajv.errorsText(validate.errors)}`);
  if (name === "Graph") graphMeaning(value);
  if (name === "FieldPage") pageMeaning(value);
}

const actualFile = process.argv.indexOf("--response");
if (actualFile >= 0) {
  const type = process.argv[actualFile + 2] ?? "Graph";
  assert.ok(["Graph", "FieldPage", "Mappings"].includes(type));
  accepted(type, JSON.parse(await readFile(process.argv[actualFile + 1], "utf8")));
  console.log(`Actual lineage ${type} response passed schema and consumer invariants.`);
} else {
  const at = "2026-10-25T08:00:00Z";
  const id = (kind) => `lineage-${kind}-${"a".repeat(24)}`;
  const evidence = { sourceId: "source-1", assetId: "asset-1", scanId: "scan-1", sha256: "a".repeat(64), observedAt: at };
  const node = (kind, layer, shape, ref, nature, materialization) => ({
    id: id(kind.toLowerCase().replaceAll("_", "")), label: kind, summary: "metadata or unexecuted definition", kind, layer, shape, ref,
    evidenceNature: nature, materialization, provenance: "DEMO", sourceIds: ["source-1"], evidenceRefs: [evidence],
    observedAt: at, sensitivity: "UNKNOWN", freshness: "CURRENT", authorization: "METADATA_VISIBLE", scopeStatus: "IN_SCOPE", notices: [],
  });
  const source = node("SOURCE", "L0", "DATABASE_CYLINDER", { type: "SOURCE", id: "source-1", sourceId: "source-1" }, "OBSERVED", "METADATA_ONLY");
  const table = node("TABLE", "L0", "TABLE_GRID", { type: "ASSET", id: "asset-1", sourceId: "source-1", assetId: "asset-1" }, "OBSERVED", "METADATA_ONLY");
  const field = node("FIELD", "L0", "FIELD_PILL", { type: "FIELD", id: "customer_id", sourceId: "source-1", assetId: "asset-1", fieldName: "customer_id" }, "OBSERVED", "METADATA_ONLY");
  const entity = node("BUSINESS_ENTITY", "L1", "ENTITY_HEXAGON", { type: "ENTITY_DEFINITION", id: "customer", definitionVersion: 1 }, "DECLARED", "NOT_MATERIALIZED");
  const metric = node("METRIC", "L2", "METRIC_CIRCLE", { type: "METRIC_DEFINITION", id: "orders", definitionVersion: 2 }, "DECLARED", "NOT_MATERIALIZED");
  const plan = node("DECISION_PLAN", "L3", "DECISION_DIAMOND", { type: "BUSINESS_GOAL", id: "trust", definitionVersion: 1 }, "PLANNED", "NOT_MATERIALIZED");
  const edge = (from, to, kind, nature, style, transformation) => ({
    id: `${from.id}:${to.id}`, from: from.id, to: to.id, kind, evidenceNature: nature, lineStyle: style,
    transformationStatus: transformation, description: "Input to dependent object; not proof of computed business data.", evidenceRefs: [evidence],
  });
  const edges = [edge(source, table, "CONTAINS", "OBSERVED", "SOLID", "NOT_APPLICABLE"), edge(table, field, "CONTAINS", "OBSERVED", "SOLID", "NOT_APPLICABLE"),
    edge(field, entity, "MAPPED_TO", "DECLARED", "DASHED", "DECLARED_ONLY"), edge(entity, metric, "FEEDS_INTO", "DECLARED", "DASHED", "DECLARED_ONLY"), edge(metric, plan, "INFORMS", "PLANNED", "DOTTED", "DECLARED_ONLY")];
  const graph = { stateVersion: 5, mappingVersion: 1, generatedAt: at, scope: { briefVersion: 2, sourceIds: ["source-1"], excludedAuthorizedSourceCount: 1 },
    nodes: [source, table, field, entity, metric, plan], edges, notices: ["Visible metadata only; derived definitions have not been materialized."],
    coverage: { partial: false, totalSources: 1, shownSources: 1, totalTables: 1, shownTables: 1, totalFields: 1, shownFields: 1, omittedDerivedDefinitions: 0, nodeLimit: 3000, edgeLimit: 6000, fieldLimit: 256 } };
  const ref = { sourceId: "source-1", assetId: "asset-1", fieldName: "customer_id" };
  const command = { expectedStateVersion: 5, expectedMappingVersion: 0, entities: [{ id: "customer", name: "customer definition", entityType: "CUSTOMER", inputFields: [ref], transformationDescription: "Declare exact customer ID input; no execution." }],
    metrics: [{ metricId: "orders", definitionVersion: 2, inputEntityIds: ["customer"], inputFields: [], transformationDescription: "Business definition only." }], decisions: [{ goalId: "trust", inputMetricIds: ["orders"], decisionQuestion: "Which cases need review?" }] };
  const page = { stateVersion: 5, sourceId: "source-1", assetId: "asset-1", totalFields: 1, offset: 0, limit: 128, nodes: [field], edges: [edges[1]], hasMore: false };
  accepted("Graph", graph); accepted("SaveMappingsCommand", command); accepted("FieldPage", page);
  accepted("Mappings", { version: 1, entities: command.entities, metrics: command.metrics, decisions: command.decisions, updatedBy: "admin", updatedAt: at });
  const partial = structuredClone(graph); partial.coverage.totalFields = 512; partial.coverage.partial = true; accepted("Graph", partial);
  let rejected = 0;
  function unsafe(name, value, message) { assert.equal(validator(name)(value), false, `accepted unsafe ${message}`); rejected++; }
  unsafe("Graph", { ...graph, tenantId: "forged" }, "caller-defined tenant");
  unsafe("Graph", { ...graph, rawRows: [] }, "raw data rows");
  unsafe("Node", { ...entity, materialization: "METADATA_ONLY" }, "unexecuted entity presented as observed metadata");
  unsafe("Node", { ...metric, evidenceNature: "OBSERVED" }, "declared formula presented as observed computation");
  unsafe("Node", { ...plan, evidenceNature: "DECLARED" }, "plan presented as existing result");
  unsafe("Node", { ...source, authorization: "SOURCE_WRITE_ALLOWED" }, "metadata permission promoted to source writes");
  unsafe("Node", { ...field, layer: "L2" }, "field given an analysis layer");
  unsafe("Node", { ...metric, shape: "TABLE_GRID" }, "metric given a physical table identity");
  unsafe("Node", { ...table, ref: { ...table.ref, type: "SOURCE" } }, "conflicting object reference");
  unsafe("EvidenceRef", { ...evidence, token: "not-a-real-credential" }, "credential-bearing evidence");
  unsafe("Edge", { ...edges[2], lineStyle: "SOLID" }, "declaration drawn as observed");
  unsafe("Edge", { ...edges[4], lineStyle: "DASHED" }, "plan drawn as a declaration");
  unsafe("Edge", { ...edges[0], kind: "FEEDS_INTO" }, "observed transformation not actually collected");
  unsafe("Edge", { ...edges[3], kind: "DERIVES_FROM" }, "ambiguous reverse predicate");
  unsafe("MetricMapping", { ...command.metrics[0], inputEntityIds: [], inputFields: [] }, "metric without inputs");
  unsafe("SaveMappingsCommand", { ...command, expectedMappingVersion: -1 }, "negative mapping version");
  unsafe("SaveMappingsCommand", { ...command, actorId: "admin" }, "caller-supplied privilege");
  unsafe("SaveMappingsCommand", { ...command, entities: [{ ...command.entities[0], inputFields: [ref, ref] }] }, "duplicate field references");
  unsafe("FieldPage", { ...page, nodes: [table] }, "non-field row in field catalogue");
  unsafe("FieldPage", { ...page, edges: [edges[2]] }, "derived edge in physical field catalogue");
  unsafe("FieldPage", { ...page, limit: 129 }, "unbounded page size");
  unsafe("Coverage", { ...graph.coverage, shownFields: 257 }, "field budget exceeded");
  const dangling = structuredClone(graph); dangling.edges[0].to = "missing";
  assert.throws(() => graphMeaning(dangling), /undisplayed endpoint/); rejected++;
  const reverse = structuredClone(graph); reverse.edges[3].from = metric.id; reverse.edges[3].to = entity.id;
  assert.throws(() => graphMeaning(reverse), /later layers/); rejected++;
  const incomplete = structuredClone(graph); incomplete.coverage.totalFields = 512;
  assert.throws(() => graphMeaning(incomplete), /marked partial/); rejected++;
  const foreign = structuredClone(graph); foreign.nodes[0].sourceIds = ["foreign"];
  assert.throws(() => graphMeaning(foreign), /escaped graph scope/); rejected++;
  console.log(`Lineage compiled ${Object.keys(schema.$defs).length} definitions; fixtures passed and ${rejected} unsafe schema/consumer cases were rejected.`);
}
