import { get, patch, post, upload } from './http'
import type { DataMeta, ImportResult, Page } from './types'

// 网评 —— 见 docs/api-contract.md §6

export type Sentiment = 'POSITIVE' | 'NEUTRAL' | 'NEGATIVE' | 'MIXED' | 'UNKNOWN'

export const SENTIMENT_LABELS: Record<Sentiment, string> = {
  POSITIVE: '正面',
  NEUTRAL: '中性',
  NEGATIVE: '负面',
  MIXED: '褒贬兼有',
  UNKNOWN: '无法判断',
}

export interface EvidenceSpan {
  start: number
  end: number
  text: string
  topic: string
}

export interface Analysis {
  version: number
  origin: 'MODEL' | 'HUMAN'
  sentiment: Sentiment
  topics: string[]
  summary: string | null
  needsHumanReview: boolean
  evidenceSpans: EvidenceSpan[]
  model: string | null
  promptVersion: string | null
  createdByName: string | null
  note: string | null
  createdAt: string
}

export interface ReviewView {
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
  analysisStatus: 'PENDING' | 'ANALYZED' | 'HUMAN' | 'FAILED'
  analysis: Analysis | null
}

export interface ReviewDetail extends ReviewView {
  history: Analysis[]
  sourceRecordId: string
  importedAt: string
  importBatch: string | null
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

export interface ReviewQuery {
  siteId?: number
  sentiment?: string
  topic?: string
  keyword?: string
  status?: string
  complaint?: boolean
  start?: string
  end?: string
  page?: number
  size?: number
}

export const reviewApi = {
  topics: () => get<{ code: string; name: string }[]>('/reviews/topics'),
  list: (q: ReviewQuery) => get<Page<ReviewView>>('/reviews', q as Record<string, unknown>),
  detail: (id: number) => get<ReviewDetail>(`/reviews/${id}`),
  classify: (id: number, body: { sentiment: Sentiment; topics: string[]; summary?: string; note?: string; expectedVersion: number }) =>
    patch<ReviewDetail>(`/reviews/${id}/classification`, body),
  summary: (siteId: number | undefined, start: string, end: string) => get<ReviewSummary>('/reviews/summary', { siteId, start, end }),
  importCsv: (file: File, onProgress?: (p: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    return upload<ImportResult>('/imports/reviews', form, onProgress)
  },
  analyze: (body: { siteId?: number; reviewIds?: number[]; limit?: number }) => post<{ runId: number }>('/reviews/analysis-jobs', body),
}
