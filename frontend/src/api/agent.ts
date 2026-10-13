import { get, patch, post } from './http'
import type { Page } from './types'

// AI 运营助手(Agent)、营销方案 —— 见 docs/api-contract.md §7

export type Scenario = 'QA' | 'DAILY_REPORT' | 'MARKETING' | 'REVIEW_ANALYSIS'
export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface RunStep {
  seq: number
  toolName: string
  status: 'OK' | 'ERROR' | 'DENIED' | 'INVALID'
  arguments: unknown
  resultSummary: string | null
  sourceIds: string[]
  durationMs: number | null
  createdAt: string
}

export interface QaResult {
  answer: string
  facts: { metric: string; value: number | string | null; unit: string | null; period: string | null; sourceIds: string[] }[]
  evidence: { sourceId: string; label: string; excerpt: string }[]
  limitations: string[]
  dataAsOf: string | null
  dataMode: string
}

export interface ReportRef {
  reportId: number
  version: number
}

export interface MarketingRef {
  planId: number
}

export interface ReviewBatchResult {
  analyzed: number
  skippedHuman: number
  failed: number
  needsHumanReview: number
}

export interface AgentRun {
  id: number
  scenario: Scenario
  status: RunStatus
  progress: string | null
  input: Record<string, unknown>
  result: QaResult | ReportRef | MarketingRef | ReviewBatchResult | null
  errorCode: string | null
  errorMessage: string | null
  provider: 'fake' | 'deepseek' | null
  model: string | null
  promptVersion: string | null
  modelCalls: number
  toolCalls: number
  tokensIn: number
  tokensOut: number
  createdAt: string
  startedAt: string | null
  finishedAt: string | null
  steps: RunStep[]
}

export type CreateRun =
  | { scenario: 'QA'; question: string; siteIds?: number[]; idempotencyKey?: string }
  | { scenario: 'DAILY_REPORT'; siteId: number; reportDate: string }
  | { scenario: 'MARKETING'; siteIds?: number[]; goal: string; segment?: string }
  | { scenario: 'REVIEW_ANALYSIS'; siteId?: number; reviewIds?: number[]; limit?: number }

export interface Usage {
  date: string
  runs: number
  tokens: number
  limits: { runs: number; tokens: number }
}

export interface MarketingPlan {
  id: number
  title: string
  siteIds: number[]
  targetSegment: string | null
  status: 'DRAFT' | 'APPROVED' | 'ARCHIVED'
  createdAt: string
  createdByName: string | null
  agentRunId: number | null
  content: {
    summary: string
    segments: { name: string; evidence: string; share: number | null }[]
    packages: { name: string; items: string[]; priceHint: string; targetDays: string; rationale: string; basis: string[] }[]
    channels: string[]
    kpis: string[]
    risks: string[]
    disclaimer: string
  }
}

export const TERMINAL: RunStatus[] = ['SUCCEEDED', 'FAILED', 'CANCELLED']

export const agentApi = {
  create: (body: CreateRun) => post<{ runId: number; status: RunStatus }>('/agent/runs', body),
  list: (p: { scenario?: Scenario; page?: number; size?: number } = {}) => get<Page<AgentRun>>('/agent/runs', p),
  get: (id: number) => get<AgentRun>(`/agent/runs/${id}`),
  cancel: (id: number) => post<AgentRun>(`/agent/runs/${id}/cancel`),
  usage: () => get<Usage>('/agent/usage'),
  plans: () => get<MarketingPlan[] | Page<MarketingPlan>>('/marketing-plans'),
  plan: (id: number) => get<MarketingPlan>(`/marketing-plans/${id}`),
  setPlanStatus: (id: number, status: 'APPROVED' | 'ARCHIVED') => patch<MarketingPlan>(`/marketing-plans/${id}/status`, { status }),
}

/** 兼容列表接口返回数组或分页对象。 */
export function asList<T>(v: T[] | Page<T>): T[] {
  return Array.isArray(v) ? v : v.items
}

/** Agent 工具的中文名称。 */
export const TOOL_LABELS: Record<string, string> = {
  get_flow_summary: '查询客流汇总',
  get_flow_trend: '查询客流趋势',
  get_zone_loads: '查询区域热度',
  get_queue_status: '查询排队状态',
  get_forecast: '查询客流预测',
  get_review_summary: '汇总网评舆情',
  get_review_evidence: '调取评价原文',
  get_inspection_summary: '汇总巡检情况',
  get_device_status: '查询设备状态',
  get_business_summary: '查询业态经营',
  get_group_stats: '查询团队数据',
  get_visitor_profile: '查询游客画像',
  create_inspection_draft: '创建巡检草稿',
}

export const SCENARIO_LABELS: Record<Scenario, string> = {
  QA: '运营问答',
  DAILY_REPORT: '运营日报',
  MARKETING: '营销方案',
  REVIEW_ANALYSIS: '网评分析',
}
