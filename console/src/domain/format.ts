import type { CaseStatus, ClaimStatus, RiskLevel } from './types'

const chinaDateTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

const chinaDateTimeSeconds = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

export function formatDateTime(value: string, seconds = false): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return (seconds ? chinaDateTimeSeconds : chinaDateTime).format(parsed).replaceAll('/', '-')
}

export function formatConfidence(value: number): string {
  return `${Math.round(value * 100)}%`
}

export const riskLabel: Record<RiskLevel, string> = {
  critical: '紧急',
  high: '高风险',
  medium: '中风险',
  low: '低风险',
}

export const caseStatusLabel: Record<CaseStatus, string> = {
  open: '待处理',
  investigating: '调查中',
  awaiting_approval: '待审批',
  monitoring: '监控中',
  closed: '已结案',
}

export const claimStatusLabel: Record<ClaimStatus, string> = {
  ASSERTED: '已观测',
  INFERRED: '推导',
  CONFLICTED: '冲突',
  STALE: '已过期',
  UNKNOWN: '未知',
  REVOKED: '已撤销',
}
