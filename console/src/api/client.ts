import {
  approveDemoAction,
  decideDemoMemory,
  demoActionEvents,
  demoCases,
  demoEvalHealth,
  demoMemories,
  getDemoCase,
  getDemoRecommendation,
  executeDemoAction,
  prepareDemoAction,
  verifyDemoAction,
} from '../data/demo'
import type {
  ActionEvent,
  ActionHydration,
  ActionReceipt,
  ActionState,
  ActionPreparationInput,
  ApprovalInput,
  ApprovalResult,
  CaseDetail,
  CaseStatus,
  CaseSummary,
  ClaimStatus,
  EvalHealth,
  ExecuteActionInput,
  ExecutionResult,
  EvidenceEvent,
  MemoryCandidate,
  MemoryDecisionInput,
  MemoryLevel,
  PreparedAction,
  Recommendation,
  RiskLevel,
  Sourced,
  VerificationOutcome,
  VerificationView,
  VerifyActionInput,
} from '../domain/types'

const API_ROOT = '/api/v1'
const FALLBACK_ENABLED = import.meta.env.VITE_ENABLE_MOCK_FALLBACK === 'true' ||
  (import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK_FALLBACK !== 'false')
const DEFAULT_REQUEST_TIMEOUT_MS = 3_000
const CASE_REQUEST_TIMEOUT_MS = 3_000
const RECOMMENDATION_REQUEST_TIMEOUT_MS = 25_000

type DemoActor = 'order-specialist' | 'warehouse-approver' | 'sales-owner' | 'auditor' | 'admin'
type InternalRequestInit = RequestInit & { demoActor?: DemoActor; timeoutMs?: number }

class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly requestId?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RawEvidenceRef {
  id: string
  sourceSystem: string
  sourceRecordId: string
  contentHash: string
  observedAt: string
  classification: string
}

interface RawClaim {
  id: string
  subjectType: string
  subjectId: string
  predicate: string
  value: string
  evidenceRefs: RawEvidenceRef[]
  validFrom: string
  validTo?: string | null
  recordedAt: string
  expiresAt?: string | null
  classification: string
  confidence: number
  status: string
}

interface RawKnowledgeGap {
  id: string
  kind: string
  subjectId: string
  descriptionZh: string
  affectedDecision: string
  requiredObservation: string
  dueAt?: string | null
}

interface RawCaseSummary {
  id: string
  orderId: string
  titleZh: string
  priority: string
  status: string
  scenarioKinds: string[]
  gapCount: number
  stateVersion: number
  updatedAt: string
}

interface RawCaseDetail extends RawCaseSummary {
  tenantId: string
  claims: RawClaim[]
  knowledgeGaps: RawKnowledgeGap[]
  domainVersion: string
  policyVersion: string
  createdAt: string
}

interface RawRecommendation {
  id: string
  caseId: string
  caseStateVersion: number
  actionDefinitionId: string
  actionInput: Record<string, unknown>
  rationaleZh: string
  evidenceIds: string[]
  createdAt: string
  domainVersion: string
  policyVersion: string
  modelVersion: string
}

interface RawActionRequest {
  id: string
  caseId: string
  recommendationId: string
  definitionId: string
  definitionVersion: string
  input: Record<string, unknown>
  requestedBy: string
  approvedBy?: string | null
  state: string
  stateVersion: number
  boundCaseStateVersion: number
  updatedAt: string
}

interface RawActionAttempt {
  number: number
  startedAt: string
  completedAt: string
  outcome: string
  messageZh: string
}

interface RawActionReceipt {
  id: string
  externalRequestId: string
  externalReceiptId?: string | null
  outcome: string
  attempts: RawActionAttempt[]
  compensationReceiptId?: string | null
  compensationVerificationId?: string | null
  verificationId?: string | null
  createdAt: string
  updatedAt: string
}

interface RawExecutionView {
  request: RawActionRequest
  receipt: RawActionReceipt
}

interface RawVerificationResult {
  id: string
  verifierId: string
  purpose?: 'ACTION' | 'COMPENSATION'
  independentSource: string
  outcome: string
  assertions: Array<{
    name: string
    expected: string
    observed: string
    passed: boolean
  }>
  evidenceRefs: RawEvidenceRef[]
  resolvedKnowledgeGapIds?: string[]
  observedFrom: string
  observedUntil: string
  antiGamingMetrics: Record<string, number>
  createdAt: string
}

interface RawVerificationView {
  request: RawActionRequest
  receipt: RawActionReceipt
  verification: RawVerificationResult
  memoryCandidate?: RawMemoryAsset | null
}

interface RawMemoryAsset {
  id: string
  level: string
  status: string
  titleZh: string
  contentZh: string
  sourceCaseIds: string[]
  sourceEvidenceIds: string[]
  validFrom: string
  expiresAt?: string | null
  ownerId: string
  aclRef: string
  modelVersion: string
  stateVersion: number
  createdAt: string
}

interface RawAuditFact {
  aggregateType: string
  aggregateId: string
  eventType: string
  actorId: string
  occurredAt: string
  details: Record<string, string>
}

interface RawEvaluationHealth {
  readiness: string
  mode: string
  datasetVersion: string
  scenarioTemplates: number
  deterministicCases: number
  enforcedInvariants: string[]
  successAuthority: string
  acceptsAgentSelfReportedSuccess: boolean
  generatedAt: string
}

function apiPath(path: string): string {
  const resolved = new URL(path, window.location.origin)
  if (resolved.origin !== window.location.origin || !resolved.pathname.startsWith(`${API_ROOT}/`)) {
    throw new Error('拒绝非同源或非 /api/v1 的请求')
  }
  return `${resolved.pathname}${resolved.search}`
}

function commandKey(scope: string, ...parts: Array<string | number>): string {
  return [scope, ...parts].join(':').slice(0, 128)
}

async function request<T>(path: string, init: InternalRequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const { demoActor, timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS, ...fetchInit } = init
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs)

  try {
    const response = await fetch(apiPath(path), {
      ...fetchInit,
      credentials: 'same-origin',
      headers: {
        Accept: 'application/json',
        ...(fetchInit.body ? { 'Content-Type': 'application/json' } : {}),
        ...((import.meta.env.DEV || import.meta.env.VITE_DEMO_AUTH === 'true') && demoActor
          ? { 'X-Demo-Actor': demoActor }
          : {}),
        ...fetchInit.headers,
      },
      signal: controller.signal,
    })

    if (!response.ok) {
      throw new ApiError(
        `接口返回 ${response.status}`,
        response.status,
        response.headers.get('x-request-id') ?? undefined,
      )
    }

    if (response.status === 204) return undefined as T
    return (await response.json()) as T
  } finally {
    window.clearTimeout(timeout)
  }
}

async function requestOptional<T>(path: string, init: InternalRequestInit = {}): Promise<T | undefined> {
  try {
    return await request<T>(path, init)
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return undefined
    throw error
  }
}

function copy<T>(value: T): T {
  return structuredClone(value)
}

async function withReadFallback<T>(live: () => Promise<T>, demo: () => T): Promise<Sourced<T>> {
  try {
    return { data: await live(), source: 'live' }
  } catch (error) {
    // A server-side denial or validation result is authoritative. Falling
    // through to fixture data could fabricate an unsafe recommendation.
    if (!FALLBACK_ENABLED || error instanceof ApiError) throw error
    return { data: copy(demo()), source: 'demo' }
  }
}

function unpackItems<T>(response: T[] | { items: T[] }): T[] {
  return Array.isArray(response) ? response : response.items
}

const scenarioLabel: Record<string, string> = {
  STALE_ERP_STATUS: 'ERP 状态滞后',
  DUPLICATE_ORDER_ID: '订单编号重复',
  INVENTORY_MISMATCH: '库存账实不符',
  PARTIAL_SHIPMENT: '部分发货',
  SUPPLIER_DELAY: '供应商延期',
  MISSING_QUALITY_INSPECTION: '缺少质检',
  ETA_CONFLICT: '预计到达时间冲突',
  AMBIGUOUS_TIMEOUT: '外部结果不确定',
}

const predicateLabel: Record<string, string> = {
  sku: 'SKU 业务标识',
  warehouseId: '履约仓库',
  warehouseCode: '履约仓库',
  orderedQuantity: '订单数量',
  availableQuantity: '可用库存',
  missingQuantity: '履约缺口数量',
  erpStatus: 'ERP 履约状态',
  eta: '预计到达时间',
  status: '质检状态',
}

const parameterLabel: Record<string, string> = {
  caseId: '案件',
  orderLineId: '订单行',
  warehouseId: '仓库',
  warehouseCode: '仓库',
  assigneeId: '责任人',
  sku: 'SKU',
  quantity: '数量',
  reasonZh: '动作原因',
}

const actionStates: ActionState[] = [
  'PROPOSED', 'POLICY_PASSED', 'AWAITING_APPROVAL', 'APPROVED', 'EXECUTING',
  'EXECUTED_UNVERIFIED', 'VERIFIED_SUCCEEDED', 'VERIFIED_FAILED', 'REJECTED',
  'EXPIRED', 'CANCELLED', 'UNKNOWN_REQUIRES_RECONCILIATION', 'COMPENSATING',
  'COMPENSATED', 'MANUAL_INTERVENTION',
]

function addHours(value: string, hours: number): string {
  const date = new Date(value)
  date.setUTCHours(date.getUTCHours() + hours)
  return date.toISOString()
}

function mapRisk(value: string): RiskLevel {
  const normalized = value.toLowerCase()
  return ['critical', 'high', 'medium', 'low'].includes(normalized) ? normalized as RiskLevel : 'medium'
}

function mapCaseStatus(value: string): CaseStatus {
  switch (value) {
    case 'AWAITING_APPROVAL': return 'awaiting_approval'
    case 'IN_PROGRESS': return 'monitoring'
    case 'VERIFIED_CLOSED': return 'closed'
    case 'MANUAL_INTERVENTION': return 'investigating'
    default: return 'open'
  }
}

function normalizeCaseSummary(raw: RawCaseSummary): CaseSummary {
  const kinds = raw.scenarioKinds.map((kind) => scenarioLabel[kind] ?? kind)
  return {
    id: raw.id,
    orderId: raw.orderId,
    customer: `业务客户 · ${raw.orderId.replace(/\D/g, '').slice(-3) || '未识别'}`,
    exceptionType: raw.titleZh.replace(/^订单履约异常[：:]\s*/, ''),
    summary: kinds.length > 0 ? `跨系统观测识别到：${kinds.join('、')}。` : '等待来源系统补充异常证据。',
    status: mapCaseStatus(raw.status),
    risk: mapRisk(raw.priority),
    owner: raw.status === 'AWAITING_APPROVAL' ? '仓库审批人' : '订单专员',
    dueAt: addHours(raw.updatedAt, 4),
    updatedAt: raw.updatedAt,
    sourceCount: 0,
    gapCount: raw.gapCount,
    tags: kinds.slice(0, 2),
  }
}

function mapClassification(value: string): EvidenceEvent['classification'] {
  if (value === 'CONFIDENTIAL') return '机密'
  if (value === 'RESTRICTED') return '受限'
  return '内部'
}

function mapClaimStatus(value: string): ClaimStatus {
  return ['ASSERTED', 'INFERRED', 'CONFLICTED', 'REVOKED'].includes(value) ? value as ClaimStatus : 'UNKNOWN'
}

function normalizeEvidence(raw: RawClaim): EvidenceEvent {
  const evidence = raw.evidenceRefs[0]
  const label = predicateLabel[raw.predicate] ?? raw.predicate
  return {
    id: evidence?.id ?? raw.id,
    title: label,
    detail: `${raw.subjectType} ${raw.subjectId} 的“${label}”观测值为 ${raw.value}。`,
    sourceSystem: evidence?.sourceSystem ?? '未知来源',
    sourceRecordId: evidence?.sourceRecordId ?? raw.subjectId,
    status: mapClaimStatus(raw.status),
    validAt: raw.validFrom,
    recordedAt: raw.recordedAt,
    expiresAt: raw.expiresAt ?? undefined,
    confidence: raw.confidence,
    classification: mapClassification(raw.classification),
    facts: [{ label, value: raw.value, changed: raw.status === 'CONFLICTED' }],
  }
}

function normalizeCaseDetail(raw: RawCaseDetail): CaseDetail {
  const summary = normalizeCaseSummary(raw)
  const sources = new Map<string, string>()
  raw.claims.flatMap((claim) => claim.evidenceRefs).forEach((item) => {
    const current = sources.get(item.sourceSystem)
    if (!current || current < item.observedAt) sources.set(item.sourceSystem, item.observedAt)
  })
  const eta = raw.claims.find((claim) => claim.predicate === 'eta')?.value ?? '未提供'
  const warehouse = raw.claims.find((claim) => claim.predicate === 'warehouseCode')?.value
    ?? raw.claims.find((claim) => claim.predicate === 'warehouseId')?.value
    ?? '未匹配'
  const dueAt = raw.knowledgeGaps.flatMap((gap) => gap.dueAt ? [gap.dueAt] : []).sort()[0]
  return {
    ...summary,
    dueAt: dueAt ?? summary.dueAt,
    sourceCount: sources.size,
    currentStage: raw.status === 'AWAITING_APPROVAL' ? '待仓库审批人处理' : raw.status === 'IN_PROGRESS' ? '执行后等待验证' : raw.status === 'VERIFIED_CLOSED' ? '独立验证已结案' : raw.status === 'MANUAL_INTERVENTION' ? '需要人工接管' : '证据调查中',
    orderValue: '源系统未提供',
    promisedAt: eta,
    scheduleLabel: '当前预计到达',
    destination: warehouse,
    stateVersion: raw.stateVersion,
    domainVersion: raw.domainVersion,
    policyVersion: raw.policyVersion,
    evidence: raw.claims.map(normalizeEvidence).sort((a, b) => b.recordedAt.localeCompare(a.recordedAt)),
    gaps: raw.knowledgeGaps.map((gap) => ({
      id: gap.id,
      kind: ['UNKNOWN', 'CONFLICTING', 'STALE'].includes(gap.kind) ? gap.kind as 'UNKNOWN' | 'CONFLICTING' | 'STALE' : 'UNKNOWN',
      title: gap.descriptionZh,
      impact: gap.affectedDecision,
      requestedObservation: gap.requiredObservation,
      dueAt: gap.dueAt ?? undefined,
    })),
    sourceHealth: [...sources.entries()].map(([source, lastObservedAt]) => ({ source, status: 'healthy', lastObservedAt })),
  }
}

const actionDefinition = {
  request_stock_recount: {
    title: '请求库存实物复核',
    risk: 'low' as RiskLevel,
    timeout: '30 分钟；结果不明确时进入人工对账',
    verifier: 'WMS 独立回读确认复核任务存在',
    relation: 'warehouse:approve_recount',
  },
  reserve_substitute_inventory: {
    title: '预留替代库存',
    risk: 'medium' as RiskLevel,
    timeout: '5 分钟；结果不明确时进入人工对账',
    verifier: 'WMS 独立回读确认预留、数量及订单绑定',
    relation: 'warehouse:approve_reservation',
  },
}

function normalizeRecommendation(raw: RawRecommendation): Recommendation {
  const definition = actionDefinition[raw.actionDefinitionId as keyof typeof actionDefinition] ?? {
    title: raw.actionDefinitionId,
    risk: 'high' as RiskLevel,
    timeout: '按领域包定义',
    verifier: '按领域包验证器独立验证',
    relation: 'action:approve',
  }
  return {
    id: raw.id,
    caseId: raw.caseId,
    title: definition.title,
    confidence: 1,
    generatedAt: raw.createdAt,
    rationale: [raw.rationaleZh],
    evidenceIds: raw.evidenceIds,
    expectedImpact: raw.actionDefinitionId === 'request_stock_recount'
      ? '建立可审计的现场复核任务；不修改库存、承诺或出库状态。'
      : '建立可补偿库存预留；不修改客户承诺、价格或正式出库状态。',
    caveat: '动作请求仍须通过状态版本、角色与策略校验；审批不代表外部执行或验证成功。',
    proposedAction: {
      actionDefinitionId: raw.actionDefinitionId,
      version: '1.0.0',
      displayName: definition.title,
      risk: definition.risk,
      state: 'PROPOSED',
      parameters: Object.entries(raw.actionInput).map(([label, value]) => ({
        label: parameterLabel[label] ?? label,
        value: typeof value === 'string' ? value : JSON.stringify(value),
      })),
      requiredRelation: definition.relation,
      idempotencyKey: `act:${raw.caseId}:${raw.id}`.slice(0, 128),
      timeout: definition.timeout,
      verifier: definition.verifier,
      compensator: raw.actionDefinitionId === 'reserve_substitute_inventory' ? 'release_reservation' : undefined,
    },
  }
}

function normalizePreparedAction(raw: RawActionRequest): PreparedAction {
  return {
    id: raw.id,
    caseId: raw.caseId,
    definitionId: raw.definitionId,
    state: actionStates.includes(raw.state as ActionState) ? raw.state as ActionState : 'MANUAL_INTERVENTION',
    stateVersion: raw.stateVersion,
    boundCaseStateVersion: raw.boundCaseStateVersion,
    requestedBy: raw.requestedBy,
    approvedBy: raw.approvedBy ?? undefined,
    updatedAt: raw.updatedAt,
  }
}

function normalizeReceipt(raw: RawActionReceipt): ActionReceipt {
  const outcomes: ActionReceipt['outcome'][] = ['SUCCEEDED', 'FAILED', 'UNKNOWN', 'COMPENSATED']
  const outcome = outcomes.includes(raw.outcome as ActionReceipt['outcome']) ? raw.outcome as ActionReceipt['outcome'] : 'UNKNOWN'
  return {
    id: raw.id,
    externalRequestId: raw.externalRequestId,
    externalReceiptId: raw.externalReceiptId ?? undefined,
    outcome,
    attempts: raw.attempts.map((attempt) => ({
      number: attempt.number,
      startedAt: attempt.startedAt,
      completedAt: attempt.completedAt,
      outcome: outcomes.includes(attempt.outcome as ActionReceipt['outcome']) ? attempt.outcome as ActionReceipt['outcome'] : 'UNKNOWN',
      message: attempt.messageZh,
    })),
    compensationReceiptId: raw.compensationReceiptId ?? undefined,
    compensationVerificationId: raw.compensationVerificationId ?? undefined,
    verificationId: raw.verificationId ?? undefined,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  }
}

function normalizeExecution(raw: RawExecutionView): ExecutionResult {
  return { action: normalizePreparedAction(raw.request), receipt: normalizeReceipt(raw.receipt) }
}

function normalizeVerificationResult(raw: RawVerificationResult): VerificationView['verification'] {
  const outcomes: VerificationOutcome[] = ['PASSED', 'FAILED', 'INCONCLUSIVE']
  return {
    id: raw.id,
    verifierId: raw.verifierId,
    purpose: raw.purpose === 'COMPENSATION' ? 'COMPENSATION' : 'ACTION',
    independentSource: raw.independentSource,
    outcome: outcomes.includes(raw.outcome as VerificationOutcome)
      ? raw.outcome as VerificationOutcome
      : 'INCONCLUSIVE',
    assertions: raw.assertions,
    evidenceIds: raw.evidenceRefs.map((item) => item.id),
    resolvedKnowledgeGapIds: raw.resolvedKnowledgeGapIds ?? [],
    observedFrom: raw.observedFrom,
    observedUntil: raw.observedUntil,
    antiGamingMetrics: raw.antiGamingMetrics,
    createdAt: raw.createdAt,
  }
}

function normalizeVerification(raw: RawVerificationView): VerificationView {
  return {
    action: normalizePreparedAction(raw.request),
    receipt: normalizeReceipt(raw.receipt),
    verification: normalizeVerificationResult(raw.verification),
    memoryCandidate: raw.memoryCandidate ? normalizeMemory(raw.memoryCandidate) : undefined,
  }
}

function normalizeMemory(raw: RawMemoryAsset): MemoryCandidate {
  const levelMap: Record<string, MemoryLevel> = {
    L1_ATOMIC_CANDIDATE: 'L1',
    L2_SCENARIO_PATTERN: 'L2',
    L3_APPROVED_PLAYBOOK: 'L3',
  }
  return {
    id: raw.id,
    level: levelMap[raw.level] ?? 'L1',
    title: raw.titleZh,
    summary: raw.contentZh,
    status: ['PROPOSED', 'APPROVED', 'SUPERSEDED', 'REVOKED'].includes(raw.status) ? raw.status as MemoryCandidate['status'] : 'PROPOSED',
    owner: raw.ownerId,
    sourceCases: raw.sourceCaseIds,
    evidenceCount: raw.sourceEvidenceIds.length,
    createdAt: raw.createdAt,
    validUntil: raw.expiresAt ?? undefined,
    confidence: 1,
    impactScope: raw.aclRef,
    modelVersion: raw.modelVersion,
    stateVersion: raw.stateVersion,
  }
}

function normalizeAudit(raw: RawAuditFact, index: number, total: number): ActionEvent {
  const labels: Record<string, string> = {
    'recommendation.created': '类型化建议已生成',
    'action.awaiting_approval': '等待人工审批',
    'action.approved': '人工审批通过',
    'action.rejected': '动作已驳回',
    'action.executed_unverified': '已执行，等待验证',
    'action.execution_unknown': '外部结果不确定',
    'verification.passed': '独立验证通过',
    'verification.failed': '独立验证失败',
    'verification.inconclusive': '验证无法判断 · 人工对账',
  }
  const isFailure = ['action.execution_unknown', 'verification.failed', 'verification.inconclusive'].includes(raw.eventType)
  return {
    id: `audit-${index}-${raw.occurredAt}`,
    label: labels[raw.eventType] ?? raw.eventType,
    detail: Object.entries(raw.details).map(([key, value]) => `${key}: ${value}`).join(' · ') || '状态变化已写入不可变审计链。',
    at: raw.occurredAt,
    actor: raw.actorId,
    status: isFailure ? 'failed' : index === total - 1 && !raw.eventType.startsWith('verification.') ? 'current' : 'complete',
    receiptId: raw.details.receiptId,
  }
}

function normalizeEvalHealth(raw: RawEvaluationHealth): EvalHealth {
  const ready = raw.readiness === 'READY'
  return {
    lastUpdatedAt: raw.generatedAt,
    activeDataset: raw.datasetVersion,
    overallStatus: ready && !raw.acceptsAgentSelfReportedSuccess ? 'healthy' : 'warning',
    metrics: [
      { id: 'deterministic-cases', label: '确定性仿真案件', value: raw.deterministicCases, unit: 'count', target: 500, direction: 'at_least', trend: [raw.deterministicCases], sampleSize: raw.deterministicCases },
      { id: 'scenario-templates', label: '故障场景模板', value: raw.scenarioTemplates, unit: 'count', target: 30, direction: 'at_least', trend: [raw.scenarioTemplates], sampleSize: raw.scenarioTemplates },
      { id: 'self-report', label: 'Agent 自述成功采信', value: raw.acceptsAgentSelfReportedSuccess ? 1 : 0, unit: 'count', target: 0, direction: 'equal', trend: [raw.acceptsAgentSelfReportedSuccess ? 1 : 0], sampleSize: 1 },
    ],
    runs: [{
      id: `SIM-${raw.generatedAt.slice(0, 10)}`,
      dataset: raw.datasetVersion,
      frozenBundle: `${raw.mode} · ${raw.enforcedInvariants.length} 项强制不变量`,
      status: ready ? 'passed' : 'warning',
      startedAt: raw.generatedAt,
      cases: raw.deterministicCases,
    }],
    providers: [{
      name: '独立验证权威',
      location: raw.successAuthority,
      status: ready ? 'healthy' : 'degraded',
      latencyMs: 0,
    }],
  }
}

export const smartDataClient = {
  listCases(): Promise<Sourced<CaseSummary[]>> {
    return withReadFallback(
      async () => {
        const response = await request<RawCaseSummary[] | { items: RawCaseSummary[] }>(`${API_ROOT}/cases?offset=0&limit=50`, { demoActor: 'order-specialist' })
        return unpackItems(response).map(normalizeCaseSummary)
      },
      () => demoCases,
    )
  },

  getCase(caseId: string): Promise<Sourced<CaseDetail>> {
    return withReadFallback(
      async () => normalizeCaseDetail(await request<RawCaseDetail>(`${API_ROOT}/cases/${encodeURIComponent(caseId)}`, {
        demoActor: 'order-specialist',
        timeoutMs: CASE_REQUEST_TIMEOUT_MS,
      })),
      () => getDemoCase(caseId),
    )
  },

  getRecommendation(caseId: string): Promise<Sourced<Recommendation>> {
    return withReadFallback(
      async () => normalizeRecommendation(await request<RawRecommendation>(`${API_ROOT}/cases/${encodeURIComponent(caseId)}/recommendations`, {
        method: 'POST',
        demoActor: 'order-specialist',
        timeoutMs: RECOMMENDATION_REQUEST_TIMEOUT_MS,
        headers: { 'Idempotency-Key': commandKey('recommend', caseId) },
      })),
      () => getDemoRecommendation(caseId),
    )
  },

  getActionTimeline(caseId: string): Promise<Sourced<ActionEvent[]>> {
    return withReadFallback(
      async () => {
        const facts = await request<RawAuditFact[]>(`${API_ROOT}/cases/${encodeURIComponent(caseId)}/timeline`, { demoActor: 'auditor' })
        if (facts.length === 0) {
          return [{
            id: `case-${caseId}-observed`,
            label: '证据已汇聚',
            detail: '当前案件尚未创建动作请求；建议与动作审计将使用各自聚合标识记录。',
            actor: 'SmartData Kernel',
            status: 'current' as const,
          }]
        }
        return facts.map((fact, index) => normalizeAudit(fact, index, facts.length))
      },
      () => demoActionEvents,
    )
  },

  getActionHydration(caseId: string): Promise<Sourced<ActionHydration>> {
    return withReadFallback(
      async () => {
        const facts = await request<RawAuditFact[]>(`${API_ROOT}/cases/${encodeURIComponent(caseId)}/timeline`, { demoActor: 'auditor' })
        const actionFact = facts.findLast((fact) => fact.aggregateType === 'actionRequest')
        if (!actionFact) return {}

        const rawAction = await request<RawActionRequest>(`${API_ROOT}/action-requests/${encodeURIComponent(actionFact.aggregateId)}`, { demoActor: 'auditor' })
        const action = normalizePreparedAction(rawAction)
        if (['PROPOSED', 'POLICY_PASSED', 'AWAITING_APPROVAL', 'APPROVED', 'EXECUTING', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(action.state)) {
          return { action }
        }

        const rawReceipt = await requestOptional<RawActionReceipt>(`${API_ROOT}/action-requests/${encodeURIComponent(action.id)}/receipt`, { demoActor: 'auditor' })
        if (!rawReceipt) return { action }
        const execution = { action, receipt: normalizeReceipt(rawReceipt) }

        const rawVerification = rawReceipt.verificationId
          ? await requestOptional<RawVerificationResult>(`${API_ROOT}/verifications/${encodeURIComponent(action.id)}`, { demoActor: 'auditor' })
          : undefined
        if (!rawVerification) return { action, execution }

        const rawMemories = rawVerification.outcome === 'PASSED'
          ? await request<RawMemoryAsset[]>(`${API_ROOT}/memory-candidates`, { demoActor: 'sales-owner' })
          : []
        const memoryCandidate = rawMemories.find((candidate) => candidate.sourceCaseIds.includes(caseId))
        return {
          action,
          execution,
          verification: {
            action,
            receipt: execution.receipt,
            verification: normalizeVerificationResult(rawVerification),
            memoryCandidate: memoryCandidate ? normalizeMemory(memoryCandidate) : undefined,
          },
        }
      },
      () => ({}),
    )
  },

  listMemoryCandidates(): Promise<Sourced<MemoryCandidate[]>> {
    return withReadFallback(
      async () => (await request<RawMemoryAsset[]>(`${API_ROOT}/memory-candidates`, { demoActor: 'sales-owner' })).map(normalizeMemory),
      () => demoMemories,
    )
  },

  getEvalHealth(): Promise<Sourced<EvalHealth>> {
    return withReadFallback(
      async () => normalizeEvalHealth(await request<RawEvaluationHealth>(`${API_ROOT}/evaluations/health`, { demoActor: 'auditor' })),
      () => demoEvalHealth,
    )
  },

  async prepareAction(input: ActionPreparationInput): Promise<Sourced<PreparedAction>> {
    if (input.dataSource === 'demo') return { data: prepareDemoAction(input), source: 'demo' }
    const raw = await request<RawActionRequest>(`${API_ROOT}/action-requests`, {
      method: 'POST',
      demoActor: 'order-specialist',
      headers: { 'Idempotency-Key': input.idempotencyKey },
      body: JSON.stringify({ recommendationId: input.recommendationId, expectedStateVersion: input.expectedCaseStateVersion }),
    })
    return { data: normalizePreparedAction(raw), source: 'live' }
  },

  async decideAction(input: ApprovalInput): Promise<Sourced<ApprovalResult>> {
    if (input.dataSource === 'demo') return { data: approveDemoAction(input), source: 'demo' }
    const suffix = input.decision === 'APPROVE' ? '' : '/reject'
    const raw = await request<RawActionRequest>(`${API_ROOT}/approvals/${encodeURIComponent(input.actionRequestId)}${suffix}`, {
      method: 'POST',
      demoActor: 'warehouse-approver',
      headers: { 'Idempotency-Key': commandKey(input.decision === 'APPROVE' ? 'approve' : 'reject', input.actionRequestId, `v${input.expectedActionStateVersion}`) },
      body: JSON.stringify({ expectedStateVersion: input.expectedActionStateVersion }),
    })
    return {
      source: 'live',
      data: {
        approvalId: `approval:${raw.id}:v${raw.stateVersion}`,
        actionRequestId: raw.id,
        state: raw.state === 'APPROVED' ? 'APPROVED' : 'REJECTED',
        stateVersion: raw.stateVersion,
        acceptedAt: raw.updatedAt,
      },
    }
  },

  async executeAction(input: ExecuteActionInput): Promise<Sourced<ExecutionResult>> {
    if (input.dataSource === 'demo') return { data: executeDemoAction(input), source: 'demo' }
    const raw = await request<RawExecutionView>(`${API_ROOT}/action-requests/${encodeURIComponent(input.actionRequestId)}/execute`, {
      method: 'POST',
      demoActor: 'warehouse-approver',
      headers: { 'Idempotency-Key': commandKey('execute', input.actionRequestId, `v${input.expectedActionStateVersion}`) },
      body: JSON.stringify({ expectedStateVersion: input.expectedActionStateVersion }),
    })
    return { data: normalizeExecution(raw), source: 'live' }
  },

  async verifyAction(input: VerifyActionInput): Promise<Sourced<VerificationView>> {
    if (input.dataSource === 'demo') return { data: verifyDemoAction(input), source: 'demo' }
    const raw = await request<RawVerificationView>(`${API_ROOT}/verifications/${encodeURIComponent(input.actionRequestId)}`, {
      method: 'POST',
      demoActor: 'auditor',
      headers: { 'Idempotency-Key': commandKey('verify', input.actionRequestId, `a${input.execution.action.stateVersion}-c${input.expectedCaseStateVersion}`) },
      body: JSON.stringify({
        expectedActionStateVersion: input.execution.action.stateVersion,
        expectedCaseStateVersion: input.expectedCaseStateVersion,
      }),
    })
    return { data: normalizeVerification(raw), source: 'live' }
  },

  async decideMemory(input: MemoryDecisionInput): Promise<Sourced<MemoryCandidate>> {
    if (input.dataSource === 'demo') return { data: decideDemoMemory(input), source: 'demo' }
    const raw = await request<RawMemoryAsset>(`${API_ROOT}/memory-candidates/${encodeURIComponent(input.candidateId)}/review`, {
      method: 'POST',
      demoActor: 'sales-owner',
      headers: { 'Idempotency-Key': commandKey('memory-review', input.candidateId, input.decision, `v${input.expectedStateVersion}`) },
      body: JSON.stringify({
        decision: input.decision === 'APPROVE' ? 'APPROVE' : 'REVOKE',
        expectedStateVersion: input.expectedStateVersion,
      }),
    })
    return { data: normalizeMemory(raw), source: 'live' }
  },
}

export { ApiError }
