// 指挥大屏使用的接口与类型, 与 docs/api-contract.md §2-§7 对应。
import { get, post } from './http'
import type { DataMeta, Device, GeoPoint, Page, PlaybackSession, Site, Zone } from './types'

export interface FlowSummary {
  siteId: number
  siteName: string
  start: string
  end: string
  entries: number | null
  exits: number | null
  inParkCurrent: number | null
  inParkPeak: number | null
  inParkPeakAt: string | null
  capacity: number
  utilization: number | null
  loadLevel: 'NORMAL' | 'WARN' | 'FULL' | 'UNKNOWN'
  entriesYoy: number | null
  entriesDod: number | null
  notes: string[]
  meta: DataMeta
}

export interface FlowTrend {
  siteId: number
  zoneId: number | null
  metric: string
  granularity: 'HOUR' | 'DAY'
  aggregation: 'sum' | 'max' | 'last'
  points: { t: string; value: number }[]
  gaps: { start: string; end: string }[]
  meta: DataMeta
}

export type LoadLevel = 'LOW' | 'NORMAL' | 'WARN' | 'FULL' | 'UNKNOWN'

export interface ZoneLoad {
  zoneId: number
  zoneName: string
  zoneType: string
  count: number | null
  capacity: number | null
  ratio: number | null
  level: LoadLevel
  at: string | null
  countSource: string | null
  meta: DataMeta
}

export interface QueueStatus {
  pointId: number
  name: string
  pointType: 'RIDE' | 'TICKET' | 'GATE' | 'SHOW' | 'SERVICE'
  siteId: number
  zoneId: number | null
  lon: number
  lat: number
  queueLength: number | null
  serviceRatePerMin: number
  estimatedWaitMin: number | null
  measuredWaitMin: number | null
  method: string
  at: string | null
  predicted: { t: string; waitMin: number }[]
  meta: DataMeta
}

export interface Weather {
  tempMax: number | null
  tempMin: number | null
  tempMean: number | null
  precipitation: number | null
  snowfall: number | null
  windMax: number | null
  weatherCode: number | null
  isForecast: boolean
  source: string
}

export interface CalendarDay {
  date: string
  holiday: string | null
  isOffDay: boolean
  weather: Weather | null
}

export interface Backtest {
  modelName: string
  modelVersion: string
  testStart: string
  testEnd: string
  sampleDays: number
  mape: number | null
  mae: number | null
  baselineName: string
  baselineMape: number | null
  baselineMae: number | null
}

export interface DailyForecast {
  siteId: number
  siteName: string
  metric: 'ENTRY'
  model: { name: string; version: string; trainedAt: string; features: string[] }
  points: {
    date: string
    predicted: number
    lower: number
    upper: number
    actual: number | null
    holiday: string | null
    isOffDay: boolean
    weather: Weather | null
    drivers: string[]
  }[]
  backtest: Backtest | null
  meta: DataMeta
}

export interface HourlyForecast {
  siteId: number
  date: string
  points: { t: string; predictedInPark: number; predictedEntries: number }[]
  peak: { t: string; value: number } | null
  zonePeaks: {
    zoneId: number
    zoneName: string
    capacity: number | null
    peak: number
    peakAt: string
    ratio: number | null
    level: LoadLevel
    staffSuggested: number
  }[]
  recommendations: { level: 'INFO' | 'WARN' | 'CRITICAL'; title: string; detail: string; zoneId: number | null }[]
  meta: DataMeta
}

export interface Alert {
  level: 'INFO' | 'WARN' | 'CRITICAL'
  type: string
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

export interface ReviewItem {
  id: number
  siteId: number
  siteName: string
  platform: string
  authorMasked: string | null
  rating: number | null
  contentMasked: string
  publishedAt: string
  isComplaint: boolean
  dataMode: string
  analysisStatus: string
  analysis: { sentiment: string; topics: string[]; summary: string | null } | null
}

export interface ReviewSummary {
  siteId: number | null
  start: string
  end: string
  total: number
  avgRating: number | null
  complaintCount: number
  bySentiment: Record<string, number>
  byTopic: { topic: string; name: string; count: number; negative: number }[]
  byPlatform: { platform: string; count: number }[]
  daily: { date: string; total: number; negative: number }[]
  hotIssues: { topic: string; name: string; count: number; samples: { reviewId: number; text: string }[] }[]
  meta: DataMeta
}

export interface BusinessSummary {
  siteId: number
  start: string
  end: string
  currency: string
  amountBasis: string
  byCategory: {
    category: 'HOTEL' | 'CATERING' | 'RETAIL' | 'TICKET' | 'ENTERTAINMENT'
    revenue: number | null
    orders: number | null
    guests: number | null
    units: number
    avgOccupancy: number | null
  }[]
  daily: { date: string; category: string; revenue: number | null }[]
  topUnits: { unitId: number; name: string; category: string; revenue: number | null }[]
  meta: DataMeta
}

export interface GroupStats {
  totalGroups: number
  totalVisitors: number
  byAgency: { agencyId: number; name: string; groups: number; visitors: number }[]
  byOrigin: { region: string; groups: number; visitors: number }[]
  daily: { date: string; groups: number; visitors: number }[]
  meta: DataMeta
}

export const screenApi = {
  overview: () => get<Overview>('/overview'),
  sites: () => get<Site[]>('/scenic-sites'),
  zones: (siteId: number) => get<Zone[]>(`/scenic-sites/${siteId}/zones`),
  zoneLoads: (siteId: number) => get<ZoneLoad[]>('/flows/zones', { siteId }),
  flowSummary: (siteId: number, start: string, end: string) => get<FlowSummary>('/flows/summary', { siteId, start, end }),
  flowTrend: (p: { siteId: number; zoneId?: number; metric: string; granularity: 'HOUR' | 'DAY'; start: string; end: string }) =>
    get<FlowTrend>('/flows/trend', p),
  queues: (siteId: number) => get<QueueStatus[]>('/queues', { siteId }),
  dailyForecast: (siteId: number, days = 7) => get<DailyForecast>('/forecasts/daily', { siteId, days }),
  hourlyForecast: (siteId: number, date: string) => get<HourlyForecast>('/forecasts/hourly', { siteId, date }),
  calendar: (start: string, end: string) => get<CalendarDay[]>('/calendar', { start, end }),
  keyCameras: (siteId?: number) => get<Device[]>('/devices', { siteId, type: 'CAMERA', keyPoint: true }),
  playback: (deviceId: number) => post<PlaybackSession>(`/devices/${deviceId}/playback-session`),
  reviews: (siteId?: number, size = 10) => get<Page<ReviewItem>>('/reviews', { siteId, size, page: 1 }),
  reviewSummary: (siteId: number | undefined, start: string, end: string) =>
    get<ReviewSummary>('/reviews/summary', { siteId, start, end }),
  business: (siteId: number, start: string, end: string) => get<BusinessSummary>('/business/summary', { siteId, start, end }),
  groupStats: (siteId: number, start: string, end: string) => get<GroupStats>('/groups/stats', { siteId, start, end }),
}
