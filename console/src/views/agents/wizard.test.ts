import { describe, expect, it } from 'vitest'
import { blankDevflow, devflowBody, devflowStepError, lockDevflowMode, nameError, registerBody, type WizardForm } from './wizard'

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

  it('locks research agents to collaborative mode and rejects a bad id', () => {
    expect(lockDevflowMode({ ...blankDevflow(), layer: 'dev', mode: 'AUTO' }).mode).toBe('COLLAB')
    const form = { ...blankDevflow('Meta'), title: '升级', goal: '改模板' }
    expect(devflowStepError(0, form)).toBe('智能体 ID 不合规')
    expect(devflowStepError(0, { ...blankDevflow('echo'), title: '回声', goal: '打通', kind: 'CREATE' }, ['echo'])).toContain('改造')
    expect(devflowStepError(0, { ...blankDevflow('meta-agent', 'CHANGE'), title: '改元', goal: '不行' })).toContain('元智能体')
    expect(devflowBody({ ...blankDevflow('weekly-report'), title: '周报', goal: '汇总', layer: 'dev', mode: 'AUTO' }, '研发效能组')).toMatchObject({
      layer: 'DEV',
      mode: 'COLLAB',
      targetAgent: 'weekly-report',
    })
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
