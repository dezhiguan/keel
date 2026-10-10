import type { AgentDetail } from '@/api/agents'
import type { TraceNode } from '@/api/traces'

export type DebugMode = 'chat' | 'task' | 'service' | 'schedule'

export const MODE_LABEL: Record<DebugMode, { name: string; hint: string }> = {
  chat: { name: '对话式', hint: '用户多轮问答' },
  task: { name: '任务式', hint: '提交一件事，看执行步骤和产出' },
  service: { name: '后台式', hint: '没有用户入口，由事件、其他系统或上级智能体调用' },
  schedule: { name: '定时任务', hint: '按计划自动运行' },
}

export function debugMode(agent?: Pick<AgentDetail, 'interaction'> | null): DebugMode {
  const mode = agent?.interaction?.mode
  return mode && mode in MODE_LABEL ? mode : 'chat'
}

/** 不能调试时返回原因；可以调试返回 null。 */
export function debugBlocked(status?: string | null) {
  if (status === 'DRAFT') return '还没注册，不能调试。先 keel register 或在向导里完成注册。'
  if (status === 'RETIRED') return '已下线，虚拟 Key 已吊销，不能再调用。历史运行仍可在链路追踪里查看。'
  return null
}

export function debugWarning(status?: string | null) {
  if (status === 'OFFLINE') return '当前离线（没有就绪实例），调用大概率失败。'
  if (status === 'DEGRADED') return '当前降级运行，回复可能来自降级模型。'
  return null
}

const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六', '周日']
const pad = (value: string) => value.padStart(2, '0')
const isNum = (value: string) => /^\d+$/.test(value)

/** 只翻译常见写法，其余原样返回 null，由页面只显示 cron 原文。 */
export function describeCron(expr?: string | null): string | null {
  const parts = (expr ?? '').trim().split(/\s+/)
  if (parts.length !== 5) return null
  const [min, hour, day, month, week] = parts
  const everyMin = /^\*\/(\d+)$/.exec(min)
  if (everyMin && hour === '*' && day === '*' && month === '*' && week === '*') return `每 ${everyMin[1]} 分钟`
  if (!isNum(min)) return null
  const everyHour = /^\*\/(\d+)$/.exec(hour)
  if (everyHour && day === '*' && month === '*' && week === '*') return `每 ${everyHour[1]} 小时的第 ${min} 分`
  if (hour === '*' && day === '*' && month === '*' && week === '*') return `每小时第 ${min} 分`
  if (!isNum(hour)) return null
  const at = `${pad(hour)}:${pad(min)}`
  if (month !== '*') return null
  if (day === '*' && week === '*') return `每天 ${at}`
  if (day === '*' && week === '1-5') return `工作日 ${at}`
  if (day === '*' && isNum(week) && Number(week) <= 7) return `每${WEEK[Number(week)]} ${at}`
  if (isNum(day) && week === '*') return `每月 ${day} 日 ${at}`
  return null
}

export interface StepRow {
  id: string
  name: string
  type: string
  depth: number
  durationMs: number | null
  status: NonNullable<TraceNode['status']>
  output: string
}

const TYPE_LABEL: Record<string, string> = {
  agent: '智能体', generation: '模型', tool: '工具', retriever: '检索', guardrail: '护栏', event: '事件',
}

/** 链路节点按开始时间排成步骤；根 agent 节点就是整次运行，不单列。 */
export function stepRows(nodes: TraceNode[] = []): StepRow[] {
  return [...nodes]
    .filter((node) => (node.depth ?? 0) > 0)
    .sort((a, b) => (a.startMs ?? 0) - (b.startMs ?? 0))
    .map((node, index) => ({
      id: node.id ?? String(index),
      name: node.shortName || node.name || '—',
      type: TYPE_LABEL[node.type ?? ''] ?? node.type ?? '—',
      depth: Math.max(0, (node.depth ?? 1) - 1),
      durationMs: node.durationMs ?? null,
      status: node.status ?? 'ok',
      output: node.outputSummary && node.outputSummary !== node.name ? node.outputSummary : '',
    }))
}
