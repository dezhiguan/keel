import { describe, expect, it } from 'vitest'
import { DEV_ROLE_TEMPLATES, DEV_ROLE_TOOLS, blankDevflow, devflowBody, devflowStepError, lockDevflowMode, nameError, registerBody, templateForLanguage, templatesFor, type WizardForm } from './wizard'

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
  env: 'staging',
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
    expect(devflowStepError(0, { ...blankDevflow('weekly-report'), title: '周报', goal: '汇总' })).toBe('')
    expect(devflowStepError(0, { ...blankDevflow('weekly-report'), title: '周报', goal: '汇总', template: 'java-spring' })).toContain('模板')
    expect(devflowBody({ ...blankDevflow('weekly-report'), title: '周报', goal: '汇总', layer: 'dev', mode: 'AUTO' }, '研发效能组')).toMatchObject({
      layer: 'DEV',
      mode: 'COLLAB',
      targetAgent: 'weekly-report',
    })
  })

  it('locks all seven research roles to the prototype templates', () => {
    expect(Object.keys(DEV_ROLE_TEMPLATES)).toHaveLength(7)
    for (const [name, template] of Object.entries(DEV_ROLE_TEMPLATES)) {
      const valid = { ...blankDevflow(name), title: name, goal: '处理研发任务' }
      expect(valid.template).toBe(template)
      expect(valid.tools).toEqual(DEV_ROLE_TOOLS[name])
      expect(devflowStepError(0, valid)).toBe('')
      expect(devflowStepError(0, { ...valid, template: 'chat-rag' })).toContain('模板')
      expect(devflowStepError(0, { ...valid, tools: [] })).toContain('工具')
    }
  })

  it('requires a declared child for a general supervisor', () => {
    const supervisor = { ...blankDevflow('qa-coordinator'), template: 'supervisor', title: '协调', goal: '编排',
      users: '研发', io: '任务到结果', success: '有证据' }
    expect(devflowStepError(0, supervisor)).toBe('')
    expect(devflowStepError(1, supervisor)).toContain('子智能体')
    expect(devflowStepError(1, { ...supervisor, delegates: ['qa-helper'] })).toBe('')
    expect(devflowBody({ ...supervisor, delegates: ['qa-helper'] }, 'QA').delegates).toEqual(['qa-helper'])
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
      env: 'staging',
      models: { default: 'qwen-plus', dailyBudgetCny: 30 },
      tools: ['git.branch.push'],
      knowledgeBases: ['demo'],
      promptType: 'chat',
    })
    expect(registerBody({ ...form, promptType: 'text' }).promptType).toBe('text')
    expect(registerBody({ ...form, model: 'chat-default' }).models).toMatchObject({
      default: 'chat-default',
      fallback: ['qwen3.8-flash', 'deepseek-flash'],
    })
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
