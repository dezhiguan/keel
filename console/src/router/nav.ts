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
  { path: '/overview', title: '运行总览', icon: '◈', group: '运营' },
  { path: '/agents', title: '智能体目录', icon: '▣', group: '运营' },
  { path: '/jobs', title: '智能体生产线', icon: '⚒', group: '运营' },
  { path: '/services', title: '平台与能力服务', icon: '◎', group: '运营' },
  { path: '/traces', title: '调用链路', icon: '≋', group: '观测' },
  { path: '/eval', title: '评测与门禁', icon: '✓', group: '观测' },
  { path: '/prompts', title: '提示词工程', icon: '✎', group: '观测' },
  { path: '/tools', title: '工具与技能', icon: '⚙', group: '治理' },
  { path: '/approvals', title: '待我处理', icon: '⊜', group: '治理' },
  { path: '/audit', title: '审计留痕', icon: '▤', group: '治理' },
  { path: '/models', title: '模型与成本', icon: '⇄', group: '治理' },
]
