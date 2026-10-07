import type { Directive } from 'vue'
import { useUserStore } from '@/stores/user'

const HINT = '预览模式不能操作，登录后可用'

function apply(el: HTMLElement) {
  const readOnly = useUserStore().readOnly
  if (readOnly) {
    el.setAttribute('data-ro', '')
    el.setAttribute('data-write-locked', '')
    if ('disabled' in el) (el as HTMLButtonElement).disabled = true
    el.title = HINT
    return
  }
  if (el.hasAttribute('data-write-locked')) {
    el.removeAttribute('data-ro')
    el.removeAttribute('data-write-locked')
    if ('disabled' in el) (el as HTMLButtonElement).disabled = false
    el.removeAttribute('title')
  }
}

export const vWrite: Directive<HTMLElement> = {
  mounted: apply,
  updated: apply,
}
