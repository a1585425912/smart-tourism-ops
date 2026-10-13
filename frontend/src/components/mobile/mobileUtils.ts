// 移动巡检的纯函数与图片压缩工具。
import type { ItemResult, TaskItem, TaskStatus, TaskView } from '@/api/mobile'

export const MAX_PHOTO_EDGE = 1600

/** 等比缩小到最长边不超过 maxEdge; 不放大。 */
export function fitWithin(width: number, height: number, maxEdge = MAX_PHOTO_EDGE): { width: number; height: number } {
  if (width <= 0 || height <= 0) return { width: 0, height: 0 }
  const k = Math.min(1, maxEdge / Math.max(width, height))
  return { width: Math.max(1, Math.round(width * k)), height: Math.max(1, Math.round(height * k)) }
}

/** 浏览器端把照片缩小并转成 JPEG, 减少弱网上传体积。解码失败时抛出错误。 */
export async function downscaleImage(file: File, maxEdge = MAX_PHOTO_EDGE, quality = 0.82): Promise<Blob> {
  const url = URL.createObjectURL(file)
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const el = new Image()
      el.onload = () => resolve(el)
      el.onerror = () => reject(new Error('无法读取该图片'))
      el.src = url
    })
    const size = fitWithin(img.naturalWidth, img.naturalHeight, maxEdge)
    const canvas = document.createElement('canvas')
    canvas.width = size.width
    canvas.height = size.height
    const g = canvas.getContext('2d')
    if (!g) throw new Error('当前浏览器不支持图片处理')
    g.drawImage(img, 0, 0, size.width, size.height)
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('图片压缩失败'))), 'image/jpeg', quality),
    )
  } finally {
    URL.revokeObjectURL(url)
  }
}

export interface TaskGroup {
  key: string
  title: string
  tasks: TaskView[]
}

const GROUPS: { key: string; title: string; statuses: TaskStatus[] }[] = [
  { key: 'todo', title: '待执行', statuses: ['ASSIGNED'] },
  { key: 'doing', title: '执行中', statuses: ['IN_PROGRESS'] },
  { key: 'rectify', title: '整改中', statuses: ['RECTIFYING'] },
  { key: 'wait', title: '已提交 / 待复核', statuses: ['SUBMITTED', 'PENDING_REVIEW'] },
  { key: 'done', title: '已完成', statuses: ['COMPLETED'] },
]

/** 按状态分组; 组内逾期优先, 其次按截止时间升序(无截止时间排最后)。 */
export function groupTasks(tasks: TaskView[]): TaskGroup[] {
  return GROUPS.map((g) => ({
    key: g.key,
    title: g.title,
    tasks: tasks
      .filter((t) => g.statuses.includes(t.status))
      .sort((a, b) => Number(b.overdue) - Number(a.overdue) || (a.dueAt ?? '9999').localeCompare(b.dueAt ?? '9999')),
  })).filter((g) => g.tasks.length > 0)
}

/** 提交前检查: 返回尚未填写结果的必填项名称。 */
export function missingRequired(items: TaskItem[], results: Record<string, { result: ItemResult | null }>): string[] {
  return items.filter((i) => i.required && !results[i.code]?.result).map((i) => i.name)
}

export const TASK_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', ASSIGNED: '待执行', IN_PROGRESS: '执行中', SUBMITTED: '已提交', RECTIFYING: '整改中',
  PENDING_REVIEW: '待复核', COMPLETED: '已完成', CANCELLED: '已取消',
}

export const ACTION_TEXT: Record<string, string> = {
  ASSIGN: '派发', START: '开始执行', SUBMIT: '提交', CONFIRM: '确认完成', REQUIRE_RECTIFY: '要求整改',
  RECTIFY_DONE: '整改完成', APPROVE: '复核通过', REJECT: '复核退回', CANCEL: '取消', CREATE: '创建', SAVE_RESULTS: '保存记录',
  AGENT_DRAFT: 'AI 草稿',
}

export const PRIORITY_TEXT: Record<string, string> = { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' }
export const SEVERITY_TEXT: Record<string, string> = { LOW: '轻微', MEDIUM: '一般', HIGH: '严重' }
