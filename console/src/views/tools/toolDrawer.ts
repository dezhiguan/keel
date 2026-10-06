export interface ToolVersionRow {
  version?: string
  change?: string
  breaking?: boolean
  at?: string | null
}

export type VersionKind = 'impl' | 'desc' | 'break' | 'risk'
export type RiskLevel = 'LOW' | 'MID' | 'HIGH'

export const DEPRECATE_DEADLINE = '2026-11-30'
export const BREAK_DEADLINE = '2026-12-31'

export const VERSION_KINDS: { id: VersionKind; title: string; hint: string }[] = [
  { id: 'impl', title: '只改内部实现', hint: '接口不变，直接发布，监控成功率和延迟' },
  { id: 'desc', title: '改描述 / 参数说明 / 新增可选参数', hint: '会改变大模型的用法：自动对所有依赖方跑回归评测' },
  { id: 'break', title: '破坏兼容（删参数、改返回格式）', hint: '' },
  { id: 'risk', title: '调高风险等级', hint: '立即生效，通知依赖方' },
]

/** Newest version first. Missing timestamps stay at the bottom. */
export function versionRows(versions: ToolVersionRow[] | undefined): ToolVersionRow[] {
  return [...(versions ?? [])].sort((a, b) => String(b.at ?? '').localeCompare(String(a.at ?? '')))
}

export function versionTime(at?: string | null): string {
  if (!at || at.length < 10) return '—'
  return at.slice(5, 10)
}

export function nextVersion(version?: string | null): string {
  const match = /^v(\d+)$/i.exec(version ?? '')
  return match ? `v${Number(match[1]) + 1}` : 'v2'
}

export function raisedRisk(risk?: RiskLevel | null): RiskLevel {
  if (risk === 'LOW') return 'MID'
  if (risk === 'MID') return 'HIGH'
  return 'HIGH'
}

export function prodDependents<T extends { env?: string | null }>(rows: T[] | undefined): T[] {
  return (rows ?? []).filter((row) => row.env === 'prod')
}

export function otherDependents<T extends { env?: string | null }>(rows: T[] | undefined): T[] {
  return (rows ?? []).filter((row) => row.env !== 'prod')
}

export function replacementId(value: string): string {
  return value.replace('（新建）', '')
}

export function breakHint(name: string): string {
  return `不能改原工具：创建 ${name}.v2，原工具进入废弃期`
}
