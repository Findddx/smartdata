import { AlertCircle, Check, KeyRound, RotateCcw, ShieldCheck, X } from 'lucide-react'
import { useMutation } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { smartDataClient } from '../api/client'
import type { ApprovalResult, CaseDetail, DataSource, PreparedAction, Recommendation } from '../domain/types'

interface ApprovalDrawerProps {
  open: boolean
  caseDetail: CaseDetail
  recommendation: Recommendation
  preparedAction: PreparedAction
  dataSource: DataSource
  onClose: () => void
  onDecided: (result: ApprovalResult) => void
}

export function ApprovalDrawer({ open, caseDetail, recommendation, preparedAction, dataSource, onClose, onDecided }: ApprovalDrawerProps) {
  const titleId = useId()
  const closeButtonRef = useRef<HTMLButtonElement>(null)
  const [comment, setComment] = useState('')
  const [formError, setFormError] = useState('')
  const [result, setResult] = useState<ApprovalResult | null>(null)
  const action = recommendation.proposedAction

  const decision = useMutation({
    mutationFn: smartDataClient.decideAction,
    onSuccess: ({ data }) => {
      setResult(data)
      onDecided(data)
    },
  })

  useEffect(() => {
    if (!open) return
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    closeButtonRef.current?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => {
      document.body.style.overflow = previousOverflow
      window.removeEventListener('keydown', onKeyDown)
    }
  }, [onClose, open])

  useEffect(() => {
    if (open) {
      setComment('')
      setFormError('')
      setResult(null)
      decision.reset()
    }
    // Reset only at drawer boundaries; mutation identity is intentionally excluded.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  if (!open) return null

  function submit(decisionValue: 'APPROVE' | 'REJECT') {
    if (comment.trim().length < 4) {
      setFormError('请填写至少 4 个字的审批意见，形成可审计记录。')
      return
    }
    setFormError('')
    decision.mutate({
      caseId: caseDetail.id,
      actionRequestId: preparedAction.id,
      expectedActionStateVersion: preparedAction.stateVersion,
      decision: decisionValue,
      comment: comment.trim(),
      dataSource,
    })
  }

  return (
    <div
      className="drawer-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose()
      }}
    >
      <section className="approval-drawer" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <header className="drawer-header">
          <div>
            <span className="eyebrow">HUMAN APPROVAL</span>
            <h2 id={titleId}>动作审批</h2>
          </div>
          <button ref={closeButtonRef} type="button" className="icon-button" onClick={onClose} aria-label="关闭审批面板">
            <X size={19} aria-hidden="true" />
          </button>
        </header>

        {result ? (
          <div className="approval-result" role="status">
            <span className="approval-result-icon"><Check size={24} aria-hidden="true" /></span>
            <span className="eyebrow">DECISION RECORDED</span>
            <h3>{result.state === 'APPROVED' ? '审批已通过' : '审批已驳回'}</h3>
            <p>决策已经写入审计链。通过不等于执行成功，后续仍需外部回执和独立验证。</p>
            <code>{result.approvalId}</code>
            <button type="button" className="secondary-action full-width" onClick={onClose}>返回案件</button>
          </div>
        ) : (
          <div className="drawer-scroll">
            <div className="approval-case-ref">
              <div>
                <small>案件 / 绑定状态版本</small>
                <strong>{caseDetail.id} · v{preparedAction.boundCaseStateVersion}</strong>
              </div>
              <span>{preparedAction.state === 'AWAITING_APPROVAL' ? '策略通过 · 待审批' : preparedAction.state}</span>
            </div>

            <section className="approval-action-summary" aria-labelledby="approval-action-title">
              <small>待审批动作</small>
              <h3 id="approval-action-title">{action.displayName}</h3>
              <code>{action.actionDefinitionId}@{action.version}</code>
              <dl>
                {action.parameters.map((item) => (
                  <div key={item.label}>
                    <dt>{item.label}</dt>
                    <dd>{item.value}</dd>
                  </div>
                ))}
              </dl>
            </section>

            <div className="approval-checks">
              <div>
                <ShieldCheck size={17} aria-hidden="true" />
                <span><small>权限关系</small><strong>{action.requiredRelation}</strong></span>
                <b>通过</b>
              </div>
              <div>
                <KeyRound size={17} aria-hidden="true" />
                <span><small>幂等范围</small><strong title={action.idempotencyKey}>{action.idempotencyKey}</strong></span>
                <b>锁定</b>
              </div>
              <div>
                <RotateCcw size={17} aria-hidden="true" />
                <span><small>失败补偿</small><strong>{action.compensator ?? '无自动补偿'}</strong></span>
                <b>{action.compensator ? '可用' : '人工'}</b>
              </div>
            </div>

            <section className="verification-contract">
              <span className="eyebrow">INDEPENDENT VERIFIER</span>
              <h3>独立验证条件</h3>
              <p>{action.verifier}</p>
              <span className="verification-warning"><AlertCircle size={14} aria-hidden="true" />外部超时不盲目重试，将进入人工对账。</span>
            </section>

            <label className="approval-comment">
              <span>审批意见 <small>必填，用于当前决策上下文</small></span>
              <textarea
                value={comment}
                onChange={(event) => setComment(event.target.value)}
                placeholder="说明批准或驳回的业务判断…"
                rows={4}
                aria-invalid={formError ? 'true' : undefined}
                aria-describedby={formError ? `${titleId}-error` : undefined}
              />
            </label>
            {formError && <p id={`${titleId}-error`} className="form-error" role="alert">{formError}</p>}
            {decision.isError && <p className="form-error" role="alert">审批请求未被受理，请核对案件状态后重试。</p>}

            <div className="drawer-actions">
              <button type="button" className="secondary-action" onClick={() => submit('REJECT')} disabled={decision.isPending}>
                驳回动作
              </button>
              <button type="button" className="primary-action" onClick={() => submit('APPROVE')} disabled={decision.isPending}>
                <Check size={17} aria-hidden="true" />{decision.isPending ? '正在提交…' : '确认批准'}
              </button>
            </div>
          </div>
        )}
      </section>
    </div>
  )
}
