export function splitList(value: string) {
  return [...new Set(value.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean))]
}

export function sourceLabel(source?: string) {
  return source === 'EXPERIENCE' ? '经验沉淀' : '人工编写'
}
