import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElConfigProvider, ElDialog, ElForm, ElFormItem, ElInput, ElLoading, ElOption, ElSelect } from 'element-plus'
import 'element-plus/theme-chalk/base.css'
import 'element-plus/theme-chalk/el-config-provider.css'
import 'element-plus/theme-chalk/el-dialog.css'
import 'element-plus/theme-chalk/el-form.css'
import 'element-plus/theme-chalk/el-form-item.css'
import 'element-plus/theme-chalk/el-input.css'
import 'element-plus/theme-chalk/el-loading.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-option.css'
import 'element-plus/theme-chalk/el-select.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles/theme.css'
import './styles/console.css'
import App from './App.vue'
import { vWrite } from './directives/write'
import { router } from './router'

async function enableMocks() {
  if (!import.meta.env.DEV || import.meta.env.VITE_API_MOCK === 'off') return
  const { worker } = await import('./mocks/browser')
  await worker.start({ onUnhandledRequest: 'bypass', quiet: true })
}

enableMocks().then(() => {
  const app = createApp(App).use(createPinia()).directive('write', vWrite).use(router)
  for (const component of [ElConfigProvider, ElDialog, ElForm, ElFormItem, ElInput, ElOption, ElSelect]) {
    app.use(component)
  }
  app.use(ElLoading).mount('#app')
})
