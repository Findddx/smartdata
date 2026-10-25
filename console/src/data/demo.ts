import type {
  ActionEvent,
  ActionPreparationInput,
  ApprovalInput,
  ApprovalResult,
  CaseDetail,
  CaseSummary,
  EvalHealth,
  ExecuteActionInput,
  ExecutionResult,
  MemoryCandidate,
  MemoryDecisionInput,
  PreparedAction,
  Recommendation,
  VerificationView,
  VerifyActionInput,
} from '../domain/types'

export const demoCases: CaseSummary[] = [
  {
    id: 'EXC-260907-184',
    orderId: 'SO-2026-08319',
    customer: '华域精密制造',
    exceptionType: '库存承诺冲突',
    summary: 'ERP 可用量与 WMS 实物复核相差 240 件，影响今日出库。',
    status: 'awaiting_approval',
    risk: 'critical',
    owner: '周岚',
    dueAt: '2026-09-07T11:30:00+08:00',
    updatedAt: '2026-09-07T09:42:00+08:00',
    sourceCount: 4,
    gapCount: 2,
    tags: ['华东仓', 'SLA 1h48m'],
  },
  {
    id: 'EXC-260907-177',
    orderId: 'SO-2026-08287',
    customer: '锐驰工业设备',
    exceptionType: '运输到达冲突',
    summary: '承运商预计到达晚于客户承诺 9 小时，GPS 最近观测已过期。',
    status: 'investigating',
    risk: 'high',
    owner: '叶青',
    dueAt: '2026-09-07T14:00:00+08:00',
    updatedAt: '2026-09-07T09:31:00+08:00',
    sourceCount: 3,
    gapCount: 1,
    tags: ['华南线', '待补观测'],
  },
  {
    id: 'EXC-260907-169',
    orderId: 'SO-2026-08252',
    customer: '博岳自动化',
    exceptionType: '质检证据缺失',
    summary: '批次已进入待出库，但 QMS 未返回最终放行记录。',
    status: 'open',
    risk: 'high',
    owner: '待分派',
    dueAt: '2026-09-07T16:30:00+08:00',
    updatedAt: '2026-09-07T09:14:00+08:00',
    sourceCount: 3,
    gapCount: 2,
    tags: ['质检', '受限证据'],
  },
  {
    id: 'EXC-260907-158',
    orderId: 'SO-2026-08198',
    customer: '清河能源科技',
    exceptionType: '部分发货风险',
    summary: '订单一行缺货 36 件，其余两行可按承诺时间发运。',
    status: 'monitoring',
    risk: 'medium',
    owner: '蒋闻',
    dueAt: '2026-09-08T10:00:00+08:00',
    updatedAt: '2026-09-07T08:57:00+08:00',
    sourceCount: 4,
    gapCount: 0,
    tags: ['分批方案', '监控中'],
  },
  {
    id: 'EXC-260907-144',
    orderId: 'SO-2026-08143',
    customer: '长川机电',
    exceptionType: '供应商延期',
    summary: '关键组件到货时间后移 18 小时，替代料库存待确认。',
    status: 'investigating',
    risk: 'medium',
    owner: '谢然',
    dueAt: '2026-09-08T15:00:00+08:00',
    updatedAt: '2026-09-07T08:40:00+08:00',
    sourceCount: 2,
    gapCount: 1,
    tags: ['供应商', '替代料'],
  },
  {
    id: 'EXC-260906-392',
    orderId: 'SO-2026-08076',
    customer: '允新材料',
    exceptionType: '重复业务编号',
    summary: '两个 WMS 预留记录引用同一 ERP 订单行，已冻结写操作。',
    status: 'open',
    risk: 'low',
    owner: '林渡',
    dueAt: '2026-09-09T09:00:00+08:00',
    updatedAt: '2026-09-07T08:22:00+08:00',
    sourceCount: 2,
    gapCount: 1,
    tags: ['身份映射', '已冻结'],
  },
]

const primaryDetail: CaseDetail = {
  ...demoCases[0]!,
  currentStage: '待仓库主管审批',
  orderValue: '¥ 684,000',
  promisedAt: '2026-09-07T18:00:00+08:00',
  scheduleLabel: '客户承诺',
  destination: '江苏 · 苏州工厂',
  stateVersion: 17,
  domainVersion: 'fulfillment-cn@1.3.0',
  policyVersion: 'warehouse-write@2026.09.1',
  sourceHealth: [
    { source: 'ERP', status: 'healthy', lastObservedAt: '2026-09-07T09:38:00+08:00' },
    { source: 'WMS', status: 'healthy', lastObservedAt: '2026-09-07T09:41:00+08:00' },
    { source: 'TMS', status: 'delayed', lastObservedAt: '2026-09-07T08:54:00+08:00' },
    { source: 'QMS', status: 'unknown', lastObservedAt: '2026-09-06T17:12:00+08:00' },
  ],
  gaps: [
    {
      id: 'GAP-184-01',
      kind: 'CONFLICTING',
      title: 'SKU AX-440 可用量冲突',
      impact: '无法确认本单 480 件是否可完整出库。',
      requestedObservation: '对华东仓 A-17 库位执行实物复核。',
      dueAt: '2026-09-07T10:15:00+08:00',
    },
    {
      id: 'GAP-184-02',
      kind: 'STALE',
      title: 'TMS 提货窗口已过期',
      impact: '替代库存获批后，当前车辆窗口可能无法满足承诺时间。',
      requestedObservation: '刷新承运商提货能力与预计到达时间。',
      dueAt: '2026-09-07T10:30:00+08:00',
    },
  ],
  evidence: [
    {
      id: 'EV-184-06',
      title: '实物复核发现短缺',
      detail: 'A-17 库位扫描与人工点数一致，实存 240 件；与系统可用量相差 240 件。',
      sourceSystem: '人工复核',
      sourceRecordId: 'COUNT-20260907-044',
      status: 'CONFLICTED',
      validAt: '2026-09-07T09:35:00+08:00',
      recordedAt: '2026-09-07T09:41:12+08:00',
      confidence: 0.99,
      classification: '受限',
      facts: [
        { label: '实存', value: '240 件', changed: true },
        { label: '账面可用', value: '480 件' },
      ],
    },
    {
      id: 'EV-184-05',
      title: '替代库位存在可调拨库存',
      detail: '华东仓 B-03 库位显示同 SKU 可用 312 件，尚未形成预留。',
      sourceSystem: 'WMS',
      sourceRecordId: 'INV-B03-AX440',
      status: 'ASSERTED',
      validAt: '2026-09-07T09:28:00+08:00',
      recordedAt: '2026-09-07T09:29:03+08:00',
      expiresAt: '2026-09-07T10:28:00+08:00',
      confidence: 0.96,
      classification: '内部',
      facts: [
        { label: '可用', value: '312 件' },
        { label: '库位', value: 'B-03' },
      ],
    },
    {
      id: 'EV-184-04',
      title: 'WMS 账面仍显示完整库存',
      detail: '库存快照显示 A-17 库位可用 480 件，快照时间早于最近一次人工移库。',
      sourceSystem: 'WMS',
      sourceRecordId: 'INV-A17-AX440',
      status: 'STALE',
      validAt: '2026-09-07T08:52:00+08:00',
      recordedAt: '2026-09-07T09:06:21+08:00',
      expiresAt: '2026-09-07T09:22:00+08:00',
      confidence: 0.72,
      classification: '内部',
      facts: [
        { label: '可用', value: '480 件' },
        { label: '快照延迟', value: '14 分钟', changed: true },
      ],
    },
    {
      id: 'EV-184-03',
      title: '提货窗口未重新确认',
      detail: '承运商原计划 15:10 到仓；该观测距今 48 分钟，超出 30 分钟有效期。',
      sourceSystem: 'TMS',
      sourceRecordId: 'LOAD-CN32-907',
      status: 'STALE',
      validAt: '2026-09-07T08:54:00+08:00',
      recordedAt: '2026-09-07T08:55:08+08:00',
      expiresAt: '2026-09-07T09:24:00+08:00',
      confidence: 0.65,
      classification: '内部',
      facts: [
        { label: '到仓', value: '15:10' },
        { label: '观测状态', value: '已过期', changed: true },
      ],
    },
    {
      id: 'EV-184-02',
      title: '订单承诺已锁定',
      detail: '客户承诺为今日 18:00 前完成发运，订单行 AX-440 数量 480 件。',
      sourceSystem: 'ERP',
      sourceRecordId: 'SO-2026-08319/L20',
      status: 'ASSERTED',
      validAt: '2026-09-06T16:20:00+08:00',
      recordedAt: '2026-09-06T16:20:14+08:00',
      confidence: 1,
      classification: '内部',
      facts: [
        { label: '承诺', value: '09-07 18:00' },
        { label: '数量', value: '480 件' },
      ],
    },
    {
      id: 'EV-184-01',
      title: '最终质检放行状态未知',
      detail: '未获取替代库存批次的最终质检放行记录，预留后仍需验证。',
      sourceSystem: 'QMS',
      sourceRecordId: '待匹配批次',
      status: 'UNKNOWN',
      validAt: '2026-09-07T09:42:00+08:00',
      recordedAt: '2026-09-07T09:42:02+08:00',
      confidence: 1,
      classification: '受限',
      facts: [
        { label: '放行状态', value: '未知', changed: true },
      ],
    },
  ],
}

const recommendation: Recommendation = {
  id: 'REC-184-03',
  caseId: primaryDetail.id,
  title: '预留 B-03 替代库存 240 件',
  confidence: 0.87,
  generatedAt: '2026-09-07T09:42:18+08:00',
  rationale: [
    'A-17 实物复核仅 240 件，原库位无法满足订单行数量。',
    'B-03 当前可用 312 件，可覆盖 240 件缺口并保留 72 件缓冲。',
    '动作可逆；预留后由独立库存快照验证数量与占用关系。',
  ],
  evidenceIds: ['EV-184-06', 'EV-184-05', 'EV-184-02'],
  expectedImpact: '恢复完整发货条件；不修改客户承诺、价格或正式出库状态。',
  caveat: 'TMS 提货窗口和替代批次质检仍需刷新，动作成功不代表订单已完成履约。',
  proposedAction: {
    actionDefinitionId: 'reserve_substitute_inventory',
    version: '1.2.0',
    displayName: '预留替代库存',
    risk: 'medium',
    state: 'AWAITING_APPROVAL',
    parameters: [
      { label: 'SKU', value: 'AX-440' },
      { label: '来源库位', value: 'B-03' },
      { label: '数量', value: '240 件' },
      { label: '订单行', value: 'SO-2026-08319/L20' },
    ],
    requiredRelation: 'warehouse:approve_reservation',
    idempotencyKey: 'act:EXC-260907-184:reserve:B03:240:v17',
    timeout: '30 秒；结果不明确时进入人工对账',
    verifier: 'WMS 独立快照确认 reservationId、数量及订单行绑定',
    compensator: 'release_reservation',
  },
}

export const demoActionEvents: ActionEvent[] = [
  {
    id: 'AE-01',
    label: '异常案件建立',
    detail: '库存承诺规则命中；冻结高风险自动写入。',
    at: '2026-09-07T09:07:04+08:00',
    actor: 'fulfillment-rule@1.3.0',
    status: 'complete',
  },
  {
    id: 'AE-02',
    label: '补充观测完成',
    detail: '仓库人员提交 A-17 实物复核结果。',
    at: '2026-09-07T09:41:12+08:00',
    actor: '陈砚 · 仓库专员',
    status: 'complete',
    receiptId: 'COUNT-20260907-044',
  },
  {
    id: 'AE-03',
    label: '策略校验通过',
    detail: '租户、关系权限、风险上限与数据出口策略均通过。',
    at: '2026-09-07T09:42:19+08:00',
    actor: 'OPA + OpenFGA',
    status: 'complete',
    receiptId: 'POL-7C2A19',
  },
  {
    id: 'AE-04',
    label: '等待人工审批',
    detail: '需要 warehouse:approve_reservation 关系权限。',
    at: '2026-09-07T09:42:20+08:00',
    actor: '周岚 · 订单专员',
    status: 'current',
  },
  {
    id: 'AE-05',
    label: '执行并获取外部回执',
    detail: '审批通过后由 Connector Node 调用 WMS。',
    actor: 'WMS Connector',
    status: 'pending',
  },
  {
    id: 'AE-06',
    label: '独立验证',
    detail: '重新读取库存快照；执行器自述不能作为成功依据。',
    actor: 'inventory-reservation-verifier@1.1',
    status: 'pending',
  },
]

export const demoMemories: MemoryCandidate[] = [
  {
    id: 'MEM-2026-0912',
    level: 'L2',
    title: '账实冲突时优先验证可逆替代预留',
    summary: '华东仓同 SKU 出现账实冲突且订单 SLA 小于 4 小时时，先核验替代库位及质检状态，再建议可补偿预留。',
    status: 'PROPOSED',
    owner: '待指定',
    sourceCases: ['EXC-260831-102', 'EXC-260902-228', 'EXC-260907-184'],
    evidenceCount: 21,
    createdAt: '2026-09-07T09:48:00+08:00',
    validUntil: '2026-12-06T23:59:59+08:00',
    confidence: 0.84,
    impactScope: '华东仓 · 库存预留',
    modelVersion: 'bailian/qwen3.5-plus@2026-08',
    stateVersion: 0,
  },
  {
    id: 'MEM-2026-0908',
    level: 'L1',
    title: 'TMS 观测超过 30 分钟应标记为过期',
    summary: '承运商窗口用于当日履约判断时，最后观测时间超过 30 分钟不可继续作为确定性依据。',
    status: 'PROPOSED',
    owner: '运输运营组',
    sourceCases: ['EXC-260906-341', 'EXC-260907-177'],
    evidenceCount: 8,
    createdAt: '2026-09-07T08:58:00+08:00',
    validUntil: '2026-10-07T23:59:59+08:00',
    confidence: 0.92,
    impactScope: '全国 · 当日提货',
    modelVersion: 'bailian/qwen3.5-plus@2026-08',
    stateVersion: 0,
  },
  {
    id: 'MEM-2026-0877',
    level: 'L3',
    title: '质检记录缺失的出库冻结手册',
    summary: '最终质检记录未知或发生批次身份冲突时，冻结正式出库，仅允许发起证据请求。',
    status: 'APPROVED',
    owner: '质量管理部 · 孟遥',
    sourceCases: ['EXC-260812-014', 'EXC-260819-118', 'EXC-260829-301'],
    evidenceCount: 34,
    createdAt: '2026-08-30T15:20:00+08:00',
    validUntil: '2027-02-28T23:59:59+08:00',
    confidence: 0.97,
    impactScope: '全域 · 正式出库',
    modelVersion: 'private/vllm-qwen3@sha256:8c0a',
    stateVersion: 3,
  },
]

export const demoEvalHealth: EvalHealth = {
  lastUpdatedAt: '2026-09-07T09:50:00+08:00',
  activeDataset: 'fulfillment-holdout-cn@2026.09.07',
  overallStatus: 'warning',
  metrics: [
    { id: 'traceability', label: '关键链路可追溯', value: 100, unit: '%', target: 100, direction: 'equal', trend: [100, 100, 100, 100, 100, 100, 100], sampleSize: 528 },
    { id: 'gap-recall', label: '未知/冲突升级召回', value: 96.8, unit: '%', target: 95, direction: 'at_least', trend: [94.1, 94.9, 95.2, 96.1, 96.4, 96.2, 96.8], sampleSize: 317 },
    { id: 'identity', label: '身份匹配精度', value: 99.3, unit: '%', target: 99, direction: 'at_least', trend: [98.8, 99, 99.1, 99.4, 99.2, 99.3, 99.3], sampleSize: 8621 },
    { id: 'verified', label: '验证闭环成功', value: 88.4, unit: '%', target: 85, direction: 'at_least', trend: [84, 85.2, 86.4, 87.9, 88.8, 89.1, 88.4], sampleSize: 528 },
    { id: 'duplicate', label: '重复业务副作用', value: 0, unit: 'count', target: 0, direction: 'equal', trend: [0, 0, 0, 0, 0, 0, 0], sampleSize: 12000 },
    { id: 'retrieval', label: '混合检索 p95', value: 842, unit: 'ms', target: 1000, direction: 'at_most', trend: [910, 884, 862, 820, 811, 826, 842], sampleSize: 2440 },
  ],
  runs: [
    { id: 'EVR-260907-24', dataset: 'fulfillment-holdout-cn@2026.09.07', frozenBundle: 'domain 1.3.0 · policy 09.1 · prompt 17', status: 'passed', startedAt: '2026-09-07T08:20:00+08:00', cases: 528 },
    { id: 'EVR-260906-19', dataset: 'fault-injection@2026.09.06', frozenBundle: 'domain 1.3.0 · policy 09.1 · prompt 17', status: 'warning', startedAt: '2026-09-06T22:10:00+08:00', cases: 144 },
    { id: 'EVR-260905-11', dataset: 'tenant-isolation@2026.09.05', frozenBundle: 'domain 1.2.1 · policy 08.9 · prompt 16', status: 'passed', startedAt: '2026-09-05T17:00:00+08:00', cases: 320 },
  ],
  providers: [
    { name: '火山方舟', location: '托管 · 华北', status: 'healthy', latencyMs: 1870 },
    { name: '阿里百炼', location: '托管 · 华东', status: 'degraded', latencyMs: 3240 },
    { name: '私有 vLLM', location: '客户数据面', status: 'healthy', latencyMs: 1260 },
  ],
}

function deriveDetail(summary: CaseSummary): CaseDetail {
  const detail = structuredClone(primaryDetail)
  return {
    ...detail,
    ...summary,
    currentStage: summary.status === 'awaiting_approval' ? '待人工审批' : summary.status === 'monitoring' ? '处置后监控' : '证据调查中',
    gaps: summary.gapCount === 0 ? [] : detail.gaps.slice(0, summary.gapCount),
    evidence: detail.evidence.map((event, index) => ({
      ...event,
      id: `${summary.id}-EV-${index + 1}`,
      detail: index === 0 ? summary.summary : event.detail,
    })),
  }
}

export function getDemoCase(caseId: string): CaseDetail {
  const summary = demoCases.find((item) => item.id === caseId) ?? demoCases[0]!
  return summary.id === primaryDetail.id ? structuredClone(primaryDetail) : deriveDetail(summary)
}

export function getDemoRecommendation(caseId: string): Recommendation {
  const detail = getDemoCase(caseId)
  const result = structuredClone(recommendation)
  result.caseId = caseId
  result.id = `REC-${caseId}`
  result.evidenceIds = detail.evidence.slice(0, 3).map((item) => item.id)
  result.proposedAction.idempotencyKey = `act:${caseId}:reserve:B03:240:v${detail.stateVersion}`
  return result
}

export function prepareDemoAction(input: ActionPreparationInput): PreparedAction {
  return {
    id: `ACT-${input.recommendationId.slice(-8)}`,
    caseId: input.caseId,
    definitionId: recommendation.proposedAction.actionDefinitionId,
    state: 'AWAITING_APPROVAL',
    stateVersion: 2,
    boundCaseStateVersion: input.expectedCaseStateVersion + 1,
    requestedBy: 'order-specialist',
  }
}

export function approveDemoAction(input: ApprovalInput): ApprovalResult {
  return {
    approvalId: `APR-${input.actionRequestId.slice(-8)}`,
    actionRequestId: input.actionRequestId,
    state: input.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED',
    stateVersion: input.expectedActionStateVersion + 1,
    acceptedAt: new Date().toISOString(),
  }
}

export function executeDemoAction(input: ExecuteActionInput): ExecutionResult {
  const now = new Date().toISOString()
  const ambiguous = input.caseId === 'EXC-260907-177'
  const outcome = ambiguous ? 'UNKNOWN' as const : 'SUCCEEDED' as const
  return {
    action: {
      id: input.actionRequestId,
      caseId: input.caseId,
      definitionId: 'reserve_substitute_inventory',
      state: ambiguous ? 'UNKNOWN_REQUIRES_RECONCILIATION' : 'EXECUTED_UNVERIFIED',
      stateVersion: input.expectedActionStateVersion + 2,
      boundCaseStateVersion: 18,
      requestedBy: 'order-specialist',
      approvedBy: 'warehouse-approver',
      updatedAt: now,
    },
    receipt: {
      id: `RCP-${input.actionRequestId.slice(-8)}`,
      externalRequestId: `WMS-REQ-${input.actionRequestId.slice(-6)}`,
      externalReceiptId: ambiguous ? undefined : `WMS-RSV-${input.actionRequestId.slice(-6)}`,
      outcome,
      attempts: [{
        number: 1,
        startedAt: now,
        completedAt: now,
        outcome,
        message: ambiguous ? '连接超时，外部结果不可确定；禁止盲目重试。' : 'WMS 已接收并返回库存预留回执。',
      }],
      createdAt: now,
      updatedAt: now,
    },
  }
}

export function verifyDemoAction(input: VerifyActionInput): VerificationView {
  const now = new Date().toISOString()
  const inconclusive = input.execution.action.state === 'UNKNOWN_REQUIRES_RECONCILIATION'
  const verificationId = `VRF-${input.actionRequestId.slice(-8)}`
  return {
    action: {
      ...input.execution.action,
      state: inconclusive ? 'UNKNOWN_REQUIRES_RECONCILIATION' : 'VERIFIED_SUCCEEDED',
      stateVersion: input.execution.action.stateVersion + (inconclusive ? 0 : 1),
      updatedAt: now,
    },
    receipt: {
      ...input.execution.receipt,
      verificationId,
      updatedAt: now,
    },
    verification: {
      id: verificationId,
      verifierId: 'inventory-reservation-verifier@1.1',
      purpose: 'ACTION',
      independentSource: 'WMS 独立库存快照',
      outcome: inconclusive ? 'INCONCLUSIVE' : 'PASSED',
      assertions: [{
        name: '订单与预留绑定',
        expected: '预留记录绑定当前订单行',
        observed: inconclusive ? '读取窗口内未获得确定记录' : '绑定一致，数量 240 件',
        passed: !inconclusive,
      }],
      evidenceIds: [`VERIFY-${input.actionRequestId.slice(-8)}`],
      resolvedKnowledgeGapIds: inconclusive ? [] : ['SUPPLIER_DELAY'],
      observedFrom: now,
      observedUntil: now,
      antiGamingMetrics: { independent_readback: 1, executor_self_report_weight: 0 },
      createdAt: now,
    },
    memoryCandidate: inconclusive ? undefined : {
      ...structuredClone(demoMemories[0]!),
      id: `MEM-${input.actionRequestId.slice(-8)}`,
      level: 'L1',
      sourceCases: [input.caseId],
      createdAt: now,
    },
  }
}

export function decideDemoMemory(input: MemoryDecisionInput): MemoryCandidate {
  const candidate = demoMemories.find((item) => item.id === input.candidateId) ?? demoMemories[0]!
  return {
    ...structuredClone(candidate),
    status: input.decision === 'APPROVE' ? 'APPROVED' : 'REVOKED',
  }
}
