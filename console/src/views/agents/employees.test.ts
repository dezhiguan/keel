import { describe, expect, it } from 'vitest'
import { badgeColor, lineageNodes, mergeEmployees } from './employees'

describe('badgeColor', () => {
  it('uses the prototype colors for known agents', () => {
    expect(badgeColor('meta-agent')).toBe('#ff7a45')
    expect(badgeColor('dev-lead')).toBe('#f1b44c')
    expect(badgeColor('careermate')).toBe('#2ec4b6')
  })

  it('picks a stable palette color for agents outside the prototype list', () => {
    expect(badgeColor('echo')).toBe(badgeColor('echo'))
    expect(badgeColor('echo')).not.toBe('#8a97ab')
  })
})

describe('mergeEmployees', () => {
  it('lists only registered agents', () => {
    const cards = mergeEmployees([
      { name: 'askdb', displayName: '问数', category: 'biz', status: 'REGISTERED', calls24h: 0, costCny: 0.03, ownerUser: 'amy' },
      { name: 'echo', displayName: '回声', category: 'dev', template: 'echo', status: 'REGISTERED' },
    ])
    expect(cards.map((card) => card.name)).toEqual(['askdb', 'echo'])
    expect(cards.find((card) => card.name === 'askdb')).toMatchObject({ placeholder: false, calls: 0, cost: 0.03, owner: 'amy', source: '人工编写（现有）', layer: 'biz', color: '#8fb6ff' })
    expect(cards.find((card) => card.name === 'echo')).toMatchObject({ placeholder: false, layer: 'dev', template: 'echo' })
  })

  it('uses a devflow job id from the registry as the source', () => {
    const cards = mergeEmployees([{ name: 'dev-lead', category: 'dev', devflowJobId: 'DF-0099', layer: 'DEV' }])
    expect(cards).toHaveLength(1)
    expect(cards[0]).toMatchObject({
      placeholder: false,
      jobId: 'DF-0099',
      source: 'meta-agent · DF-0099',
      layer: 'dev',
    })
  })

  it('draws meta-produced developers above the hand-written agents', () => {
    const cards = mergeEmployees([
      { name: 'dev-lead', category: 'dev', devflowJobId: 'DF-0099', layer: 'DEV' },
      { name: 'ticket-triage', category: 'biz', devflowJobId: 'DF-0012' },
      { name: 'meeting-minutes', category: 'biz', devflowJobId: 'DF-0014' },
      { name: 'careermate', category: 'biz' },
      { name: 'askdb', category: 'biz' },
      { name: 'prd-agent', category: 'dev' },
    ])
    const { produced, manual, piped } = lineageNodes(cards)
    expect(produced.map((card) => card.name)).toContain('dev-lead')
    expect(produced.map((card) => card.name)).not.toContain('prd-agent')
    expect(piped).toEqual(['ticket-triage', 'meeting-minutes'])
    expect(manual).toContain('careermate')
    expect(manual).toContain('askdb')
  })
})
