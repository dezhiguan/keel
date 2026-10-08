export interface NavItem {
  path: string
  title: string
  icon: string
  group: '运营' | '观测' | '治理'
  badge?: string
}

/** 详情页和列表是并列路由，router-link-active 不会沿路径继承。侧栏按路径前缀高亮。 */
export function isNavActive(itemPath: string, currentPath: string): boolean {
  return currentPath === itemPath || currentPath.startsWith(`${itemPath}/`)
}

export const NAV: NavItem[] = [
  { path: '/overview', title: '总览', icon: '◈', group: '运营' },
  { path: '/agents', title: '智能体', icon: '▣', group: '运营' },
  { path: '/jobs', title: '研发任务', icon: '⚒', group: '运营', badge: '新' },
  { path: '/services', title: '共享服务', icon: '◎', group: '运营' },
  { path: '/traces', title: '链路追踪', icon: '≋', group: '观测' },
  { path: '/eval', title: '评测中心', icon: '✓', group: '观测' },
  { path: '/prompts', title: '提示词', icon: '✎', group: '观测' },
  { path: '/tools', title: '工具', icon: '⚙', group: '治理' },
  { path: '/approvals', title: '审批中心', icon: '⊜', group: '治理' },
  { path: '/audit', title: '审计中心', icon: '▤', group: '治理' },
  { path: '/models', title: '模型网关', icon: '⇄', group: '治理' },
]
