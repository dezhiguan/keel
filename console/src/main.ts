import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/theme.css'
import './styles/console.css'
import App from './App.vue'
import { router } from './router'

async function enableMocks() {
  if (!import.meta.env.DEV || import.meta.env.VITE_API_MOCK === 'off') return
  const { worker } = await import('./mocks/browser')
  await worker.start({ onUnhandledRequest: 'bypass', quiet: true })
}

enableMocks().then(() => {
  createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
})
