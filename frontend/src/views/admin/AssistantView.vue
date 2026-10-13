<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  agentApi, SCENARIO_LABELS, TERMINAL,
  type AgentRun, type CreateRun, type MarketingRef, type QaResult, type ReportRef, type ReviewBatchResult, type RunStep, type Scenario,
} from '@/api/agent'
import { ApiError, errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import RunProgress from '@/components/admin/RunProgress.vue'
import { useAgentRun, useAsync } from '@/components/admin/composables'
import { safeMarkdown } from '@/components/admin/logic'
import { fmtDateTime, fmtNum, fmtTime } from '@/utils/format'
import type { DataMeta } from '@/api/types'

const app = useAppStore()
const router = useRouter()

interface Turn {
  key: string
  scenario: Scenario
  prompt: string
  runId: number | null
  run: AgentRun | null
  /** 创建任务阶段的错误(额度、服务不可用等) */
  createError: { code: string; message: string } | null
  request: CreateRun
}

const turns = ref<Turn[]>([])
const scenario = ref<Scenario>('QA')
const question = ref('')
const form = reactive<{ siteId: number | null; reportDate: string; goal: string; segment: string; limit: number }>({
  siteId: app.currentSite?.id ?? null, reportDate: app.now().subtract(1, 'day').format('YYYY-MM-DD'), goal: '', segment: '', limit: 50,
})
const sending = ref(false)
const transcript = ref<HTMLDivElement>()

const agent = useAgentRun()
const usage = useAsync(() => agentApi.usage())
const busy = computed(() => sending.value || agent.watching.value)
const activeTurn = computed(() => turns.value.find((t) => t.runId != null && t.run == null) ?? null)

agent.onDone((r) => {
  const t = turns.value.find((x) => x.runId === r.id)
  if (t) t.run = r
  usage.run()
  scrollDown()
})

const SCENARIOS: { v: Scenario; title: string; desc: string; icon: string; perm?: string }[] = [
  { v: 'QA', title: '运营问答', desc: '用自然语言查询客流、排队、网评、巡检、业态等授权数据，答案附数据依据', icon: 'ChatLineRound' },
  { v: 'DAILY_REPORT', title: '生成日报', desc: '基于固定统计快照生成结构化运营日报并保存版本', icon: 'Document', perm: 'REPORT_WRITE' },
  { v: 'REVIEW_ANALYSIS', title: '网评分析', desc: '批量分析待处理评价的情绪与主题，人工修正结果不会被覆盖', icon: 'ChatDotRound', perm: 'REVIEW_WRITE' },
  { v: 'MARKETING', title: '营销方案', desc: '结合客流趋势、游客画像和业态数据生成套票与营销方案草稿', icon: 'Present', perm: 'MARKETING_WRITE' },
]
const scenarios = computed(() => SCENARIOS.filter((s) => !s.perm || app.has(s.perm)))

const SUGGESTIONS = [
  '今天冰雪大世界入园人数和在园峰值是多少？',
  '太阳岛最近一周负面评价主要集中在哪些问题？',
  '预测未来三天冰雪大世界客流，哪些区域需要增派人手？',
  '有哪些巡检任务逾期？',
  '当前哪些项目排队时间最长？',
  '最近 30 天酒店入住率和客流的联动情况如何？',
]

function describe(req: CreateRun): string {
  switch (req.scenario) {
    case 'QA': return req.question
    case 'DAILY_REPORT': return `生成 ${siteName(req.siteId)} ${req.reportDate} 的运营日报`
    case 'REVIEW_ANALYSIS': return `分析${req.siteId ? ' ' + siteName(req.siteId) + ' ' : '授权景区'}待处理网评（最多 ${req.limit ?? '默认'} 条）`
    case 'MARKETING': return `生成营销方案：${req.goal}${req.segment ? '（客群：' + req.segment + '）' : ''}`
  }
}
const siteName = (id: number) => app.sites.find((s) => s.id === id)?.name ?? `景区#${id}`

function buildRequest(): CreateRun | string {
  switch (scenario.value) {
    case 'QA':
      if (!question.value.trim()) return '请输入问题'
      return { scenario: 'QA', question: question.value.trim(), idempotencyKey: `qa-${Date.now()}-${Math.random().toString(36).slice(2, 8)}` }
    case 'DAILY_REPORT':
      if (!form.siteId || !form.reportDate) return '请选择景区和日期'
      return { scenario: 'DAILY_REPORT', siteId: form.siteId, reportDate: form.reportDate }
    case 'REVIEW_ANALYSIS':
      return { scenario: 'REVIEW_ANALYSIS', siteId: form.siteId ?? undefined, limit: form.limit }
    case 'MARKETING':
      if (!form.goal.trim()) return '请填写营销目标'
      return { scenario: 'MARKETING', goal: form.goal.trim(), segment: form.segment.trim() || undefined, siteIds: form.siteId ? [form.siteId] : undefined }
  }
}

const inputError = ref('')

async function send(req?: CreateRun) {
  if (busy.value) return
  const built = req ?? buildRequest()
  if (typeof built === 'string') {
    inputError.value = built
    return
  }
  inputError.value = ''
  const turn: Turn = { key: `t${Date.now()}`, scenario: built.scenario, prompt: describe(built), runId: null, run: null, createError: null, request: built }
  turns.value.push(turn)
  const t = turns.value[turns.value.length - 1]
  if (!req && built.scenario === 'QA') question.value = ''
  scrollDown()
  sending.value = true
  try {
    const r = await agentApi.create(built)
    t.runId = r.runId
    agent.track(r.runId)
  } catch (e) {
    t.createError = e instanceof ApiError ? { code: e.code, message: errorText(e) } : { code: 'UNKNOWN', message: errorText(e) }
  } finally {
    sending.value = false
    scrollDown()
  }
}

function retry(t: Turn) {
  const req = t.request.scenario === 'QA' ? { ...t.request, idempotencyKey: `qa-${Date.now()}-retry` } : t.request
  send(req)
}

function ask(q: string) {
  scenario.value = 'QA'
  question.value = q
  send()
}

function scrollDown() {
  nextTick(() => transcript.value?.scrollTo({ top: transcript.value.scrollHeight, behavior: 'smooth' }))
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

// 历史: 载入最近的任务
onMounted(async () => {
  try {
    const page = await agentApi.list({ page: 1, size: 8 })
    const past = [...page.items].reverse().map<Turn>((r) => ({
      key: `r${r.id}`, scenario: r.scenario, prompt: promptOf(r), runId: r.id, run: TERMINAL.includes(r.status) ? r : null, createError: null,
      request: { scenario: r.scenario, ...(r.input as object) } as CreateRun,
    }))
    turns.value = [...past, ...turns.value]
    const running = past.find((t) => t.run == null)
    if (running?.runId && !agent.watching.value) agent.track(running.runId)
    scrollDown()
  } catch {
    /* 历史加载失败不影响提问 */
  }
})

function promptOf(r: AgentRun): string {
  const i = r.input as Record<string, any>
  try {
    return describe({ scenario: r.scenario, ...i } as CreateRun)
  } catch {
    return SCENARIO_LABELS[r.scenario]
  }
}

function stepsOf(t: Turn): RunStep[] {
  return t.run?.steps ?? (activeTurn.value === t ? agent.steps.value : [])
}
function statusOf(t: Turn): string | null {
  return t.run?.status ?? (activeTurn.value === t ? agent.status.value : 'QUEUED')
}
const qa = (t: Turn) => (t.run?.status === 'SUCCEEDED' && t.scenario === 'QA' ? (t.run.result as QaResult | null) : null)
const reportRef = (t: Turn) => (t.run?.status === 'SUCCEEDED' && t.scenario === 'DAILY_REPORT' ? (t.run.result as ReportRef | null) : null)
const planRef = (t: Turn) => (t.run?.status === 'SUCCEEDED' && t.scenario === 'MARKETING' ? (t.run.result as MarketingRef | null) : null)
const batch = (t: Turn) => (t.run?.status === 'SUCCEEDED' && t.scenario === 'REVIEW_ANALYSIS' ? (t.run.result as ReviewBatchResult | null) : null)

function qaMeta(r: QaResult): DataMeta {
  const ids = [...new Set(r.facts.flatMap((f) => f.sourceIds).concat(r.evidence.map((e) => e.sourceId)))]
  return { dataAsOf: r.dataAsOf, sourceIds: ids, dataMode: (r.dataMode as DataMeta['dataMode']) ?? 'DEMO', quality: r.dataAsOf ? 'GOOD' : 'MISSING' }
}

const ERR_TITLES: Record<string, string> = {
  QUOTA_EXCEEDED: '今日 AI 调用额度已用完', AI_UNAVAILABLE: 'AI 服务暂不可用', AI_TIMEOUT: 'AI 响应超时', AI_RATE_LIMITED: 'AI 服务限流',
  AI_NO_KEY: '未配置模型密钥', AI_BAD_OUTPUT: '模型输出不符合要求', SITE_FORBIDDEN: '无权访问该景区数据', FORBIDDEN: '无权执行该操作',
}
const providerText = computed(() => {
  const s = app.system
  if (!s) return ''
  return s.aiProvider === 'deepseek' ? `DeepSeek · ${s.aiModel}` : '本地规则模拟器（未调用云端模型）'
})
</script>

<template>
  <div class="assistant">
    <aside class="left">
      <h3>场景</h3>
      <button v-for="s in scenarios" :key="s.v" type="button" class="sc" :class="{ on: scenario === s.v }" @click="scenario = s.v">
        <el-icon :size="20"><component :is="s.icon" /></el-icon>
        <span><b>{{ s.title }}</b><i>{{ s.desc }}</i></span>
      </button>
      <h3>试试这样问</h3>
      <button v-for="q in SUGGESTIONS" :key="q" type="button" class="sug" :disabled="busy" @click="ask(q)">{{ q }}</button>
      <div class="rules">
        <b>助手的边界</b>
        <ul>
          <li>只能通过受控工具读取你已获授权景区的数据</li>
          <li>数字来自业务统计，助手不自行编造</li>
          <li>找不到依据时会明确说明</li>
          <li>只能创建巡检草稿，不能直接派单或对外发布</li>
        </ul>
      </div>
    </aside>

    <section class="chat">
      <header class="chat-head">
        <div>
          <b>AI 运营助手</b>
          <span class="prov" :class="app.system?.aiProvider">{{ providerText }}</span>
        </div>
        <div v-if="usage.data.value" class="usage num" title="今日用量 / 每日额度">
          今日 {{ usage.data.value.runs }} / {{ usage.data.value.limits.runs }} 次 · {{ fmtNum(usage.data.value.tokens) }} / {{ fmtNum(usage.data.value.limits.tokens) }} tokens
        </div>
      </header>

      <div ref="transcript" class="transcript">
        <div v-if="!turns.length" class="welcome">
          <el-icon :size="40"><MagicStick /></el-icon>
          <p>选择左侧场景或直接提问。助手会调用受控工具查询数据，并在回答中列出事实、依据与局限。</p>
        </div>

        <div v-for="t in turns" :key="t.key" class="turn">
          <div class="me"><span class="tagsc">{{ SCENARIO_LABELS[t.scenario] }}</span>{{ t.prompt }}</div>

          <div class="bot">
            <template v-if="t.createError">
              <div class="fail">
                <b>{{ ERR_TITLES[t.createError.code] ?? '任务创建失败' }}</b>
                <p>{{ t.createError.message }}</p>
                <p class="sw-muted">普通业务功能不受影响。</p>
                <el-button v-if="t.createError.code !== 'QUOTA_EXCEEDED'" size="small" :disabled="busy" @click="retry(t)">重试</el-button>
              </div>
            </template>
            <template v-else>
              <RunProgress :status="statusOf(t)" :progress="activeTurn === t ? agent.progress.value : null" :steps="stepsOf(t)" />
              <el-button v-if="activeTurn === t && agent.watching.value" class="cancel" size="small" @click="agent.cancel">取消任务</el-button>

              <!-- 运营问答 -->
              <div v-if="qa(t)" class="answer">
                <!-- 模型文本经 marked + DOMPurify 白名单净化后渲染 -->
                <div class="md" v-html="safeMarkdown(qa(t)!.answer)" />
                <div v-if="qa(t)!.facts.length" class="block">
                  <h5>事实数据</h5>
                  <table class="facts">
                    <thead><tr><th>指标</th><th>数值</th><th>时间范围</th><th>来源</th></tr></thead>
                    <tbody>
                      <tr v-for="(f, i) in qa(t)!.facts" :key="i">
                        <td>{{ f.metric }}</td>
                        <td class="num"><b>{{ f.value == null ? '暂无数据' : typeof f.value === 'number' ? fmtNum(f.value, Number.isInteger(f.value) ? 0 : 2) : f.value }}</b> {{ f.value == null ? '' : f.unit }}</td>
                        <td>{{ f.period ?? '—' }}</td>
                        <td><code v-for="s in f.sourceIds" :key="s" class="src">{{ s }}</code></td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <div v-if="qa(t)!.evidence.length" class="block">
                  <h5>依据</h5>
                  <div v-for="(e, i) in qa(t)!.evidence" :key="i" class="evi"><code class="src">{{ e.sourceId }}</code><b>{{ e.label }}</b><span>{{ e.excerpt }}</span></div>
                </div>
                <div v-if="qa(t)!.limitations.length" class="block lim">
                  <h5>局限与说明</h5>
                  <ul><li v-for="l in qa(t)!.limitations" :key="l">{{ l }}</li></ul>
                </div>
                <DataMetaBar :meta="qaMeta(qa(t)!)" />
              </div>

              <div v-else-if="reportRef(t)" class="answer link">
                日报已生成（第 {{ reportRef(t)!.version }} 版）。
                <el-button type="primary" size="small" @click="router.push(`/admin/reports/${reportRef(t)!.reportId}`)">查看日报</el-button>
              </div>
              <div v-else-if="planRef(t)" class="answer link">
                营销方案草稿已生成，需人工审核后使用。
                <el-button type="primary" size="small" @click="router.push('/admin/marketing')">查看方案</el-button>
              </div>
              <div v-else-if="batch(t)" class="answer link num">
                已分析 <b>{{ batch(t)!.analyzed }}</b> 条，跳过人工修正 <b>{{ batch(t)!.skippedHuman }}</b> 条，失败 <b>{{ batch(t)!.failed }}</b> 条，建议人工复核 <b>{{ batch(t)!.needsHumanReview }}</b> 条。
                <el-button type="primary" size="small" @click="router.push('/admin/reviews')">查看网评</el-button>
              </div>

              <div v-if="t.run?.status === 'FAILED'" class="fail">
                <b>{{ ERR_TITLES[t.run.errorCode ?? ''] ?? '任务执行失败' }}</b>
                <p>{{ t.run.errorMessage ?? '未返回错误详情' }}</p>
                <el-button size="small" :disabled="busy" @click="retry(t)">重试</el-button>
              </div>
              <div v-else-if="t.run?.status === 'CANCELLED'" class="cancelled">
                任务已取消。<el-button link type="primary" size="small" :disabled="busy" @click="retry(t)">重新执行</el-button>
              </div>
              <div v-if="t.run" class="foot sw-muted num">
                {{ t.run.provider === 'deepseek' ? 'DeepSeek' : '本地规则模拟' }} {{ t.run.model ?? '' }} · 模型调用 {{ t.run.modelCalls }} 次 · 工具调用 {{ t.run.toolCalls }} 次 · tokens {{ fmtNum(t.run.tokensIn + t.run.tokensOut) }} · {{ fmtTime(t.run.finishedAt ?? t.run.createdAt) }}
              </div>
            </template>
          </div>
        </div>
      </div>

      <footer class="composer">
        <div class="mode">
          <span class="tagsc">{{ SCENARIO_LABELS[scenario] }}</span>
          <template v-if="scenario !== 'QA'">
            <el-select v-model="form.siteId" :placeholder="scenario === 'DAILY_REPORT' ? '选择景区' : '全部授权景区'" :clearable="scenario !== 'DAILY_REPORT'" size="small" style="width: 170px">
              <el-option v-for="s in app.sites" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-date-picker v-if="scenario === 'DAILY_REPORT'" v-model="form.reportDate" type="date" value-format="YYYY-MM-DD" size="small" :clearable="false" style="width: 140px" />
            <el-input-number v-if="scenario === 'REVIEW_ANALYSIS'" v-model="form.limit" :min="1" :max="200" size="small" controls-position="right" style="width: 110px" />
            <span v-if="scenario === 'REVIEW_ANALYSIS'" class="sw-muted">条上限</span>
          </template>
        </div>
        <div class="row">
          <textarea v-if="scenario === 'QA'" v-model="question" rows="2" maxlength="500" placeholder="输入问题，Enter 发送，Shift+Enter 换行" :disabled="busy" @keydown="onKey" />
          <div v-else-if="scenario === 'MARKETING'" class="mk">
            <input v-model="form.goal" maxlength="300" placeholder="营销目标，如：提升工作日客流，联动酒店餐饮推出两日游套票" :disabled="busy" />
            <input v-model="form.segment" maxlength="100" placeholder="目标客群（可选）" :disabled="busy" />
          </div>
          <div v-else class="hint sw-muted">{{ scenario === 'DAILY_REPORT' ? '将生成所选景区、所选日期的运营日报（新版本，不覆盖历史版本）。' : '将批量分析待处理评价；已人工修正的评价会被跳过。' }}</div>
          <el-button type="primary" :loading="busy" @click="send()">{{ scenario === 'QA' ? '发送' : '开始' }}</el-button>
        </div>
        <div v-if="inputError" class="err">{{ inputError }}</div>
        <div class="sw-muted tip">AI 生成内容仅供参考，请以“事实数据”与来源为准。{{ fmtDateTime(app.now().format()) }}（业务时钟）</div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.assistant { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 16px; padding: 16px; height: 100%; }
.left { overflow: auto; display: flex; flex-direction: column; gap: 8px; }
h3 { margin: 8px 0 2px; font-size: 13px; color: var(--sw-text-3); font-weight: 500; }
.sc { display: flex; gap: 10px; text-align: left; background: #fff; border: 1px solid var(--sw-border); border-radius: 8px; padding: 10px 12px; cursor: pointer; color: var(--sw-text-2); font: inherit; }
.sc.on { border-color: var(--sw-primary); background: rgba(31, 111, 235, 0.05); color: var(--sw-primary); }
.sc span { display: flex; flex-direction: column; gap: 2px; }
.sc b { font-size: 14px; color: var(--sw-text-1); }
.sc i { font-style: normal; font-size: 12px; color: var(--sw-text-3); line-height: 1.5; }
.sug { text-align: left; background: #fff; border: 1px dashed var(--sw-border); border-radius: 6px; padding: 7px 10px; font: inherit; font-size: 13px; color: var(--sw-text-2); cursor: pointer; }
.sug:hover:not(:disabled) { border-color: var(--sw-primary); color: var(--sw-primary); }
.sug:disabled { opacity: 0.6; cursor: default; }
.rules { margin-top: 8px; font-size: 12px; color: var(--sw-text-3); background: #fff; border: 1px solid var(--sw-border); border-radius: 8px; padding: 10px 12px; }
.rules ul { margin: 6px 0 0; padding-left: 16px; line-height: 1.7; }
.chat { display: flex; flex-direction: column; background: #fff; border: 1px solid var(--sw-border); border-radius: 8px; min-height: 0; overflow: hidden; }
.chat-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; padding: 12px 16px; border-bottom: 1px solid var(--sw-border); }
.prov { margin-left: 10px; font-size: 12px; padding: 2px 8px; border-radius: 10px; background: #eef2f7; color: var(--sw-text-2); }
.prov.deepseek { background: rgba(31, 111, 235, 0.1); color: var(--sw-primary); }
.usage { font-size: 12px; color: var(--sw-text-3); }
.transcript { flex: 1; overflow: auto; padding: 16px; display: flex; flex-direction: column; gap: 18px; background: #f8fafc; }
.welcome { margin: auto; text-align: center; color: var(--sw-text-3); max-width: 420px; }
.turn { display: flex; flex-direction: column; gap: 10px; }
.me { align-self: flex-end; max-width: 80%; background: var(--sw-primary); color: #fff; padding: 9px 14px; border-radius: 14px 14px 2px 14px; font-size: 14px; white-space: pre-wrap; word-break: break-word; }
.tagsc { display: inline-block; font-size: 11px; background: rgba(255, 255, 255, 0.22); border-radius: 3px; padding: 0 6px; margin-right: 6px; }
.composer .tagsc { background: rgba(31, 111, 235, 0.1); color: var(--sw-primary); font-size: 12px; }
.bot { align-self: flex-start; width: min(100%, 860px); background: #fff; border: 1px solid var(--sw-border); border-radius: 2px 14px 14px 14px; padding: 12px 14px; }
.cancel { margin-top: 8px; }
.answer { margin-top: 12px; border-top: 1px dashed var(--sw-border); padding-top: 12px; }
.answer.link { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 14px; }
.md { font-size: 14px; line-height: 1.8; word-break: break-word; }
.md :deep(p) { margin: 0 0 8px; }
.md :deep(ul), .md :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
.md :deep(table) { border-collapse: collapse; font-size: 13px; }
.md :deep(th), .md :deep(td) { border: 1px solid var(--sw-border); padding: 3px 8px; }
.block { margin: 10px 0; }
h5 { margin: 0 0 6px; font-size: 13px; color: var(--sw-text-2); }
.facts { width: 100%; border-collapse: collapse; font-size: 13px; }
.facts th { text-align: left; font-weight: 500; color: var(--sw-text-3); background: var(--sw-bg); padding: 5px 8px; }
.facts td { padding: 5px 8px; border-bottom: 1px solid var(--sw-border); vertical-align: top; }
.src { display: inline-block; font-size: 11px; background: rgba(31, 111, 235, 0.08); color: var(--sw-primary-deep); border-radius: 3px; padding: 0 5px; margin: 0 4px 2px 0; word-break: break-all; }
.evi { font-size: 13px; padding: 5px 0; border-bottom: 1px dashed var(--sw-border); display: flex; gap: 6px; flex-wrap: wrap; align-items: baseline; }
.evi span { color: var(--sw-text-2); flex-basis: 100%; }
.lim ul { margin: 0; padding-left: 18px; font-size: 13px; color: #b8740a; line-height: 1.7; }
.fail { margin-top: 10px; background: rgba(229, 72, 77, 0.06); border: 1px solid rgba(229, 72, 77, 0.3); border-radius: 6px; padding: 10px 12px; font-size: 13px; }
.fail b { color: var(--sw-danger); }
.fail p { margin: 4px 0; }
.cancelled { margin-top: 10px; font-size: 13px; color: #b8740a; }
.foot { margin-top: 10px; }
.composer { border-top: 1px solid var(--sw-border); padding: 10px 14px; }
.mode { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
.row { display: flex; gap: 10px; align-items: flex-end; }
textarea, .mk input { flex: 1; width: 100%; border: 1px solid var(--sw-border); border-radius: 8px; padding: 8px 10px; font: inherit; font-size: 14px; resize: none; outline: none; }
textarea:focus, .mk input:focus { border-color: var(--sw-primary); }
.mk { flex: 1; display: flex; flex-direction: column; gap: 6px; }
.hint { flex: 1; align-self: center; }
.err { color: var(--sw-danger); font-size: 13px; margin-top: 4px; }
.tip { margin-top: 6px; }
@media (max-width: 900px) {
  .assistant { grid-template-columns: 1fr; height: auto; padding: 12px; }
  .left { max-height: none; }
  .rules, .left h3:nth-of-type(2), .sug { display: none; }
  .chat { height: calc(100vh - 56px - 24px); }
  .me { max-width: 92%; }
}
</style>
