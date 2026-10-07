import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { toKeelError } from '@/api/http'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import { previewEntered, safeRedirect } from '@/router/redirect'
import { useUserStore } from '@/stores/user'

// TODO(P1-15): menu visibility by platformRole (utils/permission.ts); the backend must re-check every call.
const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/login/LoginView.vue'), meta: { title: '登录' } },
  {
    path: '/',
    component: ConsoleLayout,
    redirect: '/overview',
    children: [
      { path: 'overview', component: () => import('@/views/overview/OverviewView.vue'), meta: { title: '总览' } },
      { path: 'agents', component: () => import('@/views/agents/AgentList.vue'), meta: { title: '智能体' } },
      { path: 'agents/new', component: () => import('@/views/agents/AgentCreateWizard.vue'), meta: { title: '新建智能体' } },
      { path: 'agents/:name', redirect: (to) => ({ path: '/agents', query: { drawer: String(to.params.name) } }) },
      { path: 'services', component: () => import('@/views/services/SharedServices.vue'), meta: { title: '共享服务' } },
      { path: 'traces', component: () => import('@/views/trace/TraceList.vue'), meta: { title: '链路追踪' } },
      { path: 'traces/:id', component: () => import('@/views/trace/TraceDetail.vue'), meta: { title: '链路详情' } },
      { path: 'quality', component: () => import('@/views/quality/QualityView.vue'), meta: { title: '质量中心' } },
      { path: 'eval', component: () => import('@/views/eval/EvalView.vue'), meta: { title: '评测中心' } },
      { path: 'prompts', component: () => import('@/views/prompts/PromptList.vue'), meta: { title: '提示词' } },
      { path: 'prompts/:agent/:name', component: () => import('@/views/prompts/PromptDetail.vue'), meta: { title: '提示词' } },
      { path: 'tools', component: () => import('@/views/tools/ToolRegistry.vue'), meta: { title: '工具' } },
      { path: 'tools/:name', redirect: (to) => ({ path: '/tools', query: { tool: String(to.params.name) } }) },
      { path: 'approvals', component: () => import('@/views/tools/ApprovalInbox.vue'), meta: { title: '审批中心' } },
      { path: 'audit', component: () => import('@/views/audit/AuditView.vue'), meta: { title: '审计中心' } },
      { path: 'models', component: () => import('@/views/models/ModelGateway.vue'), meta: { title: '模型网关' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/overview' },
]

export const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to) => {
  const user = useUserStore()
  if (!user.loaded) {
    try {
      await user.load()
    } catch (error) {
      const code = toKeelError(error).code
      if (to.path !== '/login' && (code === 'AUTH_UNAUTHENTICATED' || code === 'AUTH_TOKEN_EXPIRED' || code === 'AUTH_TOKEN_AUDIENCE')) {
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
  }
  if (to.path === '/login') {
    if (user.user?.mode === 'USER') return safeRedirect(to.query.redirect)
    return true
  }
  if (!user.user) return { path: '/login', query: { redirect: to.fullPath } }
  if (user.user.mode === 'PREVIEW' && !previewEntered()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (user.readOnly && to.path === '/agents/new') return '/agents'
})
