export type WorkspaceViewId =
  | "overview"
  | "business"
  | "assets"
  | "findings"
  | "team"
  | "tasks"
  | "sources"
  | "settings"
  | "fulfillment";
export type SourceKind =
  "JSON_SNAPSHOT" | "HTTP_JSON" | "JDBC_POSTGRES_READONLY";
export type Provenance = "USER" | "DEMO";
export interface WorkspaceSource {
  id: string;
  name: string;
  kind: SourceKind;
  provenance: Provenance;
  status: string;
  endpointLabel?: string | null;
  lastScannedAt?: string | null;
  assetCount: number;
  credentialConfigured: boolean;
  stateVersion: number;
  lastError?: string | null;
}
export interface WorkspaceColumn {
  name: string;
  inferredType: string;
  missingCount: number;
  distinctCount: number;
}
export interface WorkspaceAsset {
  id: string;
  sourceId: string;
  name: string;
  provenance: Provenance;
  rowCount: number;
  totalRows?: number | null;
  sampleLimit: number;
  sampled: boolean;
  columns: WorkspaceColumn[];
  observedAt: string;
  evidenceSha256: string;
  sampling?: string;
}
export interface FindingEvidence {
  sha256: string;
  sampledRows: number;
  totalRows?: number | null;
  sampleLimit: number;
  observedAt: string;
  sampling: string;
}
export interface WorkspaceFinding {
  id: string;
  ruleId: string;
  title: string;
  severity: string;
  sourceId: string;
  assetId: string;
  scanId: string;
  column?: string | null;
  active: boolean;
  observed: string;
  expected: string;
  evidence: FindingEvidence;
  suggestion: string;
  provenance?: Provenance;
}
export interface WorkspaceScan {
  id: string;
  sourceId: string;
  status: string;
  startedAt: string;
  completedAt?: string | null;
  assetCount?: number;
  findingCount?: number;
  assetIds: string[];
  findingIds: string[];
  error?: string | null;
}
export interface WorkspaceAssistant {
  id: string;
  role: "OPERATIONS" | "ANALYSIS" | "DECISION";
  name: string;
  responsibility: string;
  status: string;
  method: string;
  findingIds: string[];
  taskIds: string[];
  focusGoalIds?: string[];
  scopeSourceIds?: string[];
  scopeNotice?: string;
}
export interface WorkspaceTask {
  id: string;
  assistantId: string;
  findingId: string;
  title: string;
  description: string;
  status: string;
  ownerId?: string | null;
  createdAt: string;
  updatedAt: string;
  completionNote?: string | null;
  evidenceRefs: string[];
  stateVersion: number;
  verifiedBy?: string | null;
  goalIds?: string[];
  inCurrentScope?: boolean;
  scopeReason?: string;
  businessBriefVersion?: number | null;
  createdUnderBriefVersion?: number | null;
  currentFindingId?: string | null;
  priorityRank?: number;
  priorityReason?: string;
}
export interface WorkspaceComment {
  id: string;
  taskId: string;
  actorId: string;
  body: string;
  evidenceRefs: string[];
  createdAt: string;
}
export interface WorkspaceMessage {
  id: string;
  assistantId: string;
  actorId: string;
  question: string;
  answer: string;
  evidenceRefs: string[];
  createdAt: string;
  method: string;
  status?: "SUCCEEDED" | "FAILED";
  uncertainty?: string[];
  errorCode?: string | null;
  trace?: {
    providerProfile: string;
    modelId: string;
    modelVersion: string;
    promptVersion: string;
    contextSha256: string;
    responseId?: string | null;
    inputTokens?: number | null;
    outputTokens?: number | null;
  } | null;
}
export interface WorkspaceAudit {
  id?: string;
  actorId: string;
  event: string;
  resourceId: string;
  stateVersion: number;
  occurredAt?: string;
  createdAt?: string;
  details?: unknown;
}
export interface WorkspaceState {
  stateVersion: number;
  mode: string;
  sources: WorkspaceSource[];
  assets: WorkspaceAsset[];
  scans: WorkspaceScan[];
  findings: WorkspaceFinding[];
  assistants: WorkspaceAssistant[];
  tasks: WorkspaceTask[];
  comments: WorkspaceComment[];
  messages: WorkspaceMessage[];
  audit: WorkspaceAudit[];
  businessBrief?: BusinessBrief | null;
  capabilities: {
    assistantMethod?: string;
    [key: string]: unknown;
  };
}
export type BusinessGoalType =
  | "DATA_RELIABILITY"
  | "ANALYTICS_TRUST"
  | "ORDER_FULFILLMENT"
  | "INVENTORY_ACCURACY"
  | "COST_CONTROL"
  | "CUSTOM";
export interface BusinessGoal {
  id: string;
  title: string;
  type: BusinessGoalType;
  expectedOutcome: string;
  priority: "HIGH" | "MEDIUM" | "LOW";
  sourceIds: string[];
}
export interface MetricDraft {
  id: string;
  name: string;
  businessDefinition: string;
  owner: string;
  timeZone: string;
  currency: string | null;
  formulaDescription: string;
  sourceIds: string[];
}
export interface BusinessMetric extends MetricDraft {
  status: "DRAFT" | "CONFIRMED";
  stateVersion: number;
  calculationStatus: "NOT_COMPUTED";
  confirmedBy?: string | null;
  confirmedAt?: string | null;
}
export interface BusinessBrief {
  version: number;
  enterpriseName: string;
  businessDescription: string;
  goals: BusinessGoal[];
  includedSourceIds: string[];
  metrics: BusinessMetric[];
  updatedAt: string;
  updatedBy: string;
  modelContextExportEnabled: false;
}
export interface BusinessBriefCommand {
  expectedStateVersion: number;
  expectedBriefVersion: number;
  enterpriseName: string;
  businessDescription: string;
  goals: BusinessGoal[];
  includedSourceIds: string[];
  metrics: MetricDraft[];
}
export interface SourceDraft {
  expectedStateVersion: number;
  name: string;
  kind: SourceKind;
  endpoint?: string;
  credentialEnvRef?: string;
  usernameEnvRef?: string;
  snapshotRows?: Record<string, unknown>[];
  observedAt?: string;
  primaryKey?: string;
  freshnessColumn?: string;
  freshnessMaxHours?: number;
  schema?: string;
}
export interface WorkspaceReport {
  generatedAt: string;
  stateVersion: number;
  method: string;
  title: string;
  sections: { title: string; text: string }[];
  evidenceRefs: string[];
}
