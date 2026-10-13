import { get, post, upload } from './http'
import type { DataMeta, ImportResult } from './types'

// 客流、排队、预测、日历 —— 见 docs/api-contract.md §3

export type FlowMetric = 'ENTRY' | 'EXIT' | 'IN_PARK' | 'ZONE_COUNT' | 'QUEUE_WAIT'
export type Granularity = 'HOUR' | 'DAY'
export type LoadLevel = 'LOW' | 'NORMAL' | 'WARN' | 'FULL' | 'UNKNOWN'

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

export interface TrendPoint {
  t: string
  value: number
}

export interface FlowTrend {
  siteId: number
  zoneId: number | null
  metric: FlowMetric
  granularity: Granularity
  aggregation: 'sum' | 'max' | 'last'
  points: TrendPoint[]
  gaps: { start: string; end: string }[]
  meta: DataMeta
}

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

export interface DailyForecastPoint {
  date: string
  predicted: number
  lower: number
  upper: number
  actual: number | null
  holiday: string | null
  isOffDay: boolean
  weather: Weather | null
  drivers: string[]
}

export interface DailyForecast {
  siteId: number
  siteName: string
  metric: 'ENTRY'
  model: { name: string; version: string; trainedAt: string | null; features: string[] }
  points: DailyForecastPoint[]
  backtest: Backtest | null
  meta: DataMeta
}

export interface ZonePeak {
  zoneId: number
  zoneName: string
  capacity: number | null
  peak: number
  peakAt: string | null
  ratio: number | null
  level: LoadLevel
  staffSuggested: number
}

export interface Recommendation {
  level: 'INFO' | 'WARN' | 'CRITICAL'
  title: string
  detail: string
  zoneId: number | null
}

export interface HourlyForecast {
  siteId: number
  date: string
  points: { t: string; predictedInPark: number; predictedEntries: number }[]
  peak: { t: string; value: number } | null
  zonePeaks: ZonePeak[]
  recommendations: Recommendation[]
  meta: DataMeta
}

export interface CalendarDay {
  date: string
  holiday: string | null
  isOffDay: boolean
  weather: Weather | null
}

export const flowApi = {
  summary: (siteId: number, start: string, end: string) => get<FlowSummary>('/flows/summary', { siteId, start, end }),
  trend: (p: { siteId: number; zoneId?: number | null; metric: FlowMetric; granularity: Granularity; start: string; end: string }) =>
    get<FlowTrend>('/flows/trend', { ...p, zoneId: p.zoneId ?? undefined }),
  zones: (siteId: number) => get<ZoneLoad[]>('/flows/zones', { siteId }),
  importCsv: (siteId: number, file: File, onProgress?: (p: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    form.append('siteId', String(siteId))
    return upload<ImportResult>('/imports/flows', form, onProgress)
  },
  queues: (siteId: number) => get<QueueStatus[]>('/queues', { siteId }),
  dailyForecast: (siteId: number, days = 7) => get<DailyForecast>('/forecasts/daily', { siteId, days }),
  hourlyForecast: (siteId: number, date: string) => get<HourlyForecast>('/forecasts/hourly', { siteId, date }),
  backtests: (siteId: number) => get<Backtest[]>('/forecasts/backtests', { siteId }),
  refreshForecast: (siteId: number) => post<{ jobId: number }>(`/forecasts/refresh?siteId=${siteId}`),
  calendar: (start: string, end: string) => get<CalendarDay[]>('/calendar', { start, end }),
}
