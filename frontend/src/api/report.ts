import { get, post } from './http'
import type { DataMode, Page } from './types'

// 运营日报 —— 见 docs/api-contract.md §7

export interface ReportListItem {
  id: number
  siteId: number
  siteName: string
  reportDate: string
  version: number
  title: string
  dataAsOf: string
  dataMode: DataMode
  provider: string | null
  model: string | null
  createdAt: string
  createdByName: string | null
}

export interface ReportFact {
  metric: string
  value: number | string | null
  unit: string | null
  sourceIds: string[]
}

export interface Report extends ReportListItem {
  promptVersion: string | null
  snapshot: Record<string, unknown>
  content: {
    summary: string
    sections: { title: string; kind: 'FACT' | 'ANALYSIS' | 'ADVICE'; paragraphs: string[]; facts: ReportFact[] }[]
    risks: string[]
    suggestions: { title: string; detail: string; basis: string[]; owner?: string; horizon?: string; priority?: string; kpi?: string }[]
    coverage: string
  }
}

export const reportApi = {
  list: (p: { siteId?: number; page?: number; size?: number; latestOnly?: boolean; reportDate?: string } = {}) => get<Page<ReportListItem>>('/reports', p),
  get: (id: number) => get<Report>(`/reports/${id}`),
  generate: (siteId: number, reportDate: string) => post<{ runId: number }>('/reports', { siteId, reportDate }),
}
