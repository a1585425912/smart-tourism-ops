<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { mobileApi, type FileRef, type ItemResult, type ResultsBody, type Severity, type TaskDetail } from '@/api/mobile'
import { ApiError, errorText } from '@/api/http'
import StatePanel from '@/components/common/StatePanel.vue'
import PhotoUploader from '@/components/mobile/PhotoUploader.vue'
import { ACTION_TEXT, missingRequired, PRIORITY_TEXT, SEVERITY_TEXT, TASK_STATUS_TEXT } from '@/components/mobile/mobileUtils'
import { fmtDateTime, fmtTime } from '@/utils/format'

const route = useRoute()
const taskId = computed(() => Number(route.params.id))

const task = ref<TaskDetail | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)
const busy = ref(false)
const uploading = ref(0)
const notice = ref<{ kind: 'ok' | 'err' | 'warn'; text: string } | null>(null)

interface ItemForm { result: ItemResult | null; remark: string; files: FileRef[] }
interface IssueForm { key: number; itemCode: string; description: string; severity: Severity; files: FileRef[] }

const form = reactive<Record<string, ItemForm>>({})
const issues = ref<IssueForm[]>([])
const rectifyNote = ref('')
const rectifyFiles = ref<FileRef[]>([])
const dirty = ref(false)
let issueKey = 1

const RESULTS: { v: ItemResult; t: string }[] = [{ v: 'OK', t: '正常' }, { v: 'ABNORMAL', t: '异常' }, { v: 'NA', t: '不适用' }]

/** 用服务端返回的详情重建表单。 */
function apply(detail: TaskDetail) {
  task.value = detail
  Object.keys(form).forEach((k) => delete form[k])
  for (const item of detail.items) {
    const r = detail.results.find((x) => x.itemCode === item.code)
    form[item.code] = { result: r?.result ?? null, remark: r?.remark ?? '', files: [...(r?.files ?? [])] }
  }
  issues.value = detail.issues.filter((i) => i.status === 'OPEN').map((i) => ({
    key: issueKey++, itemCode: i.itemCode ?? '', description: i.description, severity: i.severity, files: [...i.files],
  }))
  dirty.value = false
}

async function load() {
  loading.value = true
  error.value = null
  try {
    apply(await mobileApi.task(taskId.value))
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
watch(taskId, load, { immediate: true })

const can = (action: string) => task.value?.allowedActions.includes(action) ?? false
const editable = computed(() => task.value?.status === 'IN_PROGRESS' && can('SUBMIT'))
const answered = computed(() => Object.values(form).filter((f) => f.result).length)

function setResult(code: string, v: ItemResult) {
  if (!editable.value) return
  form[code].result = v
  dirty.value = true
  // 标记异常时, 若该检查项还没有异常记录则自动添加一条待填写
  if (v === 'ABNORMAL' && !issues.value.some((i) => i.itemCode === code)) addIssue(code)
}

function addIssue(itemCode = '') {
  issues.value.push({ key: issueKey++, itemCode, description: '', severity: 'MEDIUM', files: [] })
  dirty.value = true
}

function removeIssue(key: number) {
  issues.value = issues.value.filter((i) => i.key !== key)
  dirty.value = true
}

function body(): ResultsBody {
  return {
    version: task.value!.version,
    items: Object.entries(form).filter(([, f]) => f.result).map(([itemCode, f]) => ({
      itemCode, result: f.result!, remark: f.remark.trim() || undefined, fileIds: f.files.map((x) => x.id),
    })),
    issues: issues.value.filter((i) => i.description.trim()).map((i) => ({
      itemCode: i.itemCode || undefined, description: i.description.trim(), severity: i.severity, fileIds: i.files.map((x) => x.id),
    })),
  }
}

/** 统一处理请求: 409 时重新加载并提示; 其它错误保留已填写内容以便重试。 */
async function run(fn: () => Promise<TaskDetail>, okText: string): Promise<boolean> {
  if (busy.value) return false
  busy.value = true
  notice.value = null
  try {
    apply(await fn())
    notice.value = { kind: 'ok', text: okText }
    return true
  } catch (e) {
    if (e instanceof ApiError && e.isConflict) {
      const illegal = e.code === 'ILLEGAL_TRANSITION'
      await load()
      notice.value = { kind: 'warn', text: illegal ? `${e.message}，已重新加载任务` : '任务已被其他人更新，已重新加载最新内容，请核对后重新操作' }
    } else {
      notice.value = { kind: 'err', text: `操作失败：${errorText(e)}。已填写的内容仍保留，可稍后重试` }
    }
    return false
  } finally {
    busy.value = false
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }
}

function validateIssues(): string | null {
  if (uploading.value > 0) return '照片仍在上传中，请稍候'
  if (issues.value.some((i) => !i.description.trim())) return '请填写每条异常的情况说明，或删除空白异常'
  const abnormalWithout = task.value!.items.filter((it) => form[it.code]?.result === 'ABNORMAL' && !issues.value.some((i) => i.itemCode === it.code))
  if (abnormalWithout.length) return `以下检查项标记为异常但未登记异常情况：${abnormalWithout.map((i) => i.name).join('、')}`
  return null
}

const start = () => run(() => mobileApi.transition(taskId.value, { action: 'START', version: task.value!.version }), '已开始执行')

async function save() {
  const problem = validateIssues()
  if (problem) return void (notice.value = { kind: 'err', text: problem })
  await run(() => mobileApi.saveResults(taskId.value, body()), '已保存，可继续填写')
}

async function submit() {
  const missing = missingRequired(task.value!.items, form)
  if (missing.length) return void (notice.value = { kind: 'err', text: `还有必填检查项未填写：${missing.join('、')}` })
  const problem = validateIssues()
  if (problem) return void (notice.value = { kind: 'err', text: problem })
  await run(async () => {
    const saved = await mobileApi.saveResults(taskId.value, body())
    task.value = saved
    return mobileApi.transition(taskId.value, { action: 'SUBMIT', version: saved.version })
  }, '已提交，等待复核')
}

async function rectifyDone() {
  if (uploading.value > 0) return void (notice.value = { kind: 'err', text: '照片仍在上传中，请稍候' })
  if (!rectifyNote.value.trim()) return void (notice.value = { kind: 'err', text: '请填写整改说明' })
  const ok = await run(() => mobileApi.transition(taskId.value, {
    action: 'RECTIFY_DONE', version: task.value!.version, rectifyNote: rectifyNote.value.trim(), rectifyFileIds: rectifyFiles.value.map((f) => f.id),
  }), '整改已提交，等待复核')
  if (ok) {
    rectifyNote.value = ''
    rectifyFiles.value = []
  }
}

const onBusy = (b: boolean) => (uploading.value = Math.max(0, uploading.value + (b ? 1 : -1)))
// 每个上传组件只在状态变化时上报, 用 Map 记录避免重复计数
const busyMap = new Map<string, boolean>()
function trackBusy(key: string, b: boolean) {
  if ((busyMap.get(key) ?? false) === b) return
  busyMap.set(key, b)
  onBusy(b)
}
</script>

<template>
  <StatePanel :loading="loading && !task" :error="task ? null : error" min-height="240px" @retry="load">
    <div v-if="task" class="task">
      <div v-if="notice" class="notice" :class="notice.kind" role="status">{{ notice.text }}</div>

      <section class="card head">
        <div class="t1"><b>{{ task.title }}</b><span class="st">{{ TASK_STATUS_TEXT[task.status] ?? task.status }}</span></div>
        <p v-if="task.description" class="desc">{{ task.description }}</p>
        <dl>
          <div><dt>编号</dt><dd>{{ task.taskNo }}</dd></div>
          <div><dt>位置</dt><dd>{{ task.siteName }}<template v-if="task.zoneName"> · {{ task.zoneName }}</template></dd></div>
          <div><dt>截止</dt><dd :class="{ red: task.overdue }">{{ task.dueAt ? fmtDateTime(task.dueAt) : '未设置' }}<template v-if="task.overdue">（已逾期）</template></dd></div>
          <div><dt>优先级</dt><dd>{{ PRIORITY_TEXT[task.priority] ?? task.priority }}</dd></div>
          <div><dt>复核人</dt><dd>{{ task.reviewerName ?? '—' }}</dd></div>
        </dl>
        <button v-if="can('START')" type="button" class="primary block" :disabled="busy" @click="start">开始执行</button>
        <p v-if="!task.allowedActions.length && task.status !== 'COMPLETED'" class="hint">当前状态下没有需要您处理的操作。</p>
      </section>

      <!-- 整改 -->
      <section v-if="can('RECTIFY_DONE')" class="card">
        <h3>整改处理</h3>
        <div v-for="i in task.issues.filter((x) => x.status === 'OPEN')" :key="i.id" class="issue-ro">
          <b>{{ SEVERITY_TEXT[i.severity] }}</b> {{ i.description }}
          <p v-if="i.reviewNote" class="hint">复核意见：{{ i.reviewNote }}</p>
        </div>
        <textarea v-model="rectifyNote" rows="3" maxlength="500" placeholder="整改说明（必填）：采取了哪些措施、当前状态" />
        <PhotoUploader v-model="rectifyFiles" :site-id="task.siteId" biz-type="INSPECTION_RECTIFY" :biz-id="task.id" @busy="(b: boolean) => trackBusy('rectify', b)" />
        <button type="button" class="primary block" :disabled="busy" @click="rectifyDone">提交整改结果</button>
      </section>

      <!-- 检查项 -->
      <section class="card">
        <h3>检查项 <small>{{ answered }} / {{ task.items.length }}</small></h3>
        <div v-for="item in task.items" :key="item.code" class="item">
          <div class="iname"><i v-if="item.required">*</i>{{ item.name }}</div>
          <p v-if="item.hint" class="hint">{{ item.hint }}</p>
          <div class="seg" role="radiogroup" :aria-label="item.name">
            <button v-for="r in RESULTS" :key="r.v" type="button" role="radio" :aria-checked="form[item.code]?.result === r.v"
              :class="[r.v, { on: form[item.code]?.result === r.v }]" :disabled="!editable" @click="setResult(item.code, r.v)">{{ r.t }}</button>
          </div>
          <template v-if="form[item.code] && (editable || form[item.code].remark || form[item.code].files.length)">
            <input v-model="form[item.code].remark" type="text" maxlength="500" placeholder="备注（可选）" :disabled="!editable" @input="dirty = true" />
            <PhotoUploader v-model="form[item.code].files" :site-id="task.siteId" biz-type="INSPECTION_RESULT" :biz-id="task.id" :disabled="!editable"
              @update:model-value="dirty = true" @busy="(b: boolean) => trackBusy('item-' + item.code, b)" />
          </template>
        </div>
        <p v-if="!task.items.length" class="hint">该任务模板没有检查项。</p>
      </section>

      <!-- 异常 -->
      <section v-if="editable" class="card">
        <h3>异常登记 <small>{{ issues.length }} 条</small></h3>
        <div v-for="i in issues" :key="i.key" class="issue">
          <select v-model="i.itemCode" @change="dirty = true">
            <option value="">不关联检查项</option>
            <option v-for="it in task.items" :key="it.code" :value="it.code">{{ it.name }}</option>
          </select>
          <textarea v-model="i.description" rows="2" maxlength="500" placeholder="异常情况说明（必填）" @input="dirty = true" />
          <div class="sev">
            严重程度
            <button v-for="s in (['LOW', 'MEDIUM', 'HIGH'] as Severity[])" :key="s" type="button" :class="{ on: i.severity === s, [s]: true }" @click="i.severity = s; dirty = true">{{ SEVERITY_TEXT[s] }}</button>
            <button type="button" class="del" @click="removeIssue(i.key)">删除</button>
          </div>
          <PhotoUploader v-model="i.files" :site-id="task.siteId" biz-type="INSPECTION_ISSUE" :biz-id="task.id" @update:model-value="dirty = true" @busy="(b: boolean) => trackBusy('issue-' + i.key, b)" />
        </div>
        <button type="button" class="ghost block" @click="addIssue()">＋ 添加异常</button>
      </section>

      <section v-else-if="task.issues.length" class="card">
        <h3>异常与整改 <small>{{ task.issues.length }} 条</small></h3>
        <div v-for="i in task.issues" :key="i.id" class="issue-ro">
          <div><b>{{ SEVERITY_TEXT[i.severity] }}</b> {{ i.description }}
            <span class="ist" :class="i.status">{{ i.status === 'OPEN' ? '待整改' : i.status === 'RECTIFIED' ? '已整改待复核' : '已关闭' }}</span></div>
          <div v-if="i.files.length" class="thumbs"><img v-for="f in i.files" :key="f.id" :src="f.url" :alt="f.name" loading="lazy" /></div>
          <p v-if="i.rectifyNote" class="hint">整改：{{ i.rectifyNote }}<template v-if="i.rectifierName">（{{ i.rectifierName }}）</template></p>
          <div v-if="i.rectifyFiles.length" class="thumbs"><img v-for="f in i.rectifyFiles" :key="f.id" :src="f.url" :alt="f.name" loading="lazy" /></div>
          <p v-if="i.reviewNote" class="hint">复核意见：{{ i.reviewNote }}</p>
        </div>
      </section>

      <section class="card">
        <h3>处理记录</h3>
        <ol class="logs">
          <li v-for="(l, idx) in task.logs" :key="idx">
            <b>{{ ACTION_TEXT[l.action] ?? l.action }}</b>
            <span>{{ l.actorName ?? '系统' }} · {{ fmtTime(l.createdAt) }}</span>
            <p v-if="l.note">{{ l.note }}</p>
          </li>
        </ol>
        <p v-if="!task.logs.length" class="hint">暂无记录</p>
      </section>

      <footer v-if="editable" class="actions">
        <span v-if="uploading > 0" class="upl">照片上传中…</span>
        <span v-else-if="dirty" class="upl">有未保存的修改</span>
        <button type="button" class="ghost" :disabled="busy" @click="save">保存</button>
        <button type="button" class="primary" :disabled="busy" @click="submit">{{ busy ? '处理中…' : '提交巡检' }}</button>
      </footer>
    </div>
  </StatePanel>
</template>

<style scoped>
.task { display: flex; flex-direction: column; gap: 10px; }
.card { background: #fff; border: 1px solid #e3e8ef; border-radius: 10px; padding: 12px; }
h3 { margin: 0 0 10px; font-size: 15px; color: #1c2533; }
h3 small { font-weight: 400; color: #8492a6; font-size: 12px; margin-left: 4px; }
.notice { padding: 8px 12px; border-radius: 8px; font-size: 13px; }
.notice.ok { background: #e6f6ee; color: #1f7a4d; }
.notice.err { background: #fdecea; color: #b4233a; }
.notice.warn { background: #fff4d6; color: #8a5a00; }
.t1 { display: flex; justify-content: space-between; gap: 8px; }
.t1 b { font-size: 16px; }
.st { flex: none; height: 22px; font-size: 12px; padding: 2px 10px; border-radius: 11px; background: #e8f0fe; color: #1f6feb; }
.desc { margin: 6px 0 0; font-size: 13px; color: #4a5568; }
dl { margin: 10px 0 0; display: grid; grid-template-columns: 1fr; gap: 4px; font-size: 13px; }
dl div { display: flex; gap: 8px; }
dt { width: 52px; color: #8492a6; flex: none; }
dd { margin: 0; color: #1c2533; }
dd.red { color: #d03050; }
.hint { margin: 4px 0; font-size: 12px; color: #8492a6; }
.item { padding: 10px 0; border-top: 1px solid #eef1f5; display: flex; flex-direction: column; gap: 6px; }
.item:first-of-type { border-top: none; padding-top: 0; }
.iname { font-size: 14px; color: #1c2533; }
.iname i { color: #e5484d; font-style: normal; margin-right: 2px; }
.seg { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.seg button { height: 40px; border-radius: 8px; border: 1px solid #d5dce6; background: #f7f9fc; color: #4a5568; font-size: 14px; }
.seg button.on.OK { background: #2fa86b; border-color: #2fa86b; color: #fff; }
.seg button.on.ABNORMAL { background: #e5484d; border-color: #e5484d; color: #fff; }
.seg button.on.NA { background: #6b7a90; border-color: #6b7a90; color: #fff; }
.seg button:disabled { opacity: 0.75; }
input[type='text'], textarea, select { width: 100%; border: 1px solid #d5dce6; border-radius: 8px; padding: 8px 10px; font-size: 14px; font-family: inherit; background: #fff; color: #1c2533; }
input:disabled { background: #f3f6fa; }
textarea { resize: vertical; margin-bottom: 8px; }
.issue { padding: 10px; margin-bottom: 10px; background: #fff7f7; border: 1px solid #f5c9cc; border-radius: 8px; display: flex; flex-direction: column; gap: 6px; }
.issue textarea { margin-bottom: 0; }
.sev { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #4a5568; }
.sev button { border: 1px solid #d5dce6; background: #fff; border-radius: 14px; padding: 3px 12px; font-size: 13px; color: #4a5568; }
.sev button.on.LOW { background: #5ec8f2; border-color: #5ec8f2; color: #fff; }
.sev button.on.MEDIUM { background: #e6a23c; border-color: #e6a23c; color: #fff; }
.sev button.on.HIGH { background: #e5484d; border-color: #e5484d; color: #fff; }
.sev .del { margin-left: auto; border: none; color: #d03050; background: transparent; }
.issue-ro { padding: 8px 0; border-top: 1px solid #eef1f5; font-size: 14px; }
.issue-ro:first-of-type { border-top: none; }
.issue-ro b { color: #d03050; margin-right: 4px; }
.ist { margin-left: 6px; font-size: 12px; padding: 0 6px; border-radius: 3px; background: #fdecea; color: #d03050; white-space: nowrap; }
.ist.RECTIFIED { background: #fff4d6; color: #a86a00; }
.ist.CLOSED { background: #e6f6ee; color: #2fa86b; }
.thumbs { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 6px; }
.thumbs img { width: 64px; height: 64px; object-fit: cover; border-radius: 6px; }
.logs { margin: 0; padding: 0 0 0 16px; font-size: 13px; color: #4a5568; }
.logs li { margin-bottom: 6px; }
.logs b { color: #1c2533; margin-right: 6px; }
.logs span { color: #8492a6; font-size: 12px; }
.logs p { margin: 2px 0 0; }
button.primary { background: #1f6feb; color: #fff; border: none; border-radius: 10px; height: 44px; font-size: 16px; padding: 0 20px; }
button.ghost { background: #fff; color: #1f6feb; border: 1px solid #1f6feb; border-radius: 10px; height: 44px; font-size: 15px; padding: 0 20px; }
button.block { width: 100%; margin-top: 10px; }
button:disabled { opacity: 0.6; }
.actions { position: fixed; left: 0; right: 0; bottom: 0; z-index: 9; max-width: 560px; margin: 0 auto; display: flex; align-items: center; gap: 8px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: #fff; border-top: 1px solid #e3e8ef; box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.06); }
.actions .upl { flex: 1; font-size: 12px; color: #a86a00; }
.actions .ghost { margin-left: auto; }
.actions .primary { flex: none; }
</style>
