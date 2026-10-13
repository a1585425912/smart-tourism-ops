<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { inspectionApi, type CreateTask, type Priority, type TaskView } from '@/api/inspection'
import { scenicApi } from '@/api/core'
import { errorText } from '@/api/http'
import type { Zone } from '@/api/types'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import { useAsync } from '@/components/admin/composables'
import { dayRange } from '@/components/admin/logic'
import { fmtDateTime, label } from '@/utils/format'

const app = useAppStore()
const router = useRouter()
const siteId = computed(() => app.currentSite?.id ?? null)
const canManage = computed(() => app.has('INSPECTION_MANAGE'))

const TABS = [
  { v: '', n: '全部' }, { v: 'DRAFT', n: '草稿' }, { v: 'ASSIGNED', n: '已派发' }, { v: 'IN_PROGRESS', n: '执行中' }, { v: 'SUBMITTED', n: '已提交' },
  { v: 'RECTIFYING', n: '整改中' }, { v: 'PENDING_REVIEW', n: '待复核' }, { v: 'COMPLETED', n: '已完成' }, { v: 'CANCELLED', n: '已取消' },
]
const STATUS_TYPE: Record<string, string> = {
  DRAFT: 'info', ASSIGNED: 'primary', IN_PROGRESS: 'primary', SUBMITTED: 'warning', RECTIFYING: 'danger', PENDING_REVIEW: 'warning', COMPLETED: 'success', CANCELLED: 'info',
}
const PRIORITY: Record<Priority, { n: string; type: string }> = {
  LOW: { n: '低', type: 'info' }, NORMAL: { n: '普通', type: 'primary' }, HIGH: { n: '高', type: 'warning' }, URGENT: { n: '紧急', type: 'danger' },
}

const q = reactive({ status: '', mine: false, keyword: '', page: 1, size: 20 })
const list = useAsync(() => inspectionApi.tasks({ siteId: siteId.value ?? undefined, status: q.status || undefined,
  mine: q.mine || undefined, keyword: q.keyword.trim() || undefined, page: q.page, size: q.size }), [siteId, () => q.page, () => q.size])
function search() {
  if (q.page !== 1) q.page = 1
  else list.run()
}
watch(() => [q.status, q.mine], search)
watch([siteId, () => q.size], search)

const stats = useAsync(async () => {
  const r = dayRange(app.today(), 30)
  return inspectionApi.stats(siteId.value ?? undefined, r.start, r.end)
}, [siteId])

// 新建任务
const dialog = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive<{ templateId: number | null; zoneId: number | null; title: string; description: string; priority: Priority; assigneeId: number | null; reviewerId: number | null; dueAt: string | null }>(
  { templateId: null, zoneId: null, title: '', description: '', priority: 'NORMAL', assigneeId: null, reviewerId: null, dueAt: null })
const templates = useAsync(() => inspectionApi.templates(), [], false)
const zoneList = ref<Zone[]>([])
const inspectors = ref<{ id: number; displayName: string }[]>([])
const reviewers = ref<{ id: number; displayName: string }[]>([])

async function openCreate() {
  if (!siteId.value) return
  Object.assign(form, { templateId: null, zoneId: null, title: '', description: '', priority: 'NORMAL', assigneeId: null, reviewerId: null, dueAt: null })
  createError.value = ''
  dialog.value = true
  try {
    const [, z, i, r] = await Promise.all([
      templates.run(), scenicApi.zones(siteId.value),
      inspectionApi.candidates(siteId.value, 'INSPECTOR'), inspectionApi.candidates(siteId.value, 'REVIEWER'),
    ])
    zoneList.value = z
    inspectors.value = i
    reviewers.value = r
  } catch (e) {
    createError.value = errorText(e)
  }
}

async function create() {
  if (!siteId.value) return
  if (!form.templateId || !form.title.trim()) {
    createError.value = '请选择检查模板并填写标题'
    return
  }
  creating.value = true
  createError.value = ''
  try {
    const body: CreateTask = {
      siteId: siteId.value, templateId: form.templateId, zoneId: form.zoneId, title: form.title.trim(), description: form.description || undefined,
      priority: form.priority, assigneeId: form.assigneeId, reviewerId: form.reviewerId, dueAt: form.dueAt,
    }
    const t = await inspectionApi.create(body)
    ElMessage.success('任务已创建（草稿），请在详情页派发')
    dialog.value = false
    router.push(`/admin/inspections/${t.id}`)
  } catch (e) {
    createError.value = errorText(e)
  } finally {
    creating.value = false
  }
}

function rowClass({ row }: { row: TaskView }) {
  return row.overdue ? 'overdue' : ''
}
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>日常巡检</h2>
      <template v-if="stats.data.value">
        <span class="chip">近 30 天任务 <b class="num">{{ stats.data.value.total }}</b></span>
        <span class="chip ok">已完成 <b class="num">{{ stats.data.value.completed }}</b></span>
        <span class="chip bad">逾期 <b class="num">{{ stats.data.value.overdue }}</b></span>
        <span class="chip warn">未闭环异常 <b class="num">{{ stats.data.value.openIssues }}</b></span>
      </template>
      <span class="grow" />
      <router-link class="link" to="/m">移动巡检端</router-link>
      <el-button v-if="canManage" type="primary" size="small" :disabled="!siteId" @click="openCreate">新建巡检任务</el-button>
    </div>

    <div class="sw-card">
      <el-tabs v-model="q.status" class="tabs">
        <el-tab-pane v-for="t in TABS" :key="t.v" :name="t.v">
          <template #label>{{ t.n }}<span v-if="t.v && stats.data.value?.byStatus[t.v]" class="cnt num">{{ stats.data.value.byStatus[t.v] }}</span></template>
        </el-tab-pane>
      </el-tabs>
      <div class="filters">
        <el-checkbox v-model="q.mine" size="small">仅本人执行</el-checkbox>
        <el-input v-model="q.keyword" placeholder="任务编号 / 标题" clearable size="small" style="width: 200px" @keyup.enter="search" @clear="search" />
        <el-button size="small" @click="search">查询</el-button>
      </div>
      <StatePanel :loading="list.loading.value && !list.data.value" :error="list.error.value" :empty="!list.data.value?.items.length" empty-text="没有符合条件的巡检任务" @retry="list.run">
        <el-table :data="list.data.value?.items ?? []" size="small" :row-class-name="rowClass" @row-click="(r: TaskView) => router.push(`/admin/inspections/${r.id}`)">
          <el-table-column prop="taskNo" label="编号" width="150" />
          <el-table-column v-if="app.isGlobal" prop="siteName" label="景区" min-width="140" />
          <el-table-column label="标题" min-width="200">
            <template #default="{ row }">
              {{ row.title }}
              <el-tag v-if="row.origin === 'AGENT_DRAFT'" size="small" type="warning" effect="plain" style="margin-left: 4px">AI 草稿</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="区域" min-width="100"><template #default="{ row }">{{ row.zoneName ?? '—' }}</template></el-table-column>
          <el-table-column label="优先级" width="76"><template #default="{ row }"><el-tag size="small" :type="PRIORITY[row.priority as Priority].type" effect="plain">{{ PRIORITY[row.priority as Priority].n }}</el-tag></template></el-table-column>
          <el-table-column label="执行人" width="96"><template #default="{ row }">{{ row.assigneeName ?? '未指派' }}</template></el-table-column>
          <el-table-column label="截止时间" width="150">
            <template #default="{ row }"><span :class="{ late: row.overdue }">{{ fmtDateTime(row.dueAt) }}<b v-if="row.overdue"> 逾期</b></span></template>
          </el-table-column>
          <el-table-column label="异常" width="80" align="right"><template #default="{ row }"><span class="num" :class="{ late: row.openIssueCount > 0 }">{{ row.openIssueCount }} / {{ row.issueCount }}</span></template></el-table-column>
          <el-table-column label="状态" width="92"><template #default="{ row }"><el-tag size="small" :type="STATUS_TYPE[row.status]">{{ label(row.status) }}</el-tag></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="q.page" v-model:page-size="q.size" class="pager" small background layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100]" :total="list.data.value?.total ?? 0" />
      </StatePanel>
    </div>

    <el-dialog v-model="dialog" title="新建巡检任务" width="min(560px, 94vw)">
      <el-form label-width="84px">
        <el-form-item label="检查模板" required>
          <el-select v-model="form.templateId" placeholder="选择模板" style="width: 100%" :loading="templates.loading.value">
            <el-option v-for="t in templates.data.value ?? []" :key="t.id" :label="`${t.name}（${t.category}，${t.items.length} 项）`" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required><el-input v-model="form.title" maxlength="100" /></el-form-item>
        <el-form-item label="区域">
          <el-select v-model="form.zoneId" placeholder="不限区域" clearable style="width: 100%">
            <el-option v-for="z in zoneList" :key="z.id" :label="z.name" :value="z.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-radio-group v-model="form.priority">
            <el-radio-button v-for="(p, k) in PRIORITY" :key="k" :value="k">{{ p.n }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="执行人">
          <el-select v-model="form.assigneeId" placeholder="稍后派发时指定" clearable style="width: 100%">
            <el-option v-for="u in inspectors" :key="u.id" :label="u.displayName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="复核人">
          <el-select v-model="form.reviewerId" placeholder="可选" clearable style="width: 100%">
            <el-option v-for="u in reviewers" :key="u.id" :label="u.displayName" :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="截止时间"><el-date-picker v-model="form.dueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" style="width: 100%" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" /></el-form-item>
      </el-form>
      <div v-if="createError" class="err">{{ createError }}</div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
h2 { margin: 0 6px 0 0; font-size: 20px; }
.grow { flex: 1; }
.chip { background: #fff; border: 1px solid var(--sw-border); border-radius: 16px; padding: 3px 12px; font-size: 13px; color: var(--sw-text-2); }
.chip b { margin-left: 4px; }
.chip.ok b { color: var(--sw-ok); } .chip.bad b { color: var(--sw-danger); } .chip.warn b { color: #b8740a; }
.link { font-size: 13px; color: var(--sw-primary); text-decoration: none; }
.cnt { margin-left: 4px; font-size: 12px; background: var(--sw-bg); border-radius: 8px; padding: 0 6px; }
.filters { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
.late { color: var(--sw-danger); }
.pager { margin-top: 12px; justify-content: flex-end; }
.err { color: var(--sw-danger); font-size: 13px; }
:deep(.el-table__row) { cursor: pointer; }
:deep(.el-table__row.overdue) { background: rgba(229, 72, 77, 0.05); }
</style>
