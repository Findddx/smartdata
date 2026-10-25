import { Search, SlidersHorizontal } from 'lucide-react'
import { useMemo, useState } from 'react'
import { caseStatusLabel, formatDateTime, riskLabel } from '../domain/format'
import type { CaseSummary } from '../domain/types'

interface CaseQueueProps {
  cases: CaseSummary[]
  selectedId: string
  onSelect: (caseId: string) => void
}

type QueueFilter = 'all' | 'high' | 'approval'

export function CaseQueue({ cases, selectedId, onSelect }: CaseQueueProps) {
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<QueueFilter>('all')

  const visibleCases = useMemo(() => {
    const normalized = query.trim().toLocaleLowerCase('zh-CN')
    return cases.filter((item) => {
      const matchesText =
        normalized.length === 0 ||
        `${item.id} ${item.orderId} ${item.customer} ${item.exceptionType}`.toLocaleLowerCase('zh-CN').includes(normalized)
      const matchesFilter =
        filter === 'all' ||
        (filter === 'high' && (item.risk === 'critical' || item.risk === 'high')) ||
        (filter === 'approval' && item.status === 'awaiting_approval')
      return matchesText && matchesFilter
    })
  }, [cases, filter, query])

  return (
    <aside className="case-queue" aria-label="异常案件队列">
      <div className="queue-heading">
        <div>
          <span className="eyebrow">ORDER EXCEPTIONS</span>
          <h1>履约异常</h1>
        </div>
        <span className="queue-count" aria-label={`${cases.length} 个开放案件`}>
          {cases.length.toString().padStart(2, '0')}
        </span>
      </div>

      <label className="queue-search">
        <Search size={15} aria-hidden="true" />
        <span className="sr-only">搜索案件</span>
        <input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="订单、客户或异常类型"
        />
        <SlidersHorizontal size={14} aria-hidden="true" />
      </label>

      <div className="queue-filters" aria-label="案件筛选">
        {([
          ['all', '全部'],
          ['high', '高风险'],
          ['approval', '待审批'],
        ] as const).map(([value, label]) => (
          <button
            key={value}
            type="button"
            className={filter === value ? 'active' : ''}
            aria-pressed={filter === value}
            onClick={() => setFilter(value)}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="queue-list" aria-live="polite">
        {visibleCases.map((item, index) => (
          <button
            type="button"
            key={item.id}
            className={`queue-item ${selectedId === item.id ? 'selected' : ''}`}
            onClick={() => onSelect(item.id)}
            aria-current={selectedId === item.id ? 'true' : undefined}
            style={{ '--row-index': index } as React.CSSProperties}
          >
            <span className={`risk-mark risk-${item.risk}`} aria-label={riskLabel[item.risk]} />
            <span className="queue-item-body">
              <span className="queue-item-topline">
                <span className="queue-order">{item.orderId}</span>
                <time dateTime={item.updatedAt}>{formatDateTime(item.updatedAt)}</time>
              </span>
              <strong>{item.exceptionType}</strong>
              <span className="queue-customer">{item.customer}</span>
              <span className="queue-summary">{item.summary}</span>
              <span className="queue-meta">
                <span>{caseStatusLabel[item.status]}</span>
                <span>{item.gapCount} 个缺口</span>
                <span>截至 {formatDateTime(item.dueAt)}</span>
              </span>
            </span>
          </button>
        ))}
        {visibleCases.length === 0 && (
          <div className="queue-empty">
            <strong>未找到匹配案件</strong>
            <span>调整关键词或筛选条件。</span>
          </div>
        )}
      </div>
    </aside>
  )
}
