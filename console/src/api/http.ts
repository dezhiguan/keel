import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import type { components } from './schema'

type ApiError = components['schemas']['ApiError'] & { details?: Record<string, unknown> }

export class KeelApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly traceId: string | undefined,
    readonly retryable: boolean,
    readonly details?: Record<string, unknown>,
  ) {
    super(message)
  }
}

const SUCCESS = 'OK'
let readOnly = false
let refreshing: Promise<void> | null = null

export function setConsoleReadOnly(value: boolean) {
  readOnly = value
}

const instance = axios.create({ baseURL: '/api/v1', timeout: 15000, withCredentials: true })

export function newTraceparent(): string {
  const hex = (bytes: number) =>
    Array.from(crypto.getRandomValues(new Uint8Array(bytes)), (b) => b.toString(16).padStart(2, '0')).join('')
  return `00-${hex(16)}-${hex(8)}-01`
}

instance.interceptors.request.use((config) => {
  config.headers.set('traceparent', newTraceparent())
  const method = (config.method ?? 'get').toLowerCase()
  if (method !== 'get' && method !== 'head') {
    config.headers.set('X-Keel-Console', '1')
    const url = String(config.url ?? '')
    if (readOnly && !url.includes('/auth/')) {
      throw new KeelApiError('AUTH_PREVIEW_READONLY', '预览模式只能查看，登录后才能操作', undefined, false)
    }
  }
  return config
})

instance.interceptors.response.use(undefined, async (error: AxiosError) => {
  const config = error.config as (InternalAxiosRequestConfig & { keelRetried?: boolean }) | undefined
  const keel = toKeelError(error)
  if (keel.code === 'AUTH_TOKEN_EXPIRED' && config && !config.keelRetried && !String(config.url ?? '').includes('/auth/refresh')) {
    config.keelRetried = true
    try {
      await refreshSession()
      return instance.request(config)
    } catch (refreshError) {
      await sendToLogin()
      return Promise.reject(refreshError)
    }
  }
  return Promise.reject(error)
})

async function refreshSession() {
  if (!refreshing) {
    refreshing = instance.post('/auth/refresh').then(() => undefined).finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

async function sendToLogin() {
  const { router } = await import('@/router')
  const current = router.currentRoute.value
  if (current.path === '/login') return
  await router.push({ path: '/login', query: { redirect: current.fullPath } })
}

export function toKeelError(error: unknown): KeelApiError {
  if (error instanceof KeelApiError) return error
  if (error instanceof AxiosError) {
    const body = error.response?.data as Partial<ApiError> | undefined
    if (body?.code) {
      return new KeelApiError(body.code, body.message ?? friendlyFallback(body.code), body.traceId, body.retryable ?? false, body.details)
    }
    if (!error.response) return new KeelApiError('NETWORK_ERROR', '网络不太稳定，请检查网络后再试', undefined, true)
    return new KeelApiError('NETWORK_ERROR', '网络不太稳定，请检查网络后再试', undefined, true)
  }
  return new KeelApiError('UNKNOWN', '出现了一点问题，请稍后再试', undefined, false)
}

function friendlyFallback(code: string) {
  if (code === 'AUTH_BAD_CREDENTIALS') return '账号或密码不正确。没有账号或忘记密码，请联系平台管理员'
  if (code === 'AUTH_LOCKED') return '登录失败次数过多，请 15 分钟后再试'
  if (code === 'AUTH_GATEWAY_UNAVAILABLE') return '登录服务暂时不可用，请稍后再试'
  return code
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

export async function put<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  try {
    const response = await instance.put(url, body, config)
    return unwrap<T>(response.data)
  } catch (error) {
    throw toKeelError(error)
  }
}

export async function post<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  try {
    const response = await instance.post(url, body, config)
    return unwrap<T>(response.data)
  } catch (error) {
    throw toKeelError(error)
  }
}

export async function postVoid(url: string, body?: unknown): Promise<void> {
  try {
    const response = await instance.post(url, body)
    if (response.status === 204 || response.data == null || response.data === '') return
    unwrap(response.data)
  } catch (error) {
    throw toKeelError(error)
  }
}
