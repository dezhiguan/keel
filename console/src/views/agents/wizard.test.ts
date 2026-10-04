import { describe, expect, it } from 'vitest'
import { nameError, registerBody, type WizardForm } from './wizard'

const form: WizardForm = {
  name: 'echo',
  displayName: '回声',
  ownerOrg: '研发效能组',
  ownerUser: 'amy',
  category: 'dev',
  template: 'echo',
  model: 'qwen-plus',
  dailyBudgetCny: 30,
  env: 'dev',
}

describe('wizard', () => {
  it('rejects a short name and a taken name', () => {
    expect(nameError('')).toBe('必填')
    expect(nameError('A')).not.toBe('')
    expect(nameError('echo')).toBe('')
    expect(nameError('echo', true)).toBe('名称已被占用')
  })

  it('sends the echo template with a cny budget', () => {
    expect(registerBody(form)).toMatchObject({
      name: 'echo',
      template: 'echo',
      env: 'dev',
      models: { default: 'qwen-plus', dailyBudgetCny: 30 },
    })
  })
})
