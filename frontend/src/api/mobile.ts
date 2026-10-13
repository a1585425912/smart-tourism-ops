// 移动巡检使用的接口与类型, 与 docs/api-contract.md §5 对应。
import { get, post, upload } from './http'
import type { Page } from './types'

export type TaskStatus =
  | 'DRAFT' | 'ASSIGNED' | 'IN_PROGRESS' | 'SUBMITTED' | 'RECTIFYING' | 'PENDING_REVIEW' | 'COMPLETED' | 'CANCELLED'
export type ItemResult = 'OK' | 'ABNORMAL' | 'NA'
export type Severity = 'LOW' | 'MEDIUM' | 'HIGH'
export type FileBizType = 'INSPECTION_RESULT' | 'INSPECTION_ISSUE' | 'INSPECTION_RECTIFY'

export interface FileRef {
  id: number
  name: string
  contentType: string
  size: number
  url: string
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
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  assigneeId: number | null
  assigneeName: string | null
  reviewerId: number | null
  reviewerName: string | null
  dueAt: string | null
  status: TaskStatus
  origin: string
  overdue: boolean
  issueCount: number
  openIssueCount: number
  version: number
  createdAt: string
  updatedAt: string
}

export interface TaskItem {
  code: string
  name: string
  required: boolean
  hint: string | null
}

export interface TaskIssue {
  id: number
  itemCode: string | null
  description: string
  severity: Severity
  status: 'OPEN' | 'RECTIFIED' | 'CLOSED'
  rectifierName: string | null
  rectifyNote: string | null
  rectifyFiles: FileRef[]
  reviewNote: string | null
  files: FileRef[]
  createdAt: string
  updatedAt: string
}

export interface TaskDetail extends TaskView {
  items: TaskItem[]
  results: { itemCode: string; itemName: string; result: ItemResult; remark: string | null; files: FileRef[] }[]
  issues: TaskIssue[]
  logs: { action: string; fromStatus: string | null; toStatus: string | null; actorName: string | null; note: string | null; createdAt: string }[]
  allowedActions: string[]
  basisRefs: string[] | null
}

export interface ResultsBody {
  version: number
  items: { itemCode: string; result: ItemResult; remark?: string; fileIds: number[] }[]
  issues: { itemCode?: string; description: string; severity: Severity; fileIds: number[] }[]
}

export interface TransitionBody {
  action: string
  version: number
  note?: string
  rectifyNote?: string
  rectifyFileIds?: number[]
}

export const mobileApi = {
  myTasks: () => get<Page<TaskView>>('/inspection-tasks', { mine: true, page: 1, size: 200 }),
  task: (id: number) => get<TaskDetail>(`/inspection-tasks/${id}`),
  saveResults: (id: number, body: ResultsBody) => post<TaskDetail>(`/inspection-tasks/${id}/results`, body),
  transition: (id: number, body: TransitionBody) => post<TaskDetail>(`/inspection-tasks/${id}/transitions`, body),
  uploadPhoto: (file: Blob, name: string, siteId: number, bizType: FileBizType, bizId: number | null, onProgress?: (p: number) => void) => {
    const form = new FormData()
    form.append('file', file, name)
    form.append('siteId', String(siteId))
    form.append('bizType', bizType)
    if (bizId !== null) form.append('bizId', String(bizId))
    return upload<FileRef>('/files', form, onProgress)
  },
}
