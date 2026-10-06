import { describe, expect, it } from 'vitest'
import { mergeUsage, type AgentSummary } from './agents'

const agent = { name: 'echo', dailyBudgetCny: 30, gatePassed: null, score: null } as AgentSummary

describe('mergeUsage', () => {
  it('keeps the registry budget when usage has no budget', () => {
    const merged = mergeUsage(agent, { name: 'echo', calls24h: 2, costCny: 0.4, score: null })
    expect(merged.calls24h).toBe(2)
    expect(merged.costCny).toBe(0.4)
    expect(merged.dailyBudgetCny).toBe(30)
    expect(merged.gatePassed).toBeNull()
  })

  it('derives the gate from the eval score', () => {
    expect(mergeUsage(agent, { name: 'echo', score: 0.9 }).gatePassed).toBe(true)
    expect(mergeUsage(agent, { name: 'echo', score: 0.5 }).gatePassed).toBe(false)
  })
})
