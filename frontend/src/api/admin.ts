import { del, get, patch, post } from './http'
import type { GeoPolygon, Site, Zone, ZoneType } from './types'

// 用户、审计、景区写操作 —— 见 docs/api-contract.md §1/§2

export interface UserView {
  id: number
  username: string
  displayName: string
  status: 'ACTIVE' | 'DISABLED'
  allSites: boolean
  roles: string[]
  siteIds: number[]
}

export interface AuditLog {
  id: number
  actorId: number | null
  actorName: string | null
  action: string
  targetType: string | null
  targetId: string | null
  siteId: number | null
  outcome: string
  detail: string | null
  traceId: string | null
  createdAt: string
}

export interface ZoneBody {
  code?: string
  name: string
  zoneType: ZoneType
  boundary: GeoPolygon
  capacity?: number | null
  warnRatio?: number | null
  countSource?: string | null
  remark?: string | null
  version?: number
}

export interface SiteBody {
  name: string
  shortName: string
  capacity: number
  warnRatio: number
  seasonNote?: string | null
  description?: string | null
  status: Site['status']
}

export const ROLE_LABELS: Record<string, string> = {
  GROUP_ADMIN: '集团管理员',
  SITE_ADMIN: '景区管理员',
  OPERATOR: '运营人员',
  INSPECTOR: '巡检人员',
  REVIEWER: '复核人员',
  VIEWER: '只读查看者',
}

export const ZONE_TYPE_LABELS: Record<ZoneType, string> = {
  AREA: '游览区',
  ENTRANCE: '出入口',
  ATTRACTION: '景点/项目',
  SERVICE: '服务区',
  PARKING: '停车场',
  COMMERCIAL: '商业区',
}

export const adminApi = {
  users: () => get<UserView[]>('/users'),
  createUser: (body: { username: string; displayName: string; password: string; roles: string[]; allSites: boolean; siteIds: number[] }) =>
    post<UserView>('/users', body),
  updateUser: (id: number, body: { displayName: string; status: string; roles: string[]; allSites: boolean; siteIds: number[]; password?: string }) =>
    patch<UserView>(`/users/${id}`, body),
  audit: (limit = 100) => get<AuditLog[]>('/audit-logs', { limit }),
  updateSite: (id: number, body: SiteBody) => patch<Site>(`/scenic-sites/${id}`, body),
  createZone: (siteId: number, body: ZoneBody) => post<Zone>(`/scenic-sites/${siteId}/zones`, body),
  updateZone: (id: number, body: ZoneBody) => patch<Zone>(`/zones/${id}`, body),
  deleteZone: (id: number) => del(`/zones/${id}`),
}
