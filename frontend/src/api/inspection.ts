import { get, post, upload } from './http'
import type { Page } from './types'

// 巡检与文件 —— 见 docs/api-contract.md §5

export type TaskStatus =
  | 'DRAFT' | 'ASSIGNED' | 'IN_PROGRESS' | 'SUBMITTED' | 'RECTIFYING' | 'PENDING_REVIEW' | 'COMPLETED' | 'CANCELLED'
export type TaskAction =
  | 'ASSIGN' | 'START' | 'SUBMIT' | 'CONFIRM' | 'REQUIRE_RECTIFY' | 'RECTIFY_DONE' | 'APPROVE' | 'REJECT' | 'CANCEL'
export type Priority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH'

export interface FileRef {
  id: number
  name: string
  contentType: string
  size: number
  url: string
}

export interface TemplateItem {
  code: string
  name: string
  required: boolean
  hint: string | null
}

export interface InspectionTemplate {
  id: number
  name: string
  category: string
  items: TemplateItem[]
}

export interface TaskView {
  id: number
  taskNo: string
  siteId: number
  siteName: string
  zoneId: number | null
  zoneName: string | null
  templateId: number
  templateName: string
  title: string
  description: string | null
  priority: Priority
  assigneeId: number | null
  assigneeName: string | null
  reviewerId: number | null
  reviewerName: string | null
  dueAt: string | null
  status: TaskStatus
  origin: 'MANUAL' | 'AGENT_DRAFT' | 'SCHEDULE'
  overdue: boolean
  issueCount: number
  openIssueCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface TaskIssue {
  id: number
  itemCode: string | null
  description: string
  severity: Severity
  status: 'OPEN' | 'RECTIFIED' | 'CLOSED'
  rectifierId: number | null
  rectifierName: string | null
  rectifyNote: string | null
  rectifyFiles: FileRef[]
  reviewNote: string | null
  files: FileRef[]
  createdAt: string
  updatedAt: string
}

export interface TaskDetail extends TaskView {
  items: TemplateItem[]
  results: { itemCode: string; itemName: string; result: 'OK' | 'ABNORMAL' | 'NA'; remark: string | null; files: FileRef[] }[]
  issues: TaskIssue[]
  logs: { action: string; fromStatus: string | null; toStatus: string | null; actorName: string | null; note: string | null; createdAt: string }[]
  allowedActions: TaskAction[]
  basisRefs: string[] | null
}

export interface CreateTask {
  siteId: number
  zoneId?: number | null
  templateId: number
  title: string
  description?: string
  priority: Priority
  assigneeId?: number | null
  reviewerId?: number | null
  dueAt?: string | null
}

export interface TransitionBody {
  action: TaskAction
  version: number
  note?: string
  assigneeId?: number
  reviewerId?: number
  rectifyNote?: string
  rectifyFileIds?: number[]
}

export interface InspectionSummary {
  byStatus: Record<string, number>
  total: number
  completed: number
  overdue: number
  openIssues: number
  overdueTasks: { taskId: number; taskNo: string; siteId: number; title: string; status: string; dueAt: string; assigneeName: string | null }[]
  issues: { issueId: number; taskId: number; taskNo: string; siteId: number; description: string; severity: string; status: string; createdAt: string }[]
  dataAsOf: string | null
}

export const inspectionApi = {
  templates: () => get<InspectionTemplate[]>('/inspection-templates'),
  tasks: (p: { siteId?: number; status?: string; mine?: boolean; keyword?: string; page?: number; size?: number }) =>
    get<Page<TaskView>>('/inspection-tasks', p),
  task: (id: number) => get<TaskDetail>(`/inspection-tasks/${id}`),
  create: (body: CreateTask) => post<TaskDetail>('/inspection-tasks', body),
  transition: (id: number, body: TransitionBody) => post<TaskDetail>(`/inspection-tasks/${id}/transitions`, body),
  candidates: (siteId: number, role: 'INSPECTOR' | 'REVIEWER') =>
    get<{ id: number; displayName: string }[]>('/inspection/candidates', { siteId, role }),
  stats: (siteId: number | undefined, start: string, end: string) => get<InspectionSummary>('/inspection/stats', { siteId, start, end }),
  uploadFile: (file: File, siteId: number, bizType: string, bizId?: number, onProgress?: (p: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    form.append('siteId', String(siteId))
    form.append('bizType', bizType)
    if (bizId != null) form.append('bizId', String(bizId))
    return upload<FileRef>('/files', form, onProgress)
  },
}
