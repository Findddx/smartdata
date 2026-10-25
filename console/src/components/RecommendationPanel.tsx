import { AlertOctagon, ArrowUpRight, Check, CheckCircle2, FileCheck2, Play, ReceiptText, ScanSearch, ShieldCheck, TriangleAlert, X } from 'lucide-react'
import { formatConfidence, formatDateTime, riskLabel } from '../domain/format'
import type { ActionState, DataSource, ExecutionResult, PreparedAction, Recommendation, VerificationView } from '../domain/types'

interface RecommendationPanelProps {
  recommendation: Recommendation
  source: DataSource
  resolvedState?: 'APPROVED' | 'REJECTED'
  preparedAction?: PreparedAction
  execution?: ExecutionResult
  verification?: VerificationView
  isPreparing: boolean
  isExecuting: boolean
  isVerifying: boolean
  prepareError: boolean
  executionError: boolean
  verificationError: boolean
  onOpenApproval: () => void
  onExecute: () => void
  onVerify: () => void
}

function focusEvidence(evidenceId: string) {
  const element = document.getElementById(`evidence-${evidenceId}`)
  element?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  element?.focus({ preventScroll: true })
}

const stateLabel: Record<string, string> = {
  PROPOSED: '动作请求已创建',
  POLICY_PASSED: '策略已通过',
  AWAITING_APPROVAL: '策略已通过 · 等待人工审批',
  APPROVED: '审批已通过 · 尚未执行',
  EXECUTING: '正在调用客户侧执行器',
  EXECUTED_UNVERIFIED: '已取得回执 · 等待独立验证',
  VERIFIED_SUCCEEDED: '独立验证通过 · 已结案',
  VERIFIED_FAILED: '独立验证失败',
  REJECTED: '审批已驳回',
  EXPIRED: '版本变化 · 原审批已失效',
  CANCELLED: '动作已取消',
  UNKNOWN_REQUIRES_RECONCILIATION: '外部结果不确定 · 禁止盲目重试',
  COMPENSATING: '验证失败 · 正在补偿',
  COMPENSATED: '补偿已完成 · 等待人工处理',
  MANUAL_INTERVENTION: '需要人工接管',
}

const receiptLabel = {
  SUCCEEDED: '执行器返回成功',
  FAILED: '执行器返回失败',
  UNKNOWN: '外部结果不确定',
  COMPENSATED: '补偿已完成',
}

const verificationLabel = {
  PASSED: '独立验证通过',
  FAILED: '独立验证失败',
  INCONCLUSIVE: '无法判断 · 已转人工对账',
}

const noNextStepLabel: Partial<Record<ActionState, string>> = {
  EXECUTING: '执行受理中 · 等待外部回执',
  EXECUTED_UNVERIFIED: '等待外部回执水合',
  VERIFIED_SUCCEEDED: '验证完成 · 已结案',
  VERIFIED_FAILED: '验证失败 · 人工处理',
  REJECTED: '审批已驳回',
  EXPIRED: '审批已失效',
  CANCELLED: '动作已取消',
  UNKNOWN_REQUIRES_RECONCILIATION: '需先取得回执或人工对账',
  COMPENSATING: '补偿处理中',
  COMPENSATED: '补偿已完成 · 人工处理',
  MANUAL_INTERVENTION: '已转人工接管',
}

export function RecommendationPanel({
  recommendation,
  source,
  resolvedState,
  preparedAction,
  execution,
  verification,
  isPreparing,
  isExecuting,
  isVerifying,
  prepareError,
  executionError,
  verificationError,
  onOpenApproval,
  onExecute,
  onVerify,
}: RecommendationPanelProps) {
  const action = recommendation.proposedAction
  const currentAction = verification?.action ?? execution?.action ?? preparedAction
  const currentState = currentAction?.state
  const visibleReceipt = verification?.receipt ?? execution?.receipt
  const canExecute = currentState === 'APPROVED' && !execution
  const canVerify = execution !== undefined && verification === undefined && ['EXECUTED_UNVERIFIED', 'UNKNOWN_REQUIRES_RECONCILIATION'].includes(execution.action.state)
  const isUnknown = currentState === 'UNKNOWN_REQUIRES_RECONCILIATION' || verification?.verification.outcome === 'INCONCLUSIVE'
  const hasNoInteractiveNextStep = currentState !== undefined && noNextStepLabel[currentState] !== undefined && !canVerify

  function runPrimaryStep() {
    if (canExecute) return onExecute()
    if (canVerify) return onVerify()
    onOpenApproval()
  }

  function primaryLabel() {
    if (isExecuting) return '正在等待外部回执…'
    if (isVerifying) return '正在独立回读验证…'
    if (canExecute) return '显式触发执行'
    if (canVerify) return isUnknown ? '先执行独立回读' : '启动独立验证'
    if (resolvedState === 'REJECTED') return '审批已驳回'
    if (verification) {
      if (verification.verification.outcome !== 'PASSED') return '验证完成 · 人工处理'
      return verification.memoryCandidate ? '验证完成 · 已结案' : '动作已验证 · 案件继续观测'
    }
    if (currentState && noNextStepLabel[currentState]) return noNextStepLabel[currentState]
    if (isPreparing) return '正在校验动作策略…'
    return preparedAction ? '继续人工审批' : '进入策略校验与审批'
  }

  return (
    <aside className="recommendation-panel" aria-labelledby="recommendation-title">
      <div className="recommendation-topline">
        <span className="eyebrow">TYPED RECOMMENDATION</span>
        <span className={`source-indicator source-${source}`}>
          <i aria-hidden="true" />{source === 'live' ? '实时数据' : '演示数据'}
        </span>
      </div>

      <div className="recommendation-index" aria-hidden="true">03</div>
      <div className="recommendation-title-row">
        <h2 id="recommendation-title">{recommendation.title}</h2>
        <span>{formatConfidence(recommendation.confidence)}</span>
      </div>
      <p className="generated-at">生成于 {formatDateTime(recommendation.generatedAt, true)}</p>

      <div className="recommendation-state">
        <ShieldCheck size={17} aria-hidden="true" />
        <div>
          <small>策略状态</small>
          <strong>
            {currentState === 'VERIFIED_SUCCEEDED' && verification && !verification.memoryCandidate
              ? '独立验证通过 · 案件继续观测'
              : currentState ? stateLabel[currentState] : '建议已生成 · 尚未提交校验'}
          </strong>
        </div>
      </div>

      <section className="recommendation-block" aria-labelledby="reason-title">
        <h3 id="reason-title">判定依据</h3>
        <ol className="reason-list">
          {recommendation.rationale.map((item, index) => (
            <li key={item}>
              <span>{String(index + 1).padStart(2, '0')}</span>
              <p>{item}</p>
            </li>
          ))}
        </ol>
        <div className="evidence-links" aria-label="引用证据">
          {recommendation.evidenceIds.slice(0, 4).map((id) => (
            <button type="button" key={id} onClick={() => focusEvidence(id)}>
              {id}<ArrowUpRight size={12} aria-hidden="true" />
            </button>
          ))}
          {recommendation.evidenceIds.length > 4 && <span className="citation-more">+{recommendation.evidenceIds.length - 4} 项</span>}
        </div>
      </section>

      <section className="recommendation-block action-contract" aria-labelledby="contract-title">
        <h3 id="contract-title">动作契约</h3>
        <code>{action.actionDefinitionId}@{action.version}</code>
        <dl>
          {action.parameters.map((parameter) => (
            <div key={parameter.label}>
              <dt>{parameter.label}</dt>
              <dd>{parameter.value}</dd>
            </div>
          ))}
        </dl>
        <div className="contract-meta">
          <span>{riskLabel[action.risk]}</span>
          <span>可补偿</span>
          <span>状态版本锁定</span>
        </div>
      </section>

      <div className="impact-note">
        <CheckCircle2 size={16} aria-hidden="true" />
        <div><small>预期影响</small><p>{recommendation.expectedImpact}</p></div>
      </div>
      <div className="caveat-note">
        <TriangleAlert size={16} aria-hidden="true" />
        <div><small>仍需确认</small><p>{recommendation.caveat}</p></div>
      </div>

      {visibleReceipt && (
        <section className={`execution-receipt ${visibleReceipt.outcome === 'UNKNOWN' ? 'artifact-warning' : ''}`} aria-labelledby="receipt-title">
          <div className="artifact-heading">
            {visibleReceipt.outcome === 'UNKNOWN' ? <AlertOctagon size={16} aria-hidden="true" /> : <ReceiptText size={16} aria-hidden="true" />}
            <span><small>EXTERNAL RECEIPT</small><strong id="receipt-title">{receiptLabel[visibleReceipt.outcome]}</strong></span>
          </div>
          <dl>
            <div><dt>请求标识</dt><dd title={visibleReceipt.externalRequestId}>{visibleReceipt.externalRequestId}</dd></div>
            <div><dt>外部回执</dt><dd title={visibleReceipt.externalReceiptId}>{visibleReceipt.externalReceiptId ?? '未确认'}</dd></div>
            <div><dt>尝试次数</dt><dd>{visibleReceipt.attempts.length} 次</dd></div>
            <div><dt>回执标识</dt><dd title={visibleReceipt.id}>{visibleReceipt.id}</dd></div>
          </dl>
          {visibleReceipt.attempts.at(-1) && <p>{visibleReceipt.attempts.at(-1)!.message}</p>}
          {visibleReceipt.compensationReceiptId && <b>补偿回执：{visibleReceipt.compensationReceiptId}</b>}
          {visibleReceipt.outcome === 'UNKNOWN' && <b>必须先验证或人工对账；系统不会自动重试。</b>}
        </section>
      )}

      {verification && (
        <section className={`verification-result verification-${verification.verification.outcome.toLowerCase()}`} aria-labelledby="verification-result-title">
          <div className="artifact-heading">
            {verification.verification.outcome === 'PASSED' ? <Check size={16} aria-hidden="true" /> : <X size={16} aria-hidden="true" />}
            <span><small>INDEPENDENT VERIFICATION</small><strong id="verification-result-title">{verificationLabel[verification.verification.outcome]}</strong></span>
          </div>
          <p>{verification.verification.independentSource} · {verification.verification.verifierId}</p>
          <ul>
            {verification.verification.assertions.map((assertion) => (
              <li key={assertion.name}>
                <span>{assertion.passed ? <Check size={12} aria-label="通过" /> : <X size={12} aria-label="未通过" />}</span>
                <span><strong>{assertion.name}</strong><small>期望 {assertion.expected} · 观测 {assertion.observed}</small></span>
              </li>
            ))}
          </ul>
          <div className="verification-window">观测窗口 {formatDateTime(verification.verification.observedFrom, true)} — {formatDateTime(verification.verification.observedUntil, true)}</div>
          {verification.memoryCandidate && <b>已生成 L1 经验候选，仍需业务责任人审核后发布。</b>}
        </section>
      )}

      <button
        type="button"
        className="primary-action"
        onClick={runPrimaryStep}
        disabled={resolvedState === 'REJECTED' || hasNoInteractiveNextStep || isPreparing || isExecuting || isVerifying || verification !== undefined}
      >
        {canExecute ? <Play size={17} aria-hidden="true" /> : canVerify ? <ScanSearch size={17} aria-hidden="true" /> : <FileCheck2 size={17} aria-hidden="true" />}
        {primaryLabel()}
        {!currentAction && <span>⌘ ↵</span>}
      </button>
      {prepareError && <p className="form-error" role="alert">动作请求未创建：案件版本或权限可能已变化，请刷新后重试。</p>}
      {executionError && <p className="form-error" role="alert">执行未被受理；禁止盲目重试，请刷新动作状态并核对版本。</p>}
      {verificationError && <p className="form-error" role="alert">独立验证未完成；案件保持未验证状态，请转人工检查来源系统。</p>}
      <p className="recommendation-footnote">审批、执行与验证是三个独立步骤；模型只能提出类型化动作请求。</p>
    </aside>
  )
}
