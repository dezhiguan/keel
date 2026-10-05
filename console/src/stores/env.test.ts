import { describe, expect, it } from 'vitest'
import { ENV_OPTIONS } from './env'

describe('env options', () => {
  it('offers every environment the console can isolate', () => {
    expect(ENV_OPTIONS.map((option) => option.value)).toEqual(['all', 'prod', 'staging', 'test', 'dev'])
  })
})
