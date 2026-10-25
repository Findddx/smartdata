export type DataSource = 'live' | 'demo'

export interface Sourced<T> {
  data: T
  source: DataSource
}

export type RiskLevel = 'critical' | 'high' | 'medium' | 'low'
export type CaseStatus = 'open' | 'investigating' | 'awaiting_approval' | 'monitoring' | 'closed'
export type ClaimStatus = 'ASSERTED' | 'INFERRED' | 'CONFLICTED' | 'STALE' | 'UNKNOWN' | 'REVOKED'

export interface CaseSummary {
  id: string
  orderId: string
  customer: string
  exceptionType: string
  summary: string
  status: CaseStatus
  risk: RiskLevel
  owner: string
  dueAt: string
  updatedAt: string
  sourceCount: number
  gapCount: number
  tags: string[]
}

export interface EvidenceFact {
  label: string
  value: string
  changed?: boolean
}

export interface EvidenceEvent {
  id: string
  title: string
  detail: string
  sourceSystem: string
  sourceRecordId: string
  status: ClaimStatus
  validAt: string
  recordedAt: string
  expiresAt?: string
  confidence: number
  classification: '内部' | '受限' | '机密'
  facts: EvidenceFact[]
}

export interface KnowledgeGap {
  id: string
  kind: 'UNKNOWN' | 'CONFLICTING' | 'STALE'
  title: string
  impact: string
  requestedObservation: string
  dueAt?: string
}

export interface SourceHealth {
  source: string
  status: 'healthy' | 'delayed' | 'unknown'
  lastObservedAt: string
}

export interface CaseDetail extends CaseSummary {
  currentStage: string
  orderValue: string
  promisedAt: string
  scheduleLabel: string
  destination: string
  stateVersion: number
  domainVersion: string
  policyVersion: string
  evidence: EvidenceEvent[]
  gaps: KnowledgeGap[]
  sourceHealth: SourceHealth[]
}

export interface ActionParameter {
  label: string
  value: string
}

export interface ProposedAction {
  actionDefinitionId: string
  version: string
  displayName: string
  risk: RiskLevel
  state: 'AWAITING_APPROVAL' | 'POLICY_PASSED' | 'PROPOSED'
  parameters: ActionParameter[]
  requiredRelation: string
  idempotencyKey: string
  timeout: string
  verifier: string
  compensator?: string
}

export interface Recommendation {
  id: string
  caseId: string
  title: string
  confidence: number
  generatedAt: string
  rationale: string[]
  evidenceIds: string[]
  expectedImpact: string
  caveat: string
  proposedAction: ProposedAction
}

export type ActionEventStatus = 'complete' | 'current' | 'pending' | 'failed'

export type ActionState =
  | 'PROPOSED'
  | 'POLICY_PASSED'
  | 'AWAITING_APPROVAL'
  | 'APPROVED'
  | 'EXECUTING'
  | 'EXECUTED_UNVERIFIED'
  | 'VERIFIED_SUCCEEDED'
  | 'VERIFIED_FAILED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'UNKNOWN_REQUIRES_RECONCILIATION'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'MANUAL_INTERVENTION'

export type ReceiptOutcome = 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'COMPENSATED'
export type VerificationOutcome = 'PASSED' | 'FAILED' | 'INCONCLUSIVE'

export interface ActionEvent {
  id: string
  label: string
  detail: string
  at?: string
  actor: string
  status: ActionEventStatus
  receiptId?: string
}

export type MemoryLevel = 'L1' | 'L2' | 'L3'
export type MemoryStatus = 'PROPOSED' | 'APPROVED' | 'SUPERSEDED' | 'REVOKED'

export interface MemoryCandidate {
  id: string
  level: MemoryLevel
  title: string
  summary: string
  status: MemoryStatus
  owner: string
  sourceCases: string[]
  evidenceCount: number
  createdAt: string
  validUntil?: string
  confidence: number
  impactScope: string
  modelVersion: string
  stateVersion: number
}

export interface EvalMetric {
  id: string
  label: string
  value: number
  unit: '%' | 'ms' | 'count'
  target: number
  direction: 'at_least' | 'at_most' | 'equal'
  trend: number[]
  sampleSize: number
}

export interface EvalRun {
  id: string
  dataset: string
  frozenBundle: string
  status: 'passed' | 'warning' | 'failed' | 'running'
  startedAt: string
  cases: number
}

export interface EvalHealth {
  lastUpdatedAt: string
  activeDataset: string
  overallStatus: 'healthy' | 'warning' | 'failed'
  metrics: EvalMetric[]
  runs: EvalRun[]
  providers: Array<{
    name: string
    location: string
    status: 'healthy' | 'degraded' | 'offline'
    latencyMs: number
  }>
}

export interface ApprovalInput {
  caseId: string
  actionRequestId: string
  expectedActionStateVersion: number
  decision: 'APPROVE' | 'REJECT'
  comment: string
  dataSource: DataSource
}

export interface ActionPreparationInput {
  caseId: string
  recommendationId: string
  expectedCaseStateVersion: number
  idempotencyKey: string
  dataSource: DataSource
}

export interface PreparedAction {
  id: string
  caseId: string
  definitionId: string
  state: ActionState
  stateVersion: number
  boundCaseStateVersion: number
  requestedBy: string
  approvedBy?: string
  updatedAt?: string
}

export interface ApprovalResult {
  approvalId: string
  actionRequestId: string
  state: 'APPROVED' | 'REJECTED'
  stateVersion: number
  acceptedAt: string
}

export interface ActionAttempt {
  number: number
  startedAt: string
  completedAt: string
  outcome: ReceiptOutcome
  message: string
}

export interface ActionReceipt {
  id: string
  externalRequestId: string
  externalReceiptId?: string
  outcome: ReceiptOutcome
  attempts: ActionAttempt[]
  compensationReceiptId?: string
  compensationVerificationId?: string
  verificationId?: string
  createdAt: string
  updatedAt: string
}

export interface ExecutionResult {
  action: PreparedAction
  receipt: ActionReceipt
}

export interface VerificationAssertion {
  name: string
  expected: string
  observed: string
  passed: boolean
}

export interface VerificationResult {
  id: string
  verifierId: string
  purpose: 'ACTION' | 'COMPENSATION'
  independentSource: string
  outcome: VerificationOutcome
  assertions: VerificationAssertion[]
  evidenceIds: string[]
  resolvedKnowledgeGapIds: string[]
  observedFrom: string
  observedUntil: string
  antiGamingMetrics: Record<string, number>
  createdAt: string
}

export interface VerificationView {
  action: PreparedAction
  receipt: ActionReceipt
  verification: VerificationResult
  memoryCandidate?: MemoryCandidate
}

export interface ActionHydration {
  action?: PreparedAction
  execution?: ExecutionResult
  verification?: VerificationView
}

export interface ExecuteActionInput {
  caseId: string
  actionRequestId: string
  expectedActionStateVersion: number
  dataSource: DataSource
}

export interface VerifyActionInput {
  caseId: string
  actionRequestId: string
  expectedCaseStateVersion: number
  dataSource: DataSource
  execution: ExecutionResult
}

export interface MemoryDecisionInput {
  candidateId: string
  decision: 'APPROVE' | 'REJECT'
  comment: string
  expectedStateVersion: number
  dataSource: DataSource
}
