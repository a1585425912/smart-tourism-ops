<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { inspectionApi, type FileRef, type TaskAction, type TransitionBody } from '@/api/inspection'
import { ApiError, errorText } from '@/api/http'
import StatePanel from '@/components/common/StatePanel.vue'
import { useAsync } from '@/components/admin/composables'
import { fmtDateTime, label } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

const task = useAsync(() => inspectionApi.task(id))
const t = computed(() => task.data.value)

// 状态机可视化: 主流程 + 存在异常时的整改分支
const FLOW = ['DRAFT', 'ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'RECTIFYING', 'PENDING_REVIEW', 'COMPLETED']
const steps = computed(() => {
  const s = t.value
  if (!s) return []
  const rectified = s.issueCount > 0 || ['RECTIFYING', 'PENDING_REVIEW'].includes(s.status)
  return FLOW.filter((x) => rectified || !['RECTIFYING', 'PENDING_REVIEW'].includes(x))
})
const activeStep = computed(() => {
  const s = t.value
  if (!s) return 0
  const i = steps.value.indexOf(s.status)
  return s.status === 'COMPLETED' ? steps.value.length : Math.max(i, 0)
})

const ACTIONS: Record<TaskAction, { n: string; type: string; needNote?: boolean; hint?: string }> = {
  ASSIGN: { n: '派发', type: 'primary', hint: '指定执行人后派发任务' },
  START: { n: '开始执行', type: 'primary' },
  SUBMIT: { n: '提交结果', type: 'primary', hint: '提交前需完成所有必填检查项（建议在移动巡检端执行）' },
  CONFIRM: { n: '确认完成', type: 'success' },
  REQUIRE_RECTIFY: { n: '要求整改', type: 'warning', needNote: true },
  RECTIFY_DONE: { n: '整改完成', type: 'primary', needNote: true, hint: '填写整改说明，可上传整改照片' },
  APPROVE: { n: '复核通过', type: 'success' },
  REJECT: { n: '复核退回', type: 'danger', needNote: true },
  CANCEL: { n: '取消任务', type: 'info' },
}
const RESULT: Record<string, { n: string; type: string }> = { OK: { n: '正常', type: 'success' }, ABNORMAL: { n: '异常', type: 'danger' }, NA: { n: '不适用', type: 'info' } }
const ISSUE_STATUS: Record<string, { n: string; type: string }> = { OPEN: { n: '待整改', type: 'danger' }, RECTIFIED: { n: '已整改待复核', type: 'warning' }, CLOSED: { n: '已关闭', type: 'success' } }
const LOG_ACTIONS: Record<string, string> = { CREATE: '创建', SAVE_RESULTS: '保存检查结果', ...Object.fromEntries(Object.entries(ACTIONS).map(([k, v]) => [k, v.n])) }

const dialog = ref(false)
const action = ref<TaskAction>('ASSIGN')
const form = reactive<{ note: string; assigneeId: number | null; reviewerId: number | null; files: FileRef[] }>({ note: '', assigneeId: null, reviewerId: null, files: [] })
const busy = ref(false)
const uploading = ref(false)
const actError = ref('')
const inspectors = ref<{ id: number; displayName: string }[]>([])
const reviewers = ref<{ id: number; displayName: string }[]>([])

async function open(a: TaskAction) {
  const s = t.value
  if (!s) return
  action.value = a
  Object.assign(form, { note: '', assigneeId: s.assigneeId, reviewerId: s.reviewerId, files: [] })
  actError.value = ''
  dialog.value = true
  if (a === 'ASSIGN') {
    try {
      ;[inspectors.value, reviewers.value] = await Promise.all([inspectionApi.candidates(s.siteId, 'INSPECTOR'), inspectionApi.candidates(s.siteId, 'REVIEWER')])
    } catch (e) {
      actError.value = errorText(e)
    }
  }
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const s = t.value
  if (!file || !s) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    actError.value = '仅支持 JPEG / PNG / WebP 图片'
    return
  }
  if (file.size > 10 * 1024 * 1024) {
    actError.value = '图片不能超过 10MB'
    return
  }
  uploading.value = true
  actError.value = ''
  try {
    form.files.push(await inspectionApi.uploadFile(file, s.siteId, 'INSPECTION_RECTIFY', s.id))
  } catch (err) {
    actError.value = '上传失败：' + errorText(err)
  } finally {
    uploading.value = false
  }
}

async function submit() {
  const s = t.value
  if (!s) return
  const meta = ACTIONS[action.value]
  if (action.value === 'ASSIGN' && !form.assigneeId) {
    actError.value = '请选择执行人'
    return
  }
  if (meta.needNote && !form.note.trim()) {
    actError.value = '请填写说明'
    return
  }
  const body: TransitionBody = { action: action.value, version: s.version }
  if (action.value === 'ASSIGN') {
    body.assigneeId = form.assigneeId ?? undefined
    body.reviewerId = form.reviewerId ?? undefined
  }
  if (action.value === 'RECTIFY_DONE') {
    body.rectifyNote = form.note.trim()
    body.rectifyFileIds = form.files.map((f) => f.id)
  }
  if (form.note.trim()) body.note = form.note.trim()
  busy.value = true
  actError.value = ''
  try {
    task.data.value = await inspectionApi.transition(s.id, body)
    dialog.value = false
    ElMessage.success(`已${meta.n}`)
  } catch (e) {
    if (e instanceof ApiError && e.code === 'CONFLICT') {
      actError.value = '任务已被其他人更新，已加载最新状态，请重新操作'
      await task.run()
    } else if (e instanceof ApiError && e.code === 'ILLEGAL_TRANSITION') {
      actError.value = e.message
      await task.run()
    } else {
      actError.value = errorText(e)
    }
  } finally {
    busy.value = false
  }
}

function resultOf(code: string) {
  return t.value?.results.find((r) => r.itemCode === code)
}
</script>

<template>
  <div class="sw-page">
    <div class="bar"><el-button link type="primary" @click="router.push('/admin/inspections')">← 返回列表</el-button></div>
    <StatePanel :loading="task.loading.value && !t" :error="task.error.value" :empty="!t" min-height="240px" @retry="task.run">
      <template v-if="t">
        <div class="sw-card">
          <div class="head">
            <div>
              <h2>{{ t.title }}
                <el-tag v-if="t.origin === 'AGENT_DRAFT'" size="small" type="warning" effect="plain">AI 草稿 · 需人工确认后派发</el-tag>
                <el-tag v-if="t.overdue" size="small" type="danger">已逾期</el-tag>
                <el-tag v-if="t.status === 'CANCELLED'" size="small" type="info">已取消</el-tag>
              </h2>
              <div class="sw-muted">{{ t.taskNo }} · {{ t.siteName }}{{ t.zoneName ? ' / ' + t.zoneName : '' }} · 模板 {{ t.templateName }}</div>
            </div>
            <div class="acts">
              <el-button v-for="a in t.allowedActions" :key="a" :type="ACTIONS[a]?.type ?? 'default'" size="small" :plain="a === 'CANCEL'" @click="open(a)">{{ ACTIONS[a]?.n ?? a }}</el-button>
              <span v-if="!t.allowedActions.length" class="sw-muted">当前账号在此状态下没有可执行的操作</span>
            </div>
          </div>
          <el-steps v-if="t.status !== 'CANCELLED'" :active="activeStep" finish-status="success" align-center class="steps">
            <el-step v-for="s in steps" :key="s" :title="label(s)" />
          </el-steps>
          <div class="info">
            <div><span>执行人</span>{{ t.assigneeName ?? '未指派' }}</div>
            <div><span>复核人</span>{{ t.reviewerName ?? '未指定' }}</div>
            <div><span>优先级</span>{{ label(t.priority) }}</div>
            <div><span>截止时间</span>{{ fmtDateTime(t.dueAt) }}</div>
            <div><span>创建时间</span>{{ fmtDateTime(t.createdAt) }}</div>
            <div><span>最近更新</span>{{ fmtDateTime(t.updatedAt) }}</div>
            <div v-if="t.description" class="wide"><span>说明</span>{{ t.description }}</div>
            <div v-if="t.basisRefs?.length" class="wide"><span>AI 依据</span><code v-for="b in t.basisRefs" :key="b" class="ref">{{ b }}</code></div>
          </div>
        </div>

        <div class="layout">
          <div class="col">
            <div class="sw-card">
              <div class="sw-card-title"><span>检查项与结果</span><span class="sw-muted">{{ t.results.length }} / {{ t.items.length }} 项已填写</span></div>
              <el-table :data="t.items" size="small">
                <el-table-column label="检查项" min-width="180">
                  <template #default="{ row }">{{ row.name }}<b v-if="row.required" class="req">*</b><div v-if="row.hint" class="sw-muted">{{ row.hint }}</div></template>
                </el-table-column>
                <el-table-column label="结果" width="90">
                  <template #default="{ row }">
                    <el-tag v-if="resultOf(row.code)" size="small" :type="RESULT[resultOf(row.code)!.result].type">{{ RESULT[resultOf(row.code)!.result].n }}</el-tag>
                    <span v-else class="sw-muted">未填写</span>
                  </template>
                </el-table-column>
                <el-table-column label="备注与照片" min-width="220">
                  <template #default="{ row }">
                    <div>{{ resultOf(row.code)?.remark ?? '' }}</div>
                    <div class="photos">
                      <el-image v-for="f in resultOf(row.code)?.files ?? []" :key="f.id" :src="f.url" :preview-src-list="(resultOf(row.code)?.files ?? []).map((x) => x.url)" fit="cover" preview-teleported />
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="sw-card">
              <div class="sw-card-title"><span>异常与整改</span><span class="sw-muted">未关闭 {{ t.openIssueCount }} / 共 {{ t.issueCount }}</span></div>
              <div v-if="!t.issues.length" class="sw-muted">未发现异常</div>
              <div v-for="i in t.issues" :key="i.id" class="issue">
                <div class="line">
                  <el-tag size="small" :type="ISSUE_STATUS[i.status].type">{{ ISSUE_STATUS[i.status].n }}</el-tag>
                  <el-tag size="small" effect="plain" :type="i.severity === 'HIGH' ? 'danger' : i.severity === 'MEDIUM' ? 'warning' : 'info'">严重程度 {{ label(i.severity) }}</el-tag>
                  <span class="sw-muted">{{ fmtDateTime(i.createdAt) }}</span>
                </div>
                <p>{{ i.description }}</p>
                <div class="photos"><el-image v-for="f in i.files" :key="f.id" :src="f.url" :preview-src-list="i.files.map((x) => x.url)" fit="cover" preview-teleported /></div>
                <div v-if="i.rectifyNote" class="rect">
                  <b>整改记录</b>（{{ i.rectifierName ?? '—' }}）：{{ i.rectifyNote }}
                  <div class="photos"><el-image v-for="f in i.rectifyFiles" :key="f.id" :src="f.url" :preview-src-list="i.rectifyFiles.map((x) => x.url)" fit="cover" preview-teleported /></div>
                </div>
                <div v-if="i.reviewNote" class="rev"><b>复核意见</b>：{{ i.reviewNote }}</div>
              </div>
            </div>
          </div>

          <div class="sw-card">
            <div class="sw-card-title"><span>操作记录</span></div>
            <ol class="logs">
              <li v-for="(l, idx) in t.logs" :key="idx">
                <div><b>{{ LOG_ACTIONS[l.action] ?? l.action }}</b> <span class="sw-muted">{{ l.actorName ?? '系统' }} · {{ fmtDateTime(l.createdAt) }}</span></div>
                <div v-if="l.fromStatus || l.toStatus" class="sw-muted">{{ label(l.fromStatus) }} → {{ label(l.toStatus) }}</div>
                <div v-if="l.note" class="note">{{ l.note }}</div>
              </li>
            </ol>
            <div v-if="!t.logs.length" class="sw-muted">暂无记录</div>
          </div>
        </div>
      </template>
    </StatePanel>

    <el-dialog v-model="dialog" :title="ACTIONS[action]?.n" width="min(480px, 94vw)">
      <p v-if="ACTIONS[action]?.hint" class="sw-muted">{{ ACTIONS[action].hint }}</p>
      <el-form label-position="top">
        <template v-if="action === 'ASSIGN'">
          <el-form-item label="执行人" required>
            <el-select v-model="form.assigneeId" placeholder="选择该景区巡检人员" style="width: 100%">
              <el-option v-for="u in inspectors" :key="u.id" :label="u.displayName" :value="u.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="复核人">
            <el-select v-model="form.reviewerId" placeholder="可选" clearable style="width: 100%">
              <el-option v-for="u in reviewers" :key="u.id" :label="u.displayName" :value="u.id" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item :label="action === 'RECTIFY_DONE' ? '整改说明' : '说明'" :required="ACTIONS[action]?.needNote">
          <el-input v-model="form.note" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item v-if="action === 'RECTIFY_DONE'" label="整改照片（可选）">
          <div class="photos">
            <el-image v-for="f in form.files" :key="f.id" :src="f.url" fit="cover" />
            <label class="add" :class="{ dis: uploading }">
              {{ uploading ? '上传中…' : '+ 添加' }}
              <input type="file" accept="image/jpeg,image/png,image/webp" hidden :disabled="uploading" @change="onFile" />
            </label>
          </div>
        </el-form-item>
      </el-form>
      <div v-if="actError" class="err">{{ actError }}</div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="busy" :disabled="uploading" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: flex-start; }
h2 { margin: 0 0 4px; font-size: 18px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.acts { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.steps { margin: 18px 0 8px; }
.info { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 8px 20px; font-size: 14px; margin-top: 12px; }
.info span { color: var(--sw-text-3); font-size: 13px; margin-right: 8px; }
.info .wide { grid-column: 1 / -1; }
.ref { font-size: 12px; background: var(--sw-bg); padding: 1px 6px; border-radius: 3px; margin-right: 6px; }
.layout { display: grid; grid-template-columns: minmax(0, 1.8fr) minmax(0, 1fr); gap: 16px; margin-top: 16px; align-items: start; }
.col { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.req { color: var(--sw-danger); margin-left: 2px; }
.photos { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 4px; }
.photos :deep(.el-image) { width: 56px; height: 56px; border-radius: 4px; border: 1px solid var(--sw-border); }
.issue { border: 1px solid var(--sw-border); border-radius: 6px; padding: 10px 12px; margin-bottom: 8px; }
.line { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.issue p { margin: 6px 0; }
.rect, .rev { font-size: 13px; background: var(--sw-bg); border-radius: 4px; padding: 6px 8px; margin-top: 6px; }
.logs { list-style: none; margin: 0; padding: 0 0 0 4px; }
.logs li { position: relative; padding: 0 0 14px 16px; border-left: 2px solid var(--sw-border); font-size: 13px; }
.logs li::before { content: ''; position: absolute; left: -5px; top: 6px; width: 8px; height: 8px; border-radius: 50%; background: var(--sw-primary); }
.note { color: var(--sw-text-2); margin-top: 2px; }
.add { width: 56px; height: 56px; border: 1px dashed var(--sw-border); border-radius: 4px; display: flex; align-items: center; justify-content: center; font-size: 12px; color: var(--sw-text-3); cursor: pointer; }
.add.dis { opacity: 0.6; cursor: default; }
.err { color: var(--sw-danger); font-size: 13px; }
@media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .steps { display: none; } }
</style>
