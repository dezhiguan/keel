import { AxiosError, AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import { KeelApiError, newTraceparent, toKeelError, unwrap } from './http'
import { agentStatus } from '@/utils/format'

describe('unwrap', () => {
  it('returns data when code is OK', () => {
    expect(unwrap({ code: 'OK', traceId: 't', data: { a: 1 } })).toEqual({ a: 1 })
  })

  it('throws KeelApiError when code is not OK', () => {
    expect(() => unwrap({ code: 'SERVER_NOT_FOUND', message: '资源不存在', traceId: 't' })).toThrow(KeelApiError)
  })
})

describe('toKeelError', () => {
  it('keeps the contract error body from the response', () => {
    const response = {
      status: 400,
      statusText: 'Bad Request',
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: { code: 'SERVER_INVALID_PARAM', message: '请求参数不合法', traceId: 'abc', retryable: false },
    }
    const error = toKeelError(new AxiosError('bad', 'ERR_BAD_REQUEST', undefined, undefined, response))
    expect(error).toMatchObject({ code: 'SERVER_INVALID_PARAM', traceId: 'abc', retryable: false })
  })

  it('treats a missing response as a retryable network error', () => {
    expect(toKeelError(new AxiosError('down'))).toMatchObject({ code: 'NETWORK_ERROR', retryable: true })
  })
})

describe('newTraceparent', () => {
  it('produces a W3C traceparent', () => {
    expect(newTraceparent()).toMatch(/^00-[0-9a-f]{32}-[0-9a-f]{16}-01$/)
  })
})

describe('agentStatus', () => {
  it('maps contract statuses onto the three status tones', () => {
    expect(agentStatus('ONLINE').tone).toBe('ok')
    expect(agentStatus('DEGRADED').tone).toBe('degraded')
    expect(agentStatus('OFFLINE').tone).toBe('failed')
    expect(agentStatus(undefined).label).toBe('—')
  })
})
