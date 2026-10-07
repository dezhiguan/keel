import { get, post, postVoid } from './http'
import type { CurrentUser } from './me'

export interface AuthOptions {
  methods: string[]
  previewEnabled: boolean
}

export interface CaptchaChallenge {
  captchaImage: string
  challengeId: string
}

export function getAuthOptions() {
  return get<AuthOptions>('/auth/options')
}

export function getCaptcha() {
  return get<CaptchaChallenge>('/auth/captcha')
}

export function sendLoginSms(phone: string) {
  return post<{ sent: boolean; expiresIn: number }>('/auth/sms/send', { phone })
}

export function loginWithPassword(body: { account: string; password: string; captcha?: string; challengeId?: string }) {
  return post<CurrentUser>('/auth/login/password', body)
}

export function loginWithSms(body: { phone: string; code: string }) {
  return post<CurrentUser>('/auth/login/sms', body)
}

export function logoutConsole() {
  return postVoid('/auth/logout')
}
