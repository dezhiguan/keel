export const NAME = /^[a-z][a-z0-9-]{1,38}[a-z0-9]$/

export interface WizardForm {
  name: string
  displayName: string
  ownerOrg: string
  ownerUser: string
  category: 'biz' | 'dev'
  template: string
  model: string
  dailyBudgetCny: number
  env: 'dev' | 'test' | 'staging' | 'prod'
}

export function nameError(name: string, taken = false): string {
  if (!name) return '必填'
  if (!NAME.test(name)) return '小写字母开头，3～40 位，只能用小写字母、数字、连字符'
  if (taken) return '名称已被占用'
  return ''
}

export function registerBody(form: WizardForm) {
  return {
    name: form.name.trim(),
    displayName: form.displayName.trim() || form.name.trim(),
    category: form.category,
    ownerOrg: form.ownerOrg.trim(),
    ownerUser: form.ownerUser.trim(),
    env: form.env,
    runtime: 'code' as const,
    language: 'python',
    template: form.template,
    models: {
      default: form.model,
      fallback: [] as string[],
      dailyBudgetCny: form.dailyBudgetCny,
    },
  }
}
