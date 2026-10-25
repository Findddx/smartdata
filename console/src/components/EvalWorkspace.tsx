import { Activity, Check, CircleAlert, Clock3, DatabaseZap, ServerCog } from 'lucide-react'
import { formatDateTime } from '../domain/format'
import type { DataSource, EvalHealth, EvalMetric } from '../domain/types'

interface EvalWorkspaceProps {
  health: EvalHealth
  source: DataSource
}

function metricPasses(metric: EvalMetric): boolean {
  if (metric.direction === 'equal') return metric.value === metric.target
  if (metric.direction === 'at_least') return metric.value >= metric.target
  return metric.value <= metric.target
}

function metricValue(metric: EvalMetric): string {
  if (metric.unit === '%') return `${metric.value.toFixed(metric.value % 1 === 0 ? 0 : 1)}%`
  if (metric.unit === 'ms') return `${metric.value.toLocaleString('zh-CN')} ms`
  return metric.value.toLocaleString('zh-CN')
}

function metricTarget(metric: EvalMetric): string {
  const prefix = metric.direction === 'at_least' ? '≥' : metric.direction === 'at_most' ? '≤' : '='
  return `${prefix} ${metric.target}${metric.unit === '%' ? '%' : metric.unit === 'ms' ? ' ms' : ''}`
}

function Sparkline({ values, passing }: { values: number[]; passing: boolean }) {
  const min = Math.min(...values)
  const max = Math.max(...values)
  const range = max - min || 1
  const points = values
    .map((value, index) => `${(index / Math.max(values.length - 1, 1)) * 100},${28 - ((value - min) / range) * 22}`)
    .join(' ')

  return (
    <svg className={`sparkline ${passing ? 'passing' : 'failing'}`} viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">
      <line x1="0" x2="100" y1="28" y2="28" />
      <polyline points={points} />
    </svg>
  )
}

export function EvalWorkspace({ health, source }: EvalWorkspaceProps) {
  const passingCount = health.metrics.filter(metricPasses).length

  return (
    <div className="asset-view eval-view">
      <header className="asset-header">
        <div>
          <span className="eyebrow">EVALUATION HEALTH</span>
          <h1>评测健康</h1>
          <p>每次运行冻结领域、策略、提示词、模型、工具 Schema 与数据集版本。</p>
        </div>
        <div className="asset-header-meta">
          <span className={`source-indicator source-${source}`}><i />{source === 'live' ? '实时数据' : '演示数据'}</span>
          <strong>{passingCount}/{health.metrics.length}</strong>
          <small>门槛通过</small>
        </div>
      </header>

      <div className="eval-summary-line">
        <div><DatabaseZap size={17} aria-hidden="true" /><span><small>活动数据集</small><strong>{health.activeDataset}</strong></span></div>
        <div><Clock3 size={17} aria-hidden="true" /><span><small>最近更新</small><strong>{formatDateTime(health.lastUpdatedAt, true)}</strong></span></div>
        <div><Activity size={17} aria-hidden="true" /><span><small>总体状态</small><strong>{health.overallStatus === 'healthy' ? '健康' : health.overallStatus === 'warning' ? '需关注' : '未通过'}</strong></span></div>
      </div>

      <section className="metric-section" aria-labelledby="release-gates-title">
        <div className="section-heading compact-heading">
          <div><span className="eyebrow">RELEASE GATES</span><h2 id="release-gates-title">发布门槛</h2></div>
          <span className="plain-note">确定性仿真集 · 仅代表技术验收</span>
        </div>
        <div className="metric-grid">
          {health.metrics.map((metric) => {
            const passing = metricPasses(metric)
            return (
              <article className="metric-cell" key={metric.id}>
                <div className="metric-cell-head">
                  <span>{passing ? <Check size={14} aria-label="通过" /> : <CircleAlert size={14} aria-label="未通过" />}</span>
                  <strong>{metric.label}</strong>
                  <small>n={metric.sampleSize.toLocaleString('zh-CN')}</small>
                </div>
                <div className="metric-reading">
                  <strong>{metricValue(metric)}</strong>
                  <span>目标 {metricTarget(metric)}</span>
                </div>
                <Sparkline values={metric.trend} passing={passing} />
              </article>
            )
          })}
        </div>
      </section>

      <div className="eval-lower-grid">
        <section aria-labelledby="eval-runs-title">
          <div className="section-heading compact-heading">
            <div><span className="eyebrow">FROZEN RUNS</span><h2 id="eval-runs-title">最近运行</h2></div>
          </div>
          <div className="run-table" role="table" aria-label="最近评测运行">
            <div className="run-table-head" role="row"><span>运行</span><span>冻结包</span><span>案件</span><span>状态</span></div>
            {health.runs.map((run) => (
              <div className="run-table-row" role="row" key={run.id}>
                <span><strong>{run.id}</strong><small>{formatDateTime(run.startedAt)}</small></span>
                <span><strong>{run.dataset}</strong><small>{run.frozenBundle}</small></span>
                <span>{run.cases}</span>
                <span className={`run-status run-${run.status}`}>{run.status === 'passed' ? '通过' : run.status === 'warning' ? '关注' : run.status === 'running' ? '运行中' : '失败'}</span>
              </div>
            ))}
          </div>
        </section>

        <section className="provider-section" aria-labelledby="providers-title">
          <div className="section-heading compact-heading">
            <div><span className="eyebrow">MODEL ROUTING</span><h2 id="providers-title">模型路径</h2></div>
          </div>
          <div className="provider-list">
            {health.providers.map((provider) => (
              <div key={provider.name}>
                <ServerCog size={16} aria-hidden="true" />
                <span><strong>{provider.name}</strong><small>{provider.location}</small></span>
                <span><strong>{provider.latencyMs.toLocaleString('zh-CN')} ms</strong><small>{provider.status === 'healthy' ? '健康' : provider.status === 'degraded' ? '降级' : '离线'}</small></span>
                <i className={`provider-status provider-${provider.status}`} aria-hidden="true" />
              </div>
            ))}
          </div>
          <p className="eval-disclaimer">仿真结果不得用于宣称真实 ROI。业务目标需在真实试点中按匹配基线评估。</p>
        </section>
      </div>
    </div>
  )
}
