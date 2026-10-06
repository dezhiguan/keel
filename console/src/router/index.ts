import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'

// TODO(P1-15): menu visibility by platformRole (utils/permission.ts); the backend must re-check every call.
const routes: RouteRecordRaw[] = [
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
