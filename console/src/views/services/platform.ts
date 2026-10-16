export type ServiceStatus = 'ONLINE' | 'DEGRADED' | 'OFFLINE'

export type PlatformComponent = {
  name: string
  namespace: string
  status?: ServiceStatus | null
  usage?: string | null
  note?: string | null
  impact?: string | null
}

type ServiceRow = { name?: string; status?: string | null }

type IncomingComponent = {
  name?: string
  namespace?: string
  status?: string | null
  usage?: string | null
  note?: string | null
  impact?: string | null
}

const CATALOG: { name: string; namespace: string; serviceName?: string; note: string }[] = [
  { name: 'keel-server', namespace: 'keel-system', serviceName: 'keel-server', note: '注册中心 · 工具 · 审批' },
  { name: 'keel-llm', namespace: 'keel-system', serviceName: '薄网关', note: '模型网关' },
  { name: 'keel-audit', namespace: 'keel-system', serviceName: 'keel-audit', note: '审计' },
  { name: 'keel-gateway', namespace: 'keel-system', serviceName: 'keel-gateway', note: '入口网关' },
  { name: 'console', namespace: 'keel-system', note: '控制台' },
  { name: 'keel-devflow-sandbox', namespace: 'keel-devflow-sandbox', note: '一次性 Job，10 分钟超时，只出包镜像源' },
]

function asStatus(status: string | null | undefined): ServiceStatus | null {
  return status === 'ONLINE' || status === 'DEGRADED' || status === 'OFFLINE' ? status : null
}

function statusOf(services: ServiceRow[], name?: string): ServiceStatus | null {
  if (!name) return null
  return asStatus(services.find((row) => row.name === name)?.status)
}

/** Preserve missing probe and quota data as missing; prototype numbers are layout examples. */
export function platformRows(
  components: IncomingComponent[] | undefined,
  services: ServiceRow[],
): { rows: PlatformComponent[]; sandboxPlaceholder: boolean } {
  const rows: PlatformComponent[] = components?.length
    ? components.flatMap((row) => row.name ? [{
        name: row.name,
        namespace: row.namespace || '—',
        status: asStatus(row.status),
        usage: row.usage,
        note: row.note,
        impact: row.impact,
      }] : [])
    : CATALOG.map((item) => ({
        name: item.name,
        namespace: item.namespace,
        status: statusOf(services, item.serviceName),
        usage: null,
        note: item.note,
      }))
  return {
    rows,
    sandboxPlaceholder: rows.some((row) => row.name === 'keel-devflow-sandbox' && !row.usage && !row.status),
  }
}
