import { Check, CircleDashed, ReceiptText } from 'lucide-react'
import { formatDateTime } from '../domain/format'
import type { ActionEvent } from '../domain/types'

interface ActionTimelineProps {
  events: ActionEvent[]
}

export function ActionTimeline({ events }: ActionTimelineProps) {
  return (
    <section className="action-section" aria-labelledby="action-timeline-title">
      <div className="section-heading compact-heading">
        <div>
          <span className="eyebrow">ACTION &amp; VERIFICATION</span>
          <h2 id="action-timeline-title">动作与验证</h2>
        </div>
        <span className="plain-note">执行器回执与验证结论分离</span>
      </div>
      <ol className="action-timeline">
        {events.map((event) => (
          <li key={event.id} className={`action-event action-${event.status}`}>
            <span className="action-icon" aria-hidden="true">
              {event.status === 'complete' ? <Check size={13} /> : <CircleDashed size={13} />}
            </span>
            <div>
              <strong>{event.label}</strong>
              <p>{event.detail}</p>
              <span className="action-meta">
                <span>{event.actor}</span>
                {event.at && <time dateTime={event.at}>{formatDateTime(event.at, true)}</time>}
                {event.receiptId && (
                  <span><ReceiptText size={12} aria-hidden="true" />{event.receiptId}</span>
                )}
              </span>
            </div>
          </li>
        ))}
      </ol>
    </section>
  )
}
