import { describe, expect, it } from 'vitest'
import { mergeGateway, prefetchAfter, sectionForTab } from './gatewayLoad'

describe('model gateway section loading', () => {
  it('maps the visible tab to the section that page needs', () => {
    expect(sectionForTab('model')).toBe('channels')
    expect(sectionForTab('key')).toBe('keys')
    expect(sectionForTab('route')).toBe('routes')
    expect(prefetchAfter('routes')).toEqual(['channels', 'keys'])
  })

  it('keeps fields a later section does not send', () => {
    const merged = mergeGateway(
      { models: [{ model: 'qwen' }], keys: [{ alias: 'askdb-dev' }] },
      { logicalRoutes: [{ logical: 'chat-default' }], keys: [{ alias: 'askdb-prod' }] },
    )
    expect(merged.models).toEqual([{ model: 'qwen' }])
    expect(merged.keys).toEqual([{ alias: 'askdb-prod' }])
    expect(merged.logicalRoutes).toEqual([{ logical: 'chat-default' }])
  })
})
