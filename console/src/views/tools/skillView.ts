export interface SkillCaseDraft {
  id?: string
  input: string
  expectedOutput?: string
}

export interface SkillContent {
  instructions: string
  tools?: string[]
  examples?: string[]
  cases?: SkillCaseDraft[]
}

export function splitList(value: string) {
  return [...new Set(value.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean))]
}

export function cleanExamples(values: string[]) {
  return [...new Set(values.map((item) => item.trim()).filter(Boolean))]
}

export function cleanCases(values: SkillCaseDraft[]) {
  return values.flatMap((item) => {
    const input = item.input.trim()
    if (!input) return []
    const id = item.id?.trim()
    return [{ ...(id ? { id } : {}), input, expectedOutput: (item.expectedOutput ?? '').trim() }]
  })
}

export function sameSkillContent(left: SkillContent, right: SkillContent) {
  return left.instructions.trim() === right.instructions.trim()
    && JSON.stringify(left.tools ?? []) === JSON.stringify(right.tools ?? [])
    && JSON.stringify(cleanExamples(left.examples ?? [])) === JSON.stringify(cleanExamples(right.examples ?? []))
    && JSON.stringify(cleanCases(left.cases ?? [])) === JSON.stringify(cleanCases(right.cases ?? []))
}

export function lineDiff(previous: string, next: string) {
  const before = previous.split('\n')
  const after = next.split('\n')
  const removed = before.filter((line) => !after.includes(line)).map((text) => ({ kind: 'del' as const, text }))
  const kept = after.map((text) => ({ kind: (before.includes(text) ? 'same' : 'add') as 'same' | 'add', text }))
  return [...removed, ...kept]
}

export function formatSkillWhen(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(date)
  const pick = (type: string) => parts.find((part) => part.type === type)?.value ?? ''
  return `${Number(pick('month'))}-${Number(pick('day'))} ${pick('hour')}:${pick('minute')}`
}

export function versionMeta(version: { createdBy?: string; createdAt?: string; note?: string }) {
  return [version.createdBy?.trim(), formatSkillWhen(version.createdAt), version.note?.trim()].filter(Boolean).join(' · ')
}

export function sourceLabel(source?: string) {
  return source === 'EXPERIENCE' ? '经验沉淀' : '人工编写'
}
