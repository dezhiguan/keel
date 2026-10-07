import { describe, expect, it } from 'vitest'
import { safeRedirect } from './redirect'

describe('safeRedirect', () => {
  it('keeps an in-app path and drops an open redirect', () => {
    expect(safeRedirect('/agents')).toBe('/agents')
    expect(safeRedirect('/prompts/ops-copilot/system?env=dev')).toBe('/prompts/ops-copilot/system?env=dev')
    expect(safeRedirect('https://evil.example')).toBe('/overview')
    expect(safeRedirect('//evil.example')).toBe('/overview')
    expect(safeRedirect('/%09/evil.example')).toBe('/%09/evil.example')
    expect(safeRedirect(undefined)).toBe('/overview')
  })
})
