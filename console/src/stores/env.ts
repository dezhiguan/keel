import { defineStore } from 'pinia'
import { ref } from 'vue'

export type EnvFilter = 'all' | 'dev' | 'staging' | 'prod'

export const ENV_OPTIONS: { value: EnvFilter; label: string }[] = [
  { value: 'all', label: '全部环境' },
  { value: 'prod', label: 'prod' },
  { value: 'staging', label: 'staging' },
]

export const useEnvStore = defineStore('env', () => {
  const env = ref<EnvFilter>('all')
  return { env }
})
