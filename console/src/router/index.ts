import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import ConsoleLayout from '@/layouts/ConsoleLayout.vue'
import PagePlaceholder from '@/components/PagePlaceholder.vue'

const placeholder = (path: string, title: string, task: string): RouteRecordRaw => ({
  path,
  component: PagePlaceholder,
  props: { title, task },
  meta: { title },
})

// TODO(P1-15): menu visibility by platformRole; the backend must re-check every call.
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: ConsoleLayout,
    redirect: '/overview',
    children: [
      { path: 'overview', component: () => import('@/views/overview/OverviewView.vue'), meta: { title: '总览' } },
      { path: 'agents', component: () => import('@/views/agents/AgentList.vue'), meta: { title: '智能体' } },
      placeholder('services', '共享服务', 'P2-12'),
      placeholder('traces', '链路追踪', 'P1-16'),
      placeholder('eval', '评测中心', 'P2-12'),
      placeholder('tools', '工具', 'P3-3'),
      placeholder('approvals', '审批中心', 'P3-3'),
      placeholder('audit', '审计中心', 'P1-15'),
      placeholder('models', '模型网关', 'P1-15'),
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/overview' },
]

export const router = createRouter({ history: createWebHistory(), routes })
