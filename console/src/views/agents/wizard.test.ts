import { describe, expect, it } from 'vitest'
import { blankDevflow, devflowBody, devflowStepError, lockDevflowMode, nameError, registerBody, templateForLanguage, templatesFor, type WizardForm } from './wizard'

const form: WizardForm = {
  name: 'echo',
  displayName: '回声',
  ownerOrg: '研发效能组',
  ownerUser: 'amy',
  category: 'dev',
  language: 'python',
  template: 'echo',
  model: 'qwen-plus',
  dailyBudgetCny: 30,
  env: 'dev',
  tools: ['git.branch.push'],
  knowledgeBases: ['demo'],
  approvalPolicyId: '',
  promptType: 'chat',
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

  it('preserves structured requirements for the real execution queue', () => {
    const body = devflowBody({ ...blankDevflow('qa-meta'), title: ' QA ', goal: ' 目标 ',
      users: ' 使用者 ', io: ' 输入输出 ', success: ' 成功标准 ' }, 'QA')
    expect(body).toMatchObject({ execution: true, layer: 'DEV', mode: 'COLLAB', goal: '目标',
      users: '使用者', io: '输入输出', success: '成功标准', seedCount: 0 })
  })

  it('sends the echo template with a cny budget', () => {
    expect(registerBody(form)).toMatchObject({
      name: 'echo',
      template: 'echo',
      env: 'dev',
      models: { default: 'qwen-plus', dailyBudgetCny: 30 },
      tools: ['git.branch.push'],
      knowledgeBases: ['demo'],
      promptType: 'chat',
    })
    expect(registerBody({ ...form, promptType: 'text' }).promptType).toBe('text')
    expect(registerBody(form)).not.toHaveProperty('approvalPolicyId')
    expect(registerBody({ ...form, approvalPolicyId: '9' })).toMatchObject({ approvalPolicyId: '9' })
    expect(registerBody({ ...form, language: 'java', template: 'java-spring' }).language).toBe('java')
  })

  it('keeps Java templates to the starter and supervisor', () => {
    expect(templatesFor('java').map(([id]) => id)).toEqual(['supervisor', 'java-spring'])
    expect(templatesFor('python').map(([id]) => id)).not.toContain('java-spring')
    expect(templateForLanguage('java', 'echo')).toBe('java-spring')
    expect(templateForLanguage('python', 'java-spring')).toBe('echo')
    expect(templateForLanguage('python', 'tool-agent')).toBe('tool-agent')
  })
})
