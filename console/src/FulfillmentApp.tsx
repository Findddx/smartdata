import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import './styles.css'
import {
  Activity,
  Bell,
  Boxes,
  BrainCircuit,
  Building2,
  ChevronDown,
  CircleHelp,
  ClockAlert,
  Database,
  FileWarning,
  Gauge,
  Menu,
  Network,
  Settings,
  ShieldCheck,
  UserRound,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { smartDataClient } from './api/client'
import { ActionTimeline } from './components/ActionTimeline'
import { ApprovalDrawer } from './components/ApprovalDrawer'
import { CaseQueue } from './components/CaseQueue'
import { EvalWorkspace } from './components/EvalWorkspace'
import { EvidenceTimeline } from './components/EvidenceTimeline'
import { MemoryWorkspace } from './components/MemoryWorkspace'
import { RecommendationPanel } from './components/RecommendationPanel'
import { caseStatusLabel, formatDateTime, riskLabel } from './domain/format'
import type { ActionEvent, ApprovalResult, CaseDetail, DataSource, ExecutionResult, PreparedAction, Recommendation, VerificationView } from './domain/types'

type View = 'cases' | 'memory' | 'eval'

const viewLabel: Record<View, string> = {
  cases: '案件处置',
  memory: '经验记忆',
  eval: '评测健康',
}

function AppHeader({ view, dataSource }: { view: View; dataSource?: DataSource }) {
  return (
    <header className="app-header">
      <div className="brand-lockup">
        <span className="brand-mark" aria-hidden="true"><i /><i /><i /></span>
        <div><strong>SMARTDATA</strong><span>履约塔台</span></div>
      </div>
      <span className="header-divider" aria-hidden="true" />
      <span className="current-view">{viewLabel[view]}</span>
      <div className="header-context">
        <button type="button" className="tenant-switcher" aria-label="切换租户">
          <Building2 size={15} aria-hidden="true" />
          <span><small>当前租户</small><strong>澄川工业 · 仿真</strong></span>
          <ChevronDown size={14} aria-hidden="true" />
        </button>
        <span className={`plane-health ${dataSource === 'demo' ? 'plane-demo' : ''}`}>
          <i />{dataSource === 'live' ? '客户数据面在线' : dataSource === 'demo' ? '演示数据面' : '正在连接数据面'}
        </span>
        <button type="button" className="icon-button" aria-label="通知"><Bell size={17} aria-hidden="true" /><b>2</b></button>
        <button type="button" className="avatar-button" aria-label="当前用户：周岚">周</button>
      </div>
    </header>
  )
}

function Navigation({ view, onChange }: { view: View; onChange: (view: View) => void }) {
  return (
    <nav className="nav-rail" aria-label="主导航">
      <div className="nav-main">
        <button type="button" className="mobile-menu" aria-label="打开菜单"><Menu size={19} /></button>
        <button type="button" className={view === 'cases' ? 'active' : ''} onClick={() => onChange('cases')} aria-label="案件处置" title="案件处置" aria-current={view === 'cases' ? 'page' : undefined}><FileWarning size={19} /></button>
        <button type="button" className={view === 'memory' ? 'active' : ''} onClick={() => onChange('memory')} aria-label="经验记忆" title="经验记忆" aria-current={view === 'memory' ? 'page' : undefined}><BrainCircuit size={19} /></button>
        <button type="button" className={view === 'eval' ? 'active' : ''} onClick={() => onChange('eval')} aria-label="评测健康" title="评测健康" aria-current={view === 'eval' ? 'page' : undefined}><Gauge size={19} /></button>
        <span className="nav-separator" />
        <button type="button" aria-label="领域模型" title="领域模型"><Network size={19} /></button>
        <button type="button" aria-label="连接器" title="连接器"><Boxes size={19} /></button>
      </div>
      <div className="nav-secondary">
        <button type="button" aria-label="帮助" title="帮助"><CircleHelp size={18} /></button>
        <button type="button" aria-label="设置" title="设置"><Settings size={18} /></button>
        <button type="button" aria-label="个人账户" title="个人账户"><UserRound size={18} /></button>
      </div>
    </nav>
  )
}

function LoadingSurface({ label }: { label: string }) {
  return (
    <div className="loading-surface" role="status">
      <span className="loading-line wide" /><span className="loading-line" /><span className="loading-line short" />
      <p>{label}</p>
    </div>
  )
}

function CaseHeader({ detail }: { detail: CaseDetail }) {
  return (
    <header className="case-workbench-header">
      <div className="case-identity">
        <div className="case-status-line">
          <span className={`risk-chip risk-${detail.risk}`}>{riskLabel[detail.risk]}</span>
          <span>{caseStatusLabel[detail.status]}</span>
          <span>{detail.currentStage}</span>
        </div>
        <h1>{detail.exceptionType}</h1>
        <p>{detail.orderId} · {detail.customer}</p>
      </div>
      <dl className="case-kpis">
        <div><dt>订单金额</dt><dd>{detail.orderValue}</dd></div>
        <div><dt>{detail.scheduleLabel}</dt><dd>{formatDateTime(detail.promisedAt)}</dd></div>
        <div><dt>目的地</dt><dd>{detail.destination}</dd></div>
        <div><dt>责任人</dt><dd>{detail.owner}</dd></div>
      </dl>
      <div className="version-line">
        <span>CASE {detail.id}</span>
        <span>STATE v{detail.stateVersion}</span>
        <span>{detail.domainVersion}</span>
        <span>{detail.policyVersion}</span>
      </div>
    </header>
  )
}

function GapStrip({ detail }: { detail: CaseDetail }) {
  if (detail.gaps.length === 0) {
    return (
      <section className="gap-strip no-gaps" aria-label="知识缺口">
        <ShieldCheck size={17} aria-hidden="true" />
        <strong>当前无未处理知识缺口</strong>
        <span>继续监控来源观测与验证窗口。</span>
      </section>
    )
  }
  return (
    <section className="gap-strip" aria-labelledby="gap-title">
      <div className="gap-strip-label">
        <ClockAlert size={17} aria-hidden="true" />
        <span><small>KNOWLEDGE GAPS</small><strong id="gap-title">{detail.gaps.length} 个决策缺口</strong></span>
      </div>
      <div className="gap-items">
        {detail.gaps.map((gap) => (
          <div key={gap.id}>
            <span className={`gap-kind gap-${gap.kind.toLowerCase()}`}>{gap.kind}</span>
            <strong>{gap.title}</strong>
            <p>{gap.requestedObservation}</p>
          </div>
        ))}
      </div>
    </section>
  )
}

function SourceStatus({ detail }: { detail: CaseDetail }) {
  return (
    <section className="source-status" aria-label="来源系统状态">
      <div><Database size={14} aria-hidden="true" /><strong>{detail.sourceCount} 个来源</strong></div>
      {detail.sourceHealth.map((source) => (
        <span key={source.source} className={`source-health source-health-${source.status}`} title={`最近观测 ${formatDateTime(source.lastObservedAt, true)}`}>
          <i aria-hidden="true" />{source.source}
        </span>
      ))}
    </section>
  )
}

interface CaseWorkbenchProps {
  detail: CaseDetail
  recommendation: Recommendation
  actionEvents: Awaited<ReturnType<typeof smartDataClient.getActionTimeline>>['data']
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

function CaseWorkbench({
  detail,
  recommendation,
  actionEvents,
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
}: CaseWorkbenchProps) {
  return (
    <div className="workbench-layout">
      <main className="case-workbench" key={detail.id}>
        <CaseHeader detail={detail} />
        <GapStrip detail={detail} />
        <SourceStatus detail={detail} />
        <EvidenceTimeline evidence={detail.evidence} citedIds={recommendation.evidenceIds} />
        <ActionTimeline events={actionEvents} />
      </main>
      <RecommendationPanel
        recommendation={recommendation}
        source={source}
        resolvedState={resolvedState}
        preparedAction={preparedAction}
        execution={execution}
        verification={verification}
        isPreparing={isPreparing}
        isExecuting={isExecuting}
        isVerifying={isVerifying}
        prepareError={prepareError}
        executionError={executionError}
        verificationError={verificationError}
        onOpenApproval={onOpenApproval}
        onExecute={onExecute}
        onVerify={onVerify}
      />
    </div>
  )
}

export function FulfillmentApp() {
  const queryClient = useQueryClient()
  const [view, setView] = useState<View>('cases')
  const [selectedCaseId, setSelectedCaseId] = useState('')
  const [approvalOpen, setApprovalOpen] = useState(false)
  const [actionDecisions, setActionDecisions] = useState<Record<string, 'APPROVED' | 'REJECTED'>>({})
  const [preparedActions, setPreparedActions] = useState<Record<string, PreparedAction>>({})
  const [executions, setExecutions] = useState<Record<string, ExecutionResult>>({})
  const [verifications, setVerifications] = useState<Record<string, VerificationView>>({})

  const casesQuery = useQuery({ queryKey: ['cases'], queryFn: smartDataClient.listCases })
  const detailQuery = useQuery({
    queryKey: ['case', selectedCaseId],
    queryFn: () => smartDataClient.getCase(selectedCaseId),
    enabled: selectedCaseId.length > 0 && view === 'cases',
  })
  const recommendationQuery = useQuery({
    queryKey: ['recommendation', selectedCaseId],
    queryFn: () => smartDataClient.getRecommendation(selectedCaseId),
    enabled: selectedCaseId.length > 0 && view === 'cases',
  })
  const timelineQuery = useQuery({
    queryKey: ['action-timeline', selectedCaseId],
    queryFn: () => smartDataClient.getActionTimeline(selectedCaseId),
    enabled: selectedCaseId.length > 0 && view === 'cases',
  })
  const actionHydrationQuery = useQuery({
    queryKey: ['action-hydration', selectedCaseId],
    queryFn: () => smartDataClient.getActionHydration(selectedCaseId),
    enabled: selectedCaseId.length > 0 && view === 'cases',
  })
  const memoryQuery = useQuery({
    queryKey: ['memory-candidates'],
    queryFn: smartDataClient.listMemoryCandidates,
    enabled: view === 'memory',
  })
  const evalQuery = useQuery({
    queryKey: ['eval-health'],
    queryFn: smartDataClient.getEvalHealth,
    enabled: view === 'eval',
  })

  const prepareAction = useMutation({
    mutationFn: smartDataClient.prepareAction,
    onSuccess: ({ data }) => {
      setPreparedActions((current) => ({ ...current, [data.caseId]: data }))
      setApprovalOpen(true)
      void refreshCaseQueries(data.caseId)
    },
  })

  const executeAction = useMutation({
    mutationFn: smartDataClient.executeAction,
    onSuccess: ({ data }) => {
      setExecutions((current) => ({ ...current, [data.action.caseId]: data }))
      setPreparedActions((current) => ({ ...current, [data.action.caseId]: data.action }))
      void refreshCaseQueries(data.action.caseId)
    },
  })

  const verifyAction = useMutation({
    mutationFn: smartDataClient.verifyAction,
    onSuccess: ({ data }) => {
      setVerifications((current) => ({ ...current, [data.action.caseId]: data }))
      setPreparedActions((current) => ({ ...current, [data.action.caseId]: data.action }))
      void refreshCaseQueries(data.action.caseId, true)
    },
  })

  function refreshCaseQueries(caseId: string, includeMemory = false) {
    const queries = [
      queryClient.invalidateQueries({ queryKey: ['cases'] }),
      queryClient.invalidateQueries({ queryKey: ['case', caseId] }),
      queryClient.invalidateQueries({ queryKey: ['action-timeline', caseId] }),
      queryClient.invalidateQueries({ queryKey: ['action-hydration', caseId] }),
    ]
    if (includeMemory) queries.push(queryClient.invalidateQueries({ queryKey: ['memory-candidates'] }))
    return Promise.all(queries)
  }

  useEffect(() => {
    const cases = casesQuery.data?.data ?? []
    if (cases.length > 0 && !cases.some((item) => item.id === selectedCaseId)) {
      setSelectedCaseId(cases[0]!.id)
    }
  }, [casesQuery.data, selectedCaseId])

  const detail = detailQuery.data?.data
  const recommendation = recommendationQuery.data?.data
  const actionEvents = timelineQuery.data?.data
  const isCaseReady = detail && recommendation && actionEvents
  const caseSource: DataSource = [detailQuery.data?.source, recommendationQuery.data?.source].every((item) => item === 'live') ? 'live' : 'demo'
  const hydratedAction = actionHydrationQuery.data?.data
  const selectedPreparedAction = detail ? preparedActions[detail.id] ?? hydratedAction?.action : undefined
  const selectedExecution = detail ? executions[detail.id] ?? hydratedAction?.execution : undefined
  const selectedVerification = detail ? verifications[detail.id] ?? hydratedAction?.verification : undefined
  const selectedResolution = detail
    ? actionDecisions[detail.id] ?? (selectedPreparedAction?.state === 'REJECTED' ? 'REJECTED' : selectedPreparedAction && selectedPreparedAction.state !== 'AWAITING_APPROVAL' && selectedPreparedAction.state !== 'PROPOSED' && selectedPreparedAction.state !== 'POLICY_PASSED' ? 'APPROVED' : undefined)
    : undefined
  const visibleActionEvents = useMemo(() => {
    if (!actionEvents || caseSource === 'live') return actionEvents
    return actionEvents.map((event): ActionEvent => {
      if (event.id === 'AE-04' && selectedPreparedAction?.state !== 'AWAITING_APPROVAL') {
        return { ...event, status: 'complete', detail: actionDecisions[selectedCaseId] === 'REJECTED' ? '审批人已驳回动作请求。' : '审批人与申请人分离，决策已写入审计链。' }
      }
      if (event.id === 'AE-05' && selectedExecution) {
        return {
          ...event,
          status: selectedExecution.receipt.outcome === 'UNKNOWN' ? 'failed' : 'complete',
          detail: selectedExecution.receipt.attempts.at(-1)?.message ?? event.detail,
          receiptId: selectedExecution.receipt.id,
          at: selectedExecution.receipt.updatedAt,
        }
      }
      if (event.id === 'AE-05' && selectedPreparedAction?.state === 'APPROVED') return { ...event, status: 'current', detail: '审批已通过；等待审批人显式触发 Connector Node。' }
      if (event.id === 'AE-06' && selectedVerification) {
        return {
          ...event,
          status: selectedVerification.verification.outcome === 'PASSED' ? 'complete' : 'failed',
          detail: `${selectedVerification.verification.independentSource}：${selectedVerification.verification.outcome === 'PASSED' ? '验证通过' : selectedVerification.verification.outcome === 'FAILED' ? '验证失败' : '无法判断，转人工对账'}。`,
          receiptId: selectedVerification.verification.id,
          at: selectedVerification.verification.createdAt,
        }
      }
      if (event.id === 'AE-06' && selectedExecution) return { ...event, status: 'current', detail: '外部回执已保存；等待从独立来源重新观测。' }
      return event
    })
  }, [actionDecisions, actionEvents, caseSource, selectedCaseId, selectedExecution, selectedPreparedAction, selectedVerification])

  function openApproval() {
    if (!detail || !recommendation) return
    if (selectedPreparedAction?.state === 'AWAITING_APPROVAL') {
      setApprovalOpen(true)
      return
    }
    if (selectedPreparedAction) return
    prepareAction.mutate({
      caseId: detail.id,
      recommendationId: recommendation.id,
      expectedCaseStateVersion: detail.stateVersion,
      idempotencyKey: recommendation.proposedAction.idempotencyKey,
      dataSource: caseSource,
    })
  }

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter' && view === 'cases' && detail && recommendation && (!selectedPreparedAction || selectedPreparedAction.state === 'AWAITING_APPROVAL')) {
        event.preventDefault()
        openApproval()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [detail, recommendation, view, selectedPreparedAction, caseSource])

  function recordDecision(result: ApprovalResult) {
    if (!detail) return
    setActionDecisions((current) => ({ ...current, [detail.id]: result.state }))
    setPreparedActions((current) => {
      const action = current[detail.id]
      if (!action) return current
      return {
        ...current,
        [detail.id]: { ...action, state: result.state, stateVersion: result.stateVersion, updatedAt: result.acceptedAt },
      }
    })
    void refreshCaseQueries(detail.id)
  }

  function executeSelectedAction() {
    if (!detail || !selectedPreparedAction || selectedPreparedAction.state !== 'APPROVED') return
    executeAction.mutate({
      caseId: detail.id,
      actionRequestId: selectedPreparedAction.id,
      expectedActionStateVersion: selectedPreparedAction.stateVersion,
      dataSource: caseSource,
    })
  }

  function verifySelectedAction() {
    if (!detail || !selectedExecution) return
    verifyAction.mutate({
      caseId: detail.id,
      actionRequestId: selectedExecution.action.id,
      expectedCaseStateVersion: detail.stateVersion,
      dataSource: caseSource,
      execution: selectedExecution,
    })
  }

  return (
    <div className="app-shell">
      <AppHeader view={view} dataSource={casesQuery.data?.source} />
      <Navigation view={view} onChange={setView} />

      <div className="app-content">
        {view === 'cases' && (
          <div className="cases-view">
            {casesQuery.data ? (
              <CaseQueue cases={casesQuery.data.data} selectedId={selectedCaseId} onSelect={setSelectedCaseId} />
            ) : (
              <div className="queue-loading"><LoadingSurface label="正在载入案件队列" /></div>
            )}

            {isCaseReady ? (
              <CaseWorkbench
                detail={detail}
                recommendation={recommendation}
                actionEvents={visibleActionEvents ?? actionEvents}
                source={caseSource}
                resolvedState={selectedResolution}
                preparedAction={selectedPreparedAction}
                execution={selectedExecution}
                verification={selectedVerification}
                isPreparing={prepareAction.isPending && prepareAction.variables?.caseId === detail.id}
                isExecuting={executeAction.isPending && executeAction.variables?.caseId === detail.id}
                isVerifying={verifyAction.isPending && verifyAction.variables?.caseId === detail.id}
                prepareError={prepareAction.isError && prepareAction.variables?.caseId === detail.id}
                executionError={executeAction.isError && executeAction.variables?.caseId === detail.id}
                verificationError={verifyAction.isError && verifyAction.variables?.caseId === detail.id}
                onOpenApproval={openApproval}
                onExecute={executeSelectedAction}
                onVerify={verifySelectedAction}
              />
            ) : (
              <div className="workbench-loading"><LoadingSurface label="正在组装案件证据" /></div>
            )}
          </div>
        )}

        {view === 'memory' && (
          memoryQuery.data ? <MemoryWorkspace candidates={memoryQuery.data.data} source={memoryQuery.data.source} /> : <LoadingSurface label="正在载入记忆候选" />
        )}

        {view === 'eval' && (
          evalQuery.data ? <EvalWorkspace health={evalQuery.data.data} source={evalQuery.data.source} /> : <LoadingSurface label="正在载入评测健康" />
        )}
      </div>

      {detail && recommendation && selectedPreparedAction && (
        <ApprovalDrawer
          open={approvalOpen}
          caseDetail={detail}
          recommendation={recommendation}
          preparedAction={selectedPreparedAction}
          dataSource={caseSource}
          onClose={() => setApprovalOpen(false)}
          onDecided={recordDecision}
        />
      )}
    </div>
  )
}
