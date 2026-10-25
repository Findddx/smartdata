import { Archive, Check, ChevronRight, Clock3, GitBranch, ShieldCheck, X } from 'lucide-react'
import { useMutation } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { smartDataClient } from '../api/client'
import { formatConfidence, formatDateTime } from '../domain/format'
import type { DataSource, MemoryCandidate, MemoryStatus } from '../domain/types'

interface MemoryWorkspaceProps {
  candidates: MemoryCandidate[]
  source: DataSource
}

const statusLabel: Record<MemoryStatus, string> = {
  PROPOSED: '待审核',
  APPROVED: '已发布',
  SUPERSEDED: '已替代',
  REVOKED: '已撤销',
}

export function MemoryWorkspace({ candidates, source }: MemoryWorkspaceProps) {
  const [selectedId, setSelectedId] = useState(candidates[0]?.id ?? '')
  const [comment, setComment] = useState('')
  const [decisions, setDecisions] = useState<Record<string, MemoryStatus>>({})
  const selected = useMemo(() => candidates.find((item) => item.id === selectedId) ?? candidates[0], [candidates, selectedId])

  const decision = useMutation({
    mutationFn: smartDataClient.decideMemory,
    onSuccess: ({ data }) => {
      setDecisions((current) => ({ ...current, [data.id]: data.status }))
      setComment('')
    },
  })

  if (!selected) {
    return <div className="empty-view">暂无记忆候选。</div>
  }

  const currentStatus = decisions[selected.id] ?? selected.status

  return (
    <div className="asset-view memory-view">
      <header className="asset-header">
        <div>
          <span className="eyebrow">EXPERIENCE GOVERNANCE</span>
          <h1>经验记忆审核</h1>
          <p>候选经验必须保留来源，并由业务责任人批准后才能影响共享检索与动作建议。</p>
        </div>
        <div className="asset-header-meta">
          <span className={`source-indicator source-${source}`}><i />{source === 'live' ? '实时数据' : '演示数据'}</span>
          <strong>{candidates.filter((item) => (decisions[item.id] ?? item.status) === 'PROPOSED').length}</strong>
          <small>待审核</small>
        </div>
      </header>

      <div className="memory-layout">
        <section className="memory-list" aria-label="记忆候选列表">
          <div className="list-column-head">
            <span>候选 / 范围</span>
            <span>来源</span>
          </div>
          {candidates.map((candidate) => {
            const status = decisions[candidate.id] ?? candidate.status
            return (
              <button
                type="button"
                key={candidate.id}
                className={`memory-row ${candidate.id === selected.id ? 'selected' : ''}`}
                onClick={() => setSelectedId(candidate.id)}
                aria-current={candidate.id === selected.id ? 'true' : undefined}
              >
                <span className="memory-level">{candidate.level}</span>
                <span className="memory-row-main">
                  <strong>{candidate.title}</strong>
                  <small>{candidate.impactScope}</small>
                </span>
                <span className="memory-row-source">
                  <strong>{candidate.sourceCases.length} 案件</strong>
                  <small>{statusLabel[status]}</small>
                </span>
                <ChevronRight size={15} aria-hidden="true" />
              </button>
            )
          })}
        </section>

        <article className="memory-inspector" key={selected.id}>
          <div className="memory-inspector-top">
            <span className="memory-level large">{selected.level}</span>
            <span className={`memory-status status-${currentStatus.toLowerCase()}`}>{statusLabel[currentStatus]}</span>
          </div>
          <h2>{selected.title}</h2>
          <p className="memory-summary">{selected.summary}</p>

          <div className="memory-metrics">
            <div><small>候选可信度</small><strong>{formatConfidence(selected.confidence)}</strong></div>
            <div><small>证据数量</small><strong>{selected.evidenceCount}</strong></div>
            <div><small>影响范围</small><strong>{selected.impactScope}</strong></div>
          </div>

          <section className="memory-provenance" aria-labelledby="memory-source-title">
            <div className="subsection-heading">
              <div><GitBranch size={16} aria-hidden="true" /><h3 id="memory-source-title">来源链</h3></div>
              <span>{selected.sourceCases.length} 个已完成案件</span>
            </div>
            <div className="source-case-list">
              {selected.sourceCases.map((caseId) => <code key={caseId}>{caseId}</code>)}
            </div>
            <dl>
              <div><dt>抽取模型</dt><dd>{selected.modelVersion}</dd></div>
              <div><dt>创建时间</dt><dd>{formatDateTime(selected.createdAt, true)}</dd></div>
              <div><dt>有效期至</dt><dd>{selected.validUntil ? formatDateTime(selected.validUntil) : '未设置'}</dd></div>
              <div><dt>责任人</dt><dd>{selected.owner}</dd></div>
            </dl>
          </section>

          <section className="memory-boundary" aria-labelledby="memory-boundary-title">
            <ShieldCheck size={16} aria-hidden="true" />
            <div>
              <h3 id="memory-boundary-title">发布边界</h3>
              <p>批准后可进入共享检索；不会覆盖源案件事实，也不会绕过动作策略与人工审批。</p>
            </div>
          </section>

          {currentStatus === 'PROPOSED' ? (
            <div className="memory-decision">
              <label>
                <span>审核意见</span>
                <textarea
                  rows={3}
                  value={comment}
                  onChange={(event) => setComment(event.target.value)}
                  placeholder="记录适用边界或驳回原因…"
                />
              </label>
              {decision.isError && <p className="form-error" role="alert">审核请求提交失败，请稍后重试。</p>}
              <div>
                <button
                  type="button"
                  className="secondary-action"
                  disabled={decision.isPending || comment.trim().length < 4}
                  onClick={() => decision.mutate({ candidateId: selected.id, decision: 'REJECT', comment: comment.trim(), expectedStateVersion: selected.stateVersion, dataSource: source })}
                >
                  <X size={16} aria-hidden="true" />不予发布
                </button>
                <button
                  type="button"
                  className="primary-action"
                  disabled={decision.isPending || comment.trim().length < 4}
                  onClick={() => decision.mutate({ candidateId: selected.id, decision: 'APPROVE', comment: comment.trim(), expectedStateVersion: selected.stateVersion, dataSource: source })}
                >
                  <Check size={16} aria-hidden="true" />批准发布
                </button>
              </div>
            </div>
          ) : (
            <div className="memory-resolved" role="status">
              {currentStatus === 'APPROVED' ? <Check size={16} aria-hidden="true" /> : <Archive size={16} aria-hidden="true" />}
              <span><strong>{statusLabel[currentStatus]}</strong><small>状态变化已进入审计链与索引同步队列。</small></span>
              <Clock3 size={14} aria-hidden="true" />
            </div>
          )}
        </article>
      </div>
    </div>
  )
}
