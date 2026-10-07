/** 只接受站内相对路径，避免登录后被带到外站。 */
export function safeRedirect(raw: unknown): string {
  if (typeof raw !== 'string') return '/overview'
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\') || raw.includes('://')) return '/overview'
  return raw
}

const PREVIEW_KEY = 'keel.console.preview'

export function markPreviewEntered() {
  sessionStorage.setItem(PREVIEW_KEY, '1')
}

export function clearPreviewEntered() {
  sessionStorage.removeItem(PREVIEW_KEY)
}

export function previewEntered() {
  return sessionStorage.getItem(PREVIEW_KEY) === '1'
}
