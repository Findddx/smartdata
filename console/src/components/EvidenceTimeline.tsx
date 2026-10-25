import { Clock3, Database, Fingerprint } from 'lucide-react'
import { claimStatusLabel, formatConfidence, formatDateTime } from '../domain/format'
import type { EvidenceEvent } from '../domain/types'

interface EvidenceTimelineProps {
  evidence: EvidenceEvent[]
  citedIds: string[]
}

export function EvidenceTimeline({ evidence, citedIds }: EvidenceTimelineProps) {
  return (
    <section className="evidence-section" aria-labelledby="evidence-title">
      <div className="section-heading evidence-heading">
        <div>
          <span className="eyebrow">BITEMPORAL EVIDENCE</span>
          <h2 id="evidence-title">证据时间线</h2>
        </div>
        <div className="time-legend" aria-label="时间字段说明">
          <span><i className="valid-swatch" />业务有效时间</span>
          <span><i className="recorded-swatch" />系统记录时间</span>
        </div>
      </div>

      <ol className="evidence-timeline">
        {evidence.map((event, index) => {
          const isCited = citedIds.includes(event.id)
          return (
            <li
              id={`evidence-${event.id}`}
              key={event.id}
              className={`evidence-event evidence-${event.status.toLowerCase()} ${isCited ? 'is-cited' : ''}`}
              tabIndex={-1}
              style={{ '--event-index': index } as React.CSSProperties}
            >
              <span className="evidence-node" aria-hidden="true" />
              <article>
                <div className="evidence-event-head">
                  <div>
                    <span className={`state-label state-${event.status.toLowerCase()}`}>
                      {claimStatusLabel[event.status]}
                    </span>
                    {isCited && <span className="citation-label">建议引用</span>}
                    <h3>{event.title}</h3>
                  </div>
                  <span className="confidence">可信度 {formatConfidence(event.confidence)}</span>
                </div>

                <p>{event.detail}</p>

                <div className="evidence-facts">
                  {event.facts.map((fact) => (
                    <span key={fact.label} className={fact.changed ? 'changed' : ''}>
                      <small>{fact.label}</small>
                      <strong>{fact.value}</strong>
                    </span>
                  ))}
                </div>

                <div className="evidence-provenance">
                  <span title="数据来源">
                    <Database size={13} aria-hidden="true" />
                    {event.sourceSystem}
                  </span>
                  <span title="源记录标识">
                    <Fingerprint size={13} aria-hidden="true" />
                    {event.sourceRecordId}
                  </span>
                  <span title="数据分类">{event.classification}</span>
                </div>

                <div className="bitemporal-row">
                  <span className="valid-time">
                    <Clock3 size={13} aria-hidden="true" />
                    <small>有效</small>
                    <time dateTime={event.validAt}>{formatDateTime(event.validAt, true)}</time>
                  </span>
                  <span className="recorded-time">
                    <Clock3 size={13} aria-hidden="true" />
                    <small>记录</small>
                    <time dateTime={event.recordedAt}>{formatDateTime(event.recordedAt, true)}</time>
                  </span>
                  {event.expiresAt && (
                    <span className="expires-time">
                      <small>失效</small>
                      <time dateTime={event.expiresAt}>{formatDateTime(event.expiresAt, true)}</time>
                    </span>
                  )}
                </div>
              </article>
            </li>
          )
        })}
      </ol>
    </section>
  )
}
