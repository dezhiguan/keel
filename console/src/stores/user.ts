import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { logoutConsole } from '@/api/auth'
import { setConsoleReadOnly } from '@/api/http'
import { getCurrentUser, type CurrentUser } from '@/api/me'
import { clearPreviewEntered } from '@/router/redirect'

export const useUserStore = defineStore('user', () => {
  const user = ref<CurrentUser | null>(null)
  const loaded = ref(false)
  const readOnly = computed(() => user.value?.readOnly === true || user.value?.mode === 'PREVIEW')

  function apply(next: CurrentUser | null) {
    user.value = next
    setConsoleReadOnly(next?.readOnly === true || next?.mode === 'PREVIEW')
  }

  async function load() {
    try {
      apply(await getCurrentUser())
    } catch (error) {
      apply(null)
      throw error
    } finally {
      loaded.value = true
    }
  }

  function adopt(next: CurrentUser) {
    apply(next)
    loaded.value = true
  }

  async function logout() {
    try {
      await logoutConsole()
    } catch {
      // 会话已经失效时仍然离开控制台。
    }
    clearPreviewEntered()
    apply(null)
    loaded.value = true
  }

  return { user, loaded, readOnly, load, adopt, logout }
})
