export interface NavItem {
  path: string
  title: string
  description: string
  icon: string
  group: '运营' | '观测' | '治理'
  badge?: string
}

/** 详情页和列表是并列路由，router-link-active 不会沿路径继承。侧栏按路径前缀高亮。 */
export function isNavActive(itemPath: string, currentPath: string): boolean {
  return currentPath === itemPath || currentPath.startsWith(`${itemPath}/`)
}

export const NAV: NavItem[] = [
  { path: '/overview', title: '运行总览', description: '运行状态与核心指标', icon: '◈', group: '运营' },
  { path: '/agents', title: '智能体管理', description: '配置、调试与发布', icon: '▣', group: '运营' },
  { path: '/jobs', title: '智能体研发', description: '从需求到交付', icon: '⚒', group: '运营' },
  { path: '/services', title: '基础服务', description: '平台组件与共享能力', icon: '◎', group: '运营' },
  { path: '/traces', title: '链路追踪', description: '调用过程与异常定位', icon: '≋', group: '观测' },
  { path: '/eval', title: '测评中心', description: '效果评测与质量把关', icon: '✓', group: '观测' },
  { path: '/models', title: '模型网关', description: '模型接入、路由与计费', icon: '⇄', group: '治理' },
  { path: '/tools', title: '工具与技能', description: 'MCP 工具与 Skill 管理', icon: '⚙', group: '治理' },
  { path: '/approvals', title: '审批中心', description: '人工审批与事项确认', icon: '⊜', group: '治理' },
  { path: '/audit', title: '审计中心', description: '操作留痕与记录查询', icon: '▤', group: '治理' },
]
