import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import type { components } from './schema'

type ApiError = components['schemas']['ApiError']

export class KeelApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly traceId: string | undefined,
    readonly retryable: boolean,
  ) {
    super(message)
  }
}

const SUCCESS = 'OK'

const instance = axios.create({ baseURL: '/api/v1', timeout: 15000 })

export function newTraceparent(): string {
  const hex = (bytes: number) =>
    Array.from(crypto.getRandomValues(new Uint8Array(bytes)), (b) => b.toString(16).padStart(2, '0')).join('')
  return `00-${hex(16)}-${hex(8)}-01`
}

instance.interceptors.request.use((config) => {
  config.headers.set('traceparent', newTraceparent())
  // TODO(P0-5): attach the auth-gateway access token (aud=keel-api) once login is wired.
  return config
})

export function toKeelError(error: unknown): KeelApiError {
  if (error instanceof KeelApiError) return error
  if (error instanceof AxiosError) {
    const body = error.response?.data as Partial<ApiError> | undefined
    if (body?.code) {
      return new KeelApiError(body.code, body.message ?? body.code, body.traceId, body.retryable ?? false)
    }
    return new KeelApiError('NETWORK_ERROR', error.message, undefined, true)
  }
  return new KeelApiError('UNKNOWN', String(error), undefined, false)
}

export function unwrap<T>(body: { code: string; message?: string | null; traceId: string; data?: T }): T {
  if (body.code !== SUCCESS) {
    throw new KeelApiError(body.code, body.message ?? body.code, body.traceId, false)
  }
  return body.data as T
}

export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  try {
    const response = await instance.get(url, config)
    return unwrap<T>(response.data)
  } catch (error) {
    throw toKeelError(error)
  }
}
