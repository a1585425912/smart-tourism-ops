import { describe, expect, it } from 'vitest'
import { fitWithin, groupTasks, missingRequired } from './mobileUtils'
import type { TaskView } from '@/api/mobile'

function task(id: number, status: TaskView['status'], extra: Partial<TaskView> = {}): TaskView {
  return {
    id, taskNo: `XJ-${id}`, siteId: 1, siteName: '景区', zoneId: null, zoneName: null, templateId: 1, templateName: '模板',
    title: `任务${id}`, description: null, priority: 'NORMAL', assigneeId: 1, assigneeName: null, reviewerId: null, reviewerName: null,
    dueAt: null, status, origin: 'MANUAL', overdue: false, issueCount: 0, openIssueCount: 0, version: 0, createdAt: '', updatedAt: '', ...extra,
  }
}

describe('fitWithin', () => {
  it('shrinks the longest edge to the limit, keeping aspect ratio', () => {
    expect(fitWithin(4000, 3000)).toEqual({ width: 1600, height: 1200 })
    expect(fitWithin(3000, 4000)).toEqual({ width: 1200, height: 1600 })
  })
  it('never upscales and handles degenerate sizes', () => {
    expect(fitWithin(800, 600)).toEqual({ width: 800, height: 600 })
    expect(fitWithin(0, 100)).toEqual({ width: 0, height: 0 })
    expect(fitWithin(10000, 1).height).toBe(1)
  })
})

describe('groupTasks', () => {
  it('groups by status, drops empty groups and hidden statuses', () => {
    const groups = groupTasks([task(1, 'ASSIGNED'), task(2, 'RECTIFYING'), task(3, 'DRAFT'), task(4, 'PENDING_REVIEW'), task(5, 'SUBMITTED')])
    expect(groups.map((g) => g.key)).toEqual(['todo', 'rectify', 'wait'])
    expect(groups[2].tasks.map((t) => t.id).sort()).toEqual([4, 5])
  })
  it('puts overdue first, then earliest due, undated last', () => {
    const groups = groupTasks([
      task(1, 'ASSIGNED', { dueAt: '2026-02-20T10:00:00+08:00' }),
      task(2, 'ASSIGNED'),
      task(3, 'ASSIGNED', { dueAt: '2026-02-19T10:00:00+08:00' }),
      task(4, 'ASSIGNED', { dueAt: '2026-02-25T10:00:00+08:00', overdue: true }),
    ])
    expect(groups[0].tasks.map((t) => t.id)).toEqual([4, 3, 1, 2])
  })
})

describe('missingRequired', () => {
  const items = [
    { code: 'a', name: '冰体裂缝', required: true, hint: null },
    { code: 'b', name: '护栏', required: true, hint: null },
    { code: 'c', name: '照明', required: false, hint: null },
  ]
  it('lists required items without a result', () => {
    expect(missingRequired(items, { a: { result: 'OK' }, b: { result: null } })).toEqual(['护栏'])
    expect(missingRequired(items, {})).toEqual(['冰体裂缝', '护栏'])
  })
  it('accepts NA and ABNORMAL as answered', () => {
    expect(missingRequired(items, { a: { result: 'NA' }, b: { result: 'ABNORMAL' } })).toEqual([])
  })
})
