<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { agentApi, asList, type MarketingPlan, type MarketingRef } from '@/api/agent'
import { ApiError, errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import RunProgress from '@/components/admin/RunProgress.vue'
import { useAgentRun, useAsync } from '@/components/admin/composables'
import { fmtDateTime, fmtPct } from '@/utils/format'

const app = useAppStore()
const canAi = computed(() => app.has('AI_USE'))

const plans = useAsync(async () => asList(await agentApi.plans()))
const visiblePlans = computed(() => (plans.data.value ?? [])
  .filter(p => app.isGlobal || !app.currentSite || p.siteIds.includes(app.currentSite.id))
  .slice().sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id))
const STATUS: Record<MarketingPlan['status'], { n: string; type: string }> = { DRAFT: { n: '草稿', type: 'info' }, APPROVED: { n: '已采纳', type: 'success' }, ARCHIVED: { n: '已归档', type: 'warning' } }
const siteNames = (ids: number[]) => ids.map((id) => app.sites.find((s) => s.id === id)?.shortName ?? `#${id}`).join('、')

// 详情
const current = ref<MarketingPlan | null>(null)
const drawer = ref(false)
const detailLoading = ref(false)
async function open(id: number) {
  drawer.value = true
  detailLoading.value = true
  try {
    current.value = await agentApi.plan(id)
  } catch (e) {
    ElMessage.error(errorText(e))
    drawer.value = false
  } finally {
    detailLoading.value = false
  }
}

async function setStatus(status: 'APPROVED' | 'ARCHIVED') {
  if (!current.value) return
  try {
    current.value = await agentApi.setPlanStatus(current.value.id, status)
    ElMessage.success(status === 'APPROVED' ? '已标记为采纳（仅内部状态，不会对外发布）' : '已归档')
    plans.run()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

// AI 生成
const gen = ref(false)
const form = reactive<{ goal: string; segment: string; siteIds: number[] }>({ goal: '', segment: '', siteIds: [] })
const starting = ref(false)
const genError = ref('')
const agent = useAgentRun()
agent.onDone((r) => {
  if (r.status === 'SUCCEEDED') {
    plans.run()
    const made = r.result as MarketingRef | null
    if (made?.planId) {
      gen.value = false
      open(made.planId)
    }
  }
})

function openGen() {
  form.goal = ''
  form.segment = ''
  form.siteIds = app.currentSite ? [app.currentSite.id] : []
  genError.value = ''
  agent.stop()
  agent.status.value = null
  gen.value = true
}

async function start() {
  if (!form.goal.trim()) {
    genError.value = '请填写营销目标'
    return
  }
  starting.value = true
  genError.value = ''
  try {
    const r = await agentApi.create({ scenario: 'MARKETING', goal: form.goal.trim(), segment: form.segment.trim() || undefined, siteIds: form.siteIds.length ? form.siteIds : undefined })
    agent.track(r.runId)
  } catch (e) {
    genError.value = e instanceof ApiError && e.code === 'QUOTA_EXCEEDED' ? '今日 AI 调用额度已用完' : errorText(e)
  } finally {
    starting.value = false
  }
}

const GOALS = ['提升工作日客流，联动酒店与餐饮推出两日游套票', '针对省外年轻客群设计冰雪夜场差异化套票', '春节假期错峰引流，缓解核心区域排队']
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>套票与营销方案</h2>
      <span class="sw-muted">方案为 AI 基于客流、画像、业态数据生成的草稿，需人工审核；系统不会自动创建或发布任何营销活动</span>
      <span class="grow" />
      <el-button v-if="canAi" type="primary" size="small" @click="openGen">AI 生成方案</el-button>
    </div>

    <StatePanel :loading="plans.loading.value && !plans.data.value" :error="plans.error.value" :empty="!visiblePlans.length" empty-text="还没有营销方案，可点击“AI 生成方案”" min-height="240px" @retry="plans.run">
      <div class="cards">
        <div v-for="p in visiblePlans" :key="p.id" class="sw-card plan" @click="open(p.id)">
          <div class="pt"><b>{{ p.title }}</b><el-tag size="small" :type="STATUS[p.status].type">{{ STATUS[p.status].n }}</el-tag></div>
          <p>{{ p.content?.summary }}</p>
          <div class="sw-muted">
            <span>景区：{{ siteNames(p.siteIds) }}</span>
            <span v-if="p.targetSegment"> · 客群：{{ p.targetSegment }}</span>
          </div>
          <div class="sw-muted">{{ p.createdByName ?? '—' }} · {{ fmtDateTime(p.createdAt) }}</div>
        </div>
      </div>
    </StatePanel>

    <el-drawer v-model="drawer" :title="current?.title ?? '营销方案'" size="min(680px, 100vw)">
      <div v-if="detailLoading" class="sw-muted">加载中…</div>
      <div v-else-if="current" class="detail">
        <div class="line">
          <el-tag :type="STATUS[current.status].type">{{ STATUS[current.status].n }}</el-tag>
          <el-tag type="warning" effect="plain">AI 生成草稿</el-tag>
          <span class="sw-muted">{{ siteNames(current.siteIds) }} · {{ fmtDateTime(current.createdAt) }}</span>
        </div>
        <p class="sum">{{ current.content.summary }}</p>

        <h4>目标客群</h4>
        <div v-for="s in current.content.segments" :key="s.name" class="seg">
          <b>{{ s.name }}</b><span v-if="s.share != null" class="num share">占比 {{ fmtPct(s.share) }}</span>
          <div class="sw-muted">依据：{{ s.evidence }}</div>
        </div>

        <h4>套票 / 产品组合</h4>
        <div v-for="k in current.content.packages" :key="k.name" class="pkg">
          <div class="line"><b>{{ k.name }}</b><span class="price">{{ k.priceHint }}</span><span class="sw-muted">{{ k.targetDays }}</span></div>
          <ul><li v-for="i in k.items" :key="i">{{ i }}</li></ul>
          <div class="why">{{ k.rationale }}</div>
          <div v-if="k.basis.length" class="basis"><span class="sw-muted">数据依据</span><code v-for="b in k.basis" :key="b">{{ b }}</code></div>
        </div>

        <div class="cols">
          <div><h4>投放渠道</h4><ul><li v-for="c in current.content.channels" :key="c">{{ c }}</li></ul></div>
          <div><h4>评估指标</h4><ul><li v-for="c in current.content.kpis" :key="c">{{ c }}</li></ul></div>
        </div>
        <h4>风险提示</h4>
        <ul class="risk"><li v-for="c in current.content.risks" :key="c">{{ c }}</li></ul>
        <p class="disc">{{ current.content.disclaimer }}</p>
      </div>
      <template #footer>
        <template v-if="current && app.has('MARKETING_WRITE')">
          <el-button v-if="current.status !== 'ARCHIVED'" @click="setStatus('ARCHIVED')">归档</el-button>
          <el-button v-if="current.status === 'DRAFT'" type="primary" @click="setStatus('APPROVED')">标记为采纳</el-button>
        </template>
      </template>
    </el-drawer>

    <el-dialog v-model="gen" title="AI 生成营销方案" width="min(560px, 94vw)" :close-on-click-modal="false">
      <template v-if="!agent.status.value">
        <el-form label-position="top">
          <el-form-item label="营销目标" required>
            <el-input v-model="form.goal" type="textarea" :rows="3" maxlength="300" show-word-limit placeholder="描述希望达成的目标" />
            <div class="sugs"><span v-for="g in GOALS" :key="g" @click="form.goal = g">{{ g }}</span></div>
          </el-form-item>
          <el-form-item label="目标客群（可选）"><el-input v-model="form.segment" maxlength="100" placeholder="如：省外亲子家庭" /></el-form-item>
          <el-form-item label="涉及景区">
            <el-select v-model="form.siteIds" multiple placeholder="默认全部授权景区" style="width: 100%">
              <el-option v-for="s in app.sites" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>
        </el-form>
        <div v-if="genError" class="err">{{ genError }}</div>
      </template>
      <template v-else>
        <RunProgress :status="agent.status.value" :progress="agent.progress.value" :steps="agent.steps.value" :error-message="agent.run.value?.errorMessage" />
      </template>
      <template #footer>
        <el-button v-if="agent.watching.value" @click="agent.cancel">取消任务</el-button>
        <el-button v-else @click="gen = false">关闭</el-button>
        <el-button v-if="!agent.status.value" type="primary" :loading="starting" @click="start">开始生成</el-button>
        <el-button v-else-if="!agent.watching.value && agent.status.value !== 'SUCCEEDED'" type="primary" @click="agent.status.value = null">重新填写</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
h4 { margin: 18px 0 8px; font-size: 14px; }
.grow { flex: 1; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr)); gap: 16px; }
.plan { cursor: pointer; }
.plan:hover { border-color: var(--sw-primary); }
.pt { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
.plan p { color: var(--sw-text-2); font-size: 13px; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; margin: 8px 0; }
.line { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.sum { line-height: 1.8; color: var(--sw-text-2); }
.seg { padding: 8px 10px; background: var(--sw-bg); border-radius: 6px; margin-bottom: 6px; }
.share { margin-left: 8px; color: var(--sw-primary); font-size: 13px; }
.pkg { border: 1px solid var(--sw-border); border-left: 3px solid var(--sw-primary); border-radius: 6px; padding: 10px 12px; margin-bottom: 8px; }
.price { color: var(--sw-danger); font-weight: 600; }
.pkg ul, .cols ul, .risk { margin: 6px 0; padding-left: 18px; font-size: 13px; color: var(--sw-text-2); line-height: 1.7; }
.why { font-size: 13px; }
.basis { margin-top: 6px; display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.basis code { font-size: 12px; background: var(--sw-bg); padding: 1px 6px; border-radius: 3px; }
.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.risk { color: #b8740a; }
.disc { font-size: 12px; color: var(--sw-text-3); border-top: 1px dashed var(--sw-border); padding-top: 10px; }
.sugs { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
.sugs span { font-size: 12px; color: var(--sw-primary); cursor: pointer; line-height: 1.5; }
.err { color: var(--sw-danger); font-size: 13px; }
@media (max-width: 560px) { .cols { grid-template-columns: 1fr; } }
</style>
