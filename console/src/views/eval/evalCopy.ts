import type { EvalResult } from '@/api/eval'

export function formatScore(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—'
  return value.toFixed(2)
}

export function formatGateNumber(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—'
  const rounded = Math.round(value * 100) / 100
  return String(rounded)
}

/** Prototype sentence: business threshold, then the dev-agent note, then the worst regression. */
export function gateRule(result: EvalResult): string {
  const base = `规则：总分 ≥ ${formatGateNumber(result.gate?.minScore)}（研发智能体 ≥ 0.80），且任一维度退步不超过 ${formatGateNumber(result.gate?.maxRegression)}pt。`
  if (result.passed) return base
  const hit = (result.dimensions ?? []).find((row) => row.verdict === 'EXCEEDED' && row.deltaPt != null && row.tag)
  if (hit?.deltaPt != null && hit.tag) return `${base}"${hit.tag}"下降 ${Math.abs(hit.deltaPt)}pt。`
  if (result.reason) return `${base}${result.reason}。`
  return base
}

export function deltaText(delta: number | null | undefined): string {
  if (delta == null) return '—'
  return `${delta > 0 ? '+' : ''}${delta}pt`
}

export function expectedSubject(agent: string, result: EvalResult | null): string {
  const hit = (result?.dimensions ?? []).find((row) => row.verdict === 'EXCEEDED' && row.deltaPt != null && row.tag)
  const text = hit?.tag && hit.deltaPt != null ? `${hit.tag} ${hit.deltaPt}pt 标记为预期` : `${agent} 标记为预期`
  return text.slice(0, 128)
}
