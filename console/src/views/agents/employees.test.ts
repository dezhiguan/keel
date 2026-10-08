import { describe, expect, it } from 'vitest'
import { lineageNodes, mergeEmployees } from './employees'

describe('mergeEmployees', () => {
  it('keeps registry numbers and fills missing employees from the prototype', () => {
    const cards = mergeEmployees([
      { name: 'askdb', displayName: '问数', category: 'biz', status: 'REGISTERED', calls24h: 0, costCny: 0.03, ownerUser: 'amy' },
      { name: 'echo', displayName: '回声', category: 'dev', template: 'echo', status: 'REGISTERED' },
    ])
    const askdb = cards.find((card) => card.name === 'askdb')
    expect(askdb).toMatchObject({ placeholder: false, calls: 0, cost: 0.03, owner: 'amy', source: '人工编写（现有）', layer: 'biz' })
    expect(cards.find((card) => card.name === 'meta-agent')).toMatchObject({ placeholder: true, layer: 'meta' })
    expect(cards.find((card) => card.name === 'echo')).toMatchObject({ placeholder: false, layer: 'dev', template: 'echo' })
  })

  it('uses a devflow job id from the registry as the source', () => {
    const cards = mergeEmployees([{ name: 'dev-lead', category: 'dev', devflowJobId: 'DF-0099', layer: 'DEV' }])
    expect(cards.find((card) => card.name === 'dev-lead')).toMatchObject({
      placeholder: false,
      jobId: 'DF-0099',
      source: 'meta-agent · DF-0099',
      layer: 'dev',
    })
  })

  it('draws meta-produced developers above the hand-written agents', () => {
    const { produced, manual, piped } = lineageNodes(mergeEmployees([]))
    expect(produced.map((card) => card.name)).toContain('dev-lead')
    expect(produced.map((card) => card.name)).not.toContain('prd-agent')
    expect(piped).toEqual(['ticket-triage', 'meeting-minutes'])
    expect(manual).toContain('careermate')
    expect(manual).toContain('askdb')
  })
})
