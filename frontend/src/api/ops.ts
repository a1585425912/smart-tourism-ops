import { get, post } from './http'
import type { DataMeta, GeoPoint, Page } from './types'
import type { FlowSummary } from './flow'

// 运营总览、业态、团队、画像、接入 —— 见 docs/api-contract.md §4

export interface Alert {
  level: 'INFO' | 'WARN' | 'CRITICAL'
  type: 'ZONE_LOAD' | 'DEVICE_OFFLINE' | 'DATA_STALE' | 'QUEUE' | 'REVIEW' | 'INSPECTION' | 'FORECAST'
  siteId: number
  title: string
  detail: string
  at: string
  ref: string | null
}

export interface OverviewSite {
  siteId: number
  name: string
  shortName: string
  status: string
  capacity: number
  center: GeoPoint
  today: FlowSummary
  zones: { warn: number; full: number; total: number }
  devices: { online: number; total: number; offline: number }
  reviews: { today: number; negative: number; avgRating: number | null }
  inspections: { open: number; overdue: number; openIssues: number }
  queues: { name: string; waitMin: number | null }[]
  groupsToday: { groups: number; visitors: number }
}

export interface Overview {
  now: string
  sites: OverviewSite[]
  totals: { entries: number | null; inPark: number | null; capacity: number }
  alerts: Alert[]
  meta: DataMeta
}

export type BusinessCategory = 'HOTEL' | 'CATERING' | 'RETAIL' | 'TICKET' | 'ENTERTAINMENT'

export interface BusinessSummary {
  siteId: number
  start: string
  end: string
  currency: string
  amountBasis: string
  byCategory: {
    category: BusinessCategory
    revenue: number | null
    orders: number | null
    guests: number | null
    units: number
    avgOccupancy: number | null
  }[]
  daily: { date: string; category: BusinessCategory; revenue: number | null }[]
  topUnits: { unitId: number; name: string; category: BusinessCategory; revenue: number | null }[]
  meta: DataMeta
}

export interface BusinessUnit {
  id: number
  siteId: number
  code: string
  name: string
  category: BusinessCategory
  lon: number | null
  lat: number | null
  capacity: number | null
  dataMode: string
}

export interface BusinessLinkage {
  siteId: number
  points: {
    date: string
    entries: number | null
    hotelOccupancy: number | null
    cateringRevenue: number | null
    retailRevenue: number | null
  }[]
  correlations: { pair: string; r: number | null; n: number }[]
  insights: string[]
  meta: DataMeta
}

export interface TourGroup {
  id: number
  groupCode: string
  agencyId: number
  agencyName: string
  siteId: number
  siteName: string
  visitDate: string
  plannedSize: number
  enteredCount: number
  guideName: string | null
  guidePhoneMasked: string | null
  guideCertNo: string | null
  originRegion: string | null
  status: 'BOOKED' | 'ENTERED' | 'LEFT' | 'CANCELLED'
  enteredAt: string | null
  dataMode: string
}

export interface GroupStats {
  totalGroups: number
  totalVisitors: number
  byAgency: { agencyId: number; name: string; groups: number; visitors: number }[]
  byOrigin: { region: string; groups: number; visitors: number }[]
  daily: { date: string; groups: number; visitors: number }[]
  meta: DataMeta
}

export interface Agency {
  id: number
  code: string
  name: string
  licenseNo: string | null
  region: string | null
  contactMasked: string | null
  dataMode: string
}

export type ProfileDimension = 'ORIGIN' | 'AGE' | 'GENDER' | 'CHANNEL' | 'STAY'

export interface VisitorProfile {
  siteId: number
  month: string
  sampleSize: number
  coverage: number
  dimensions: Partial<Record<ProfileDimension, { bucket: string; visitors: number; share: number }[]>>
  notes: string[]
  meta: DataMeta
}

export interface IntegrationSource {
  id: number
  code: string
  name: string
  sourceType: string
  dataMode: 'REAL' | 'DEMO'
  siteId: number | null
  provider: string | null
  licenseNote: string | null
  refreshSeconds: number | null
  watermark: string | null
  lastSuccessAt: string | null
  lastErrorAt: string | null
  lastError: string | null
  health: 'OK' | 'FAILED' | 'STALE' | 'UNKNOWN'
  enabled: boolean
}

export interface Job {
  id: number
  jobType: string
  idempotencyKey: string | null
  refId: number | null
  payload: unknown
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  attempts: number
  maxAttempts: number
  leaseOwner: string | null
  leaseUntil: string | null
  heartbeatAt: string | null
  cancelRequested: boolean
  runAfter: string
  lastError: string | null
  createdBy: number | null
  createdAt: string
  updatedAt: string
  finishedAt: string | null
}

export const opsApi = {
  overview: () => get<Overview>('/overview'),
  businessSummary: (siteId: number, start: string, end: string) => get<BusinessSummary>('/business/summary', { siteId, start, end }),
  businessUnits: (siteId: number, category?: string) => get<BusinessUnit[]>('/business/units', { siteId, category }),
  businessLinkage: (siteId: number, days = 30) => get<BusinessLinkage>('/business/linkage', { siteId, days }),
  groups: (p: { siteId?: number; date?: string; agencyId?: number; status?: string; page?: number; size?: number }) =>
    get<Page<TourGroup>>('/groups', p),
  groupStats: (siteId: number, start: string, end: string) => get<GroupStats>('/groups/stats', { siteId, start, end }),
  agencies: () => get<Agency[]>('/agencies'),
  profile: (siteId: number, month: string) => get<VisitorProfile>('/profiles', { siteId, month }),
  integrations: () => get<IntegrationSource[]>('/integrations'),
  jobs: (p: { type?: string; status?: string; limit?: number } = {}) => get<Job[]>('/jobs', p),
  retryJob: (id: number) => post<Job>(`/jobs/${id}/retry`),
}
