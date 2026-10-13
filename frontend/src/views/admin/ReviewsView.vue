<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { EChartsOption } from 'echarts'
import { reviewApi, SENTIMENT_LABELS, type ReviewView, type Sentiment } from '@/api/review'
import type { ReviewBatchResult } from '@/api/agent'
import { errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import KpiTile from '@/components/admin/KpiTile.vue'
import CsvImportDialog from '@/components/admin/CsvImportDialog.vue'
import RunProgress from '@/components/admin/RunProgress.vue'
import EChart from '@/components/charts/EChart.vue'
import { axisStyle, baseOption, C, SENTIMENT_COLORS } from '@/components/charts/palette'
import { useAgentRun, useAsync } from '@/components/admin/composables'
import { dayRange } from '@/components/admin/logic'
import { fmtDateTime, fmtNum, fmtPct } from '@/utils/format'

const app = useAppStore()
const router = useRouter()
const siteId = computed(() => app.currentSite?.id ?? undefined)

const days = ref(30)
const range = computed(() => dayRange(app.today(), days.value))
const topics = useAsync(() => reviewApi.topics())
const topicName = (code: string) => topics.data.value?.find((t) => t.code === code)?.name ?? code

const summary = useAsync(() => reviewApi.summary(siteId.value, range.value.start, range.value.end), [siteId, range])

const q = reactive({ sentiment: '', topic: '', keyword: '', status: '', complaint: false, page: 1, size: 20 })
const list = useAsync(() => reviewApi.list({
  siteId: siteId.value, sentiment: q.sentiment || undefined, topic: q.topic || undefined, keyword: q.keyword.trim() || undefined,
  status: q.status || undefined, complaint: q.complaint || undefined, start: range.value.start, end: range.value.end, page: q.page, size: q.size,
}), [siteId, range, () => q.page, () => q.size])

function search() {
  if (q.page !== 1) q.page = 1
  else list.run()
}
watch(() => [q.sentiment, q.topic, q.status, q.complaint], search)
watch([siteId, range], search)

const sentimentOption = computed<EChartsOption | null>(() => {
  const by = summary.data.value?.bySentiment
  if (!by || !Object.values(by).some((v) => v > 0)) return null
  return {
    tooltip: { trigger: 'item', confine: true },
    legend: { bottom: 0, icon: 'circle', itemWidth: 8 },
    series: [{
      type: 'pie', radius: ['45%', '70%'], center: ['50%', '44%'], label: { formatter: '{b} {d}%' },
      data: Object.entries(by).filter(([, v]) => v > 0).map(([k, v]) => ({ name: SENTIMENT_LABELS[k as Sentiment] ?? k, value: v, itemStyle: { color: SENTIMENT_COLORS[k] ?? C.grey } })),
    }],
  }
})

const topicOption = computed<EChartsOption | null>(() => {
  const rows = [...(summary.data.value?.byTopic ?? [])].filter((r) => r.count > 0).sort((a, b) => a.count - b.count)
  if (!rows.length) return null
  return baseOption({
    grid: { left: 84, right: 24, top: 30, bottom: 24 },
    tooltip: { trigger: 'axis', confine: true, axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', ...axisStyle },
    yAxis: { type: 'category', data: rows.map((r) => r.name), ...axisStyle, splitLine: { show: false } },
    series: [
      { name: '负面', type: 'bar', stack: 't', barMaxWidth: 14, color: C.danger, data: rows.map((r) => r.negative) },
      { name: '其他', type: 'bar', stack: 't', barMaxWidth: 14, color: C.ice, data: rows.map((r) => r.count - r.negative) },
    ],
  })
})

const dailyOption = computed<EChartsOption | null>(() => {
  const rows = summary.data.value?.daily ?? []
  if (!rows.length) return null
  return baseOption({
    xAxis: { type: 'category', data: rows.map((r) => r.date.slice(5)), ...axisStyle, splitLine: { show: false } },
    yAxis: { type: 'value', ...axisStyle, minInterval: 1 },
    series: [
      { name: '评价总数', type: 'line', smooth: true, showSymbol: false, color: C.primary, areaStyle: { opacity: 0.08 }, data: rows.map((r) => r.total) },
      { name: '负面', type: 'line', smooth: true, showSymbol: false, color: C.danger, data: rows.map((r) => r.negative) },
    ],
  })
})

const negShare = computed(() => {
  const s = summary.data.value
  if (!s || !s.total) return null
  return (s.bySentiment.NEGATIVE ?? 0) / s.total
})

// CSV 导入
const importOpen = ref(false)
function doImport(file: File, onProgress: (p: number) => void) {
  return reviewApi.importCsv(file, onProgress)
}
function reload() {
  summary.run()
  list.run()
}

// AI 批量分析
const agent = useAgentRun()
const analyzeOpen = ref(false)
const starting = ref(false)
agent.onDone((r) => {
  if (r.status === 'SUCCEEDED') reload()
})
const batch = computed(() => (agent.run.value?.status === 'SUCCEEDED' ? (agent.run.value.result as ReviewBatchResult | null) : null))

async function analyze() {
  starting.value = true
  try {
    const r = await reviewApi.analyze({ siteId: siteId.value, limit: 100 })
    analyzeOpen.value = true
    agent.track(r.runId)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    starting.value = false
  }
}

const STATUS: Record<ReviewView['analysisStatus'], { t: string; type: string }> = {
  PENDING: { t: '待分析', type: 'info' }, ANALYZED: { t: 'AI 已分析', type: 'primary' }, HUMAN: { t: '人工修正', type: 'success' }, FAILED: { t: '分析失败', type: 'danger' },
}
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>网评舆情</h2>
      <el-radio-group v-model="days" size="small">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
      <span class="grow" />
      <DataMetaBar :meta="summary.data.value?.meta" />
      <el-button v-if="app.has('REVIEW_IMPORT')" size="small" @click="importOpen = true">导入评价 CSV</el-button>
      <el-button v-if="app.has('REVIEW_WRITE') && app.has('AI_USE')" type="primary" size="small" :loading="starting || agent.watching.value" @click="analyze">AI 批量分析</el-button>
    </div>

    <StatePanel :loading="summary.loading.value && !summary.data.value" :error="summary.error.value" :empty="!summary.data.value" min-height="120px" @retry="summary.run">
      <template v-if="summary.data.value">
        <div class="sw-grid kpis">
          <KpiTile label="评价总数" :value="fmtNum(summary.data.value.total)" unit="条" />
          <KpiTile label="平均评分" :value="fmtNum(summary.data.value.avgRating, 1)" unit="分" :accent="C.ice" sub="仅统计带评分的评价" />
          <KpiTile label="负面占比" :value="fmtPct(negShare)" :accent="C.danger" :sub="`负面 ${fmtNum(summary.data.value.bySentiment.NEGATIVE ?? 0)} 条`" />
          <KpiTile label="投诉" :value="fmtNum(summary.data.value.complaintCount)" unit="条" :accent="C.warn" />
        </div>
        <div class="charts">
          <div class="sw-card"><div class="sw-card-title"><span>情绪分布</span></div><EChart v-if="sentimentOption" :option="sentimentOption" height="260px" /><div v-else class="none">暂无已分析评价</div></div>
          <div class="sw-card"><div class="sw-card-title"><span>主题分布（红色为负面）</span></div><EChart v-if="topicOption" :option="topicOption" height="260px" /><div v-else class="none">暂无数据</div></div>
          <div class="sw-card"><div class="sw-card-title"><span>每日评价量</span></div><EChart v-if="dailyOption" :option="dailyOption" height="260px" /><div v-else class="none">暂无数据</div></div>
        </div>
        <div v-if="summary.data.value.hotIssues.length" class="sw-card hot">
          <div class="sw-card-title"><span>热点问题</span><span class="sw-muted">按负面评价集中度汇总，点击样例查看原文</span></div>
          <div class="issues">
            <div v-for="h in summary.data.value.hotIssues" :key="h.topic" class="issue">
              <div class="it"><b>{{ h.name }}</b><span class="num">{{ h.count }} 条</span></div>
              <p v-for="s in h.samples" :key="s.reviewId" @click="router.push(`/admin/reviews/${s.reviewId}`)">“{{ s.text }}”</p>
            </div>
          </div>
        </div>
      </template>
    </StatePanel>

    <div class="sw-card">
      <div class="sw-card-title">
        <span>评价列表</span>
        <span class="filters">
          <el-select v-model="q.sentiment" placeholder="情绪" clearable size="small" style="width: 110px">
            <el-option v-for="(n, k) in SENTIMENT_LABELS" :key="k" :label="n" :value="k" />
          </el-select>
          <el-select v-model="q.topic" placeholder="主题" clearable size="small" style="width: 120px">
            <el-option v-for="t in topics.data.value ?? []" :key="t.code" :label="t.name" :value="t.code" />
          </el-select>
          <el-select v-model="q.status" placeholder="分析状态" clearable size="small" style="width: 120px">
            <el-option v-for="(s, k) in STATUS" :key="k" :label="s.t" :value="k" />
          </el-select>
          <el-checkbox v-model="q.complaint" size="small">仅投诉</el-checkbox>
          <el-input v-model="q.keyword" placeholder="关键词" clearable size="small" style="width: 150px" @keyup.enter="search" @clear="search" />
          <el-button size="small" @click="search">查询</el-button>
        </span>
      </div>
      <StatePanel :loading="list.loading.value && !list.data.value" :error="list.error.value" :empty="!list.data.value?.items.length" empty-text="没有符合条件的评价" @retry="list.run">
        <el-table :data="list.data.value?.items ?? []" size="small" @row-click="(r: ReviewView) => router.push(`/admin/reviews/${r.id}`)">
          <el-table-column label="发布时间" width="140"><template #default="{ row }">{{ fmtDateTime(row.publishedAt) }}</template></el-table-column>
          <el-table-column prop="platform" label="来源" width="90" />
          <el-table-column label="评分" width="60" align="right"><template #default="{ row }"><span class="num">{{ fmtNum(row.rating, 1) }}</span></template></el-table-column>
          <el-table-column label="内容（已脱敏）" min-width="280">
            <template #default="{ row }">
              <div class="content">{{ row.contentMasked }}</div>
              <el-tag v-if="row.isComplaint" size="small" type="danger" effect="plain">投诉</el-tag>
              <el-tag v-if="row.dataMode === 'DEMO'" size="small" type="info" effect="plain" style="margin-left: 4px">演示</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="情绪" width="96">
            <template #default="{ row }">
              <span v-if="row.analysis" class="sent" :style="{ color: SENTIMENT_COLORS[row.analysis.sentiment] }">{{ SENTIMENT_LABELS[row.analysis.sentiment as Sentiment] }}</span>
              <span v-else class="sw-muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="主题" min-width="140">
            <template #default="{ row }">
              <el-tag v-for="t in row.analysis?.topics ?? []" :key="t" size="small" effect="plain" style="margin: 0 4px 2px 0">{{ topicName(t) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="STATUS[row.analysisStatus as ReviewView['analysisStatus']].type">{{ STATUS[row.analysisStatus as ReviewView['analysisStatus']].t }}</el-tag>
              <div v-if="row.analysis?.needsHumanReview" class="need">建议人工复核</div>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-model:current-page="q.page" v-model:page-size="q.size" class="pager" small background layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100]" :total="list.data.value?.total ?? 0" />
      </StatePanel>
    </div>

    <CsvImportDialog
      v-model="importOpen"
      title="导入网络评价"
      header="source_record_id,site_code,platform,published_at,rating,content,author,is_complaint"
      :notes="[
        'author、is_complaint、rating 可为空；published_at 为带时区的 ISO-8601 时间',
        '相同 source_record_id 重复导入会被跳过；逐行错误会返回行号',
        '导入时自动脱敏手机号、证件号；只能导入已授权景区（site_code）的评价',
      ]"
      :uploader="doImport"
      @done="reload"
    />

    <el-dialog v-model="analyzeOpen" title="AI 批量分析" width="min(520px, 94vw)">
      <RunProgress :status="agent.status.value" :progress="agent.progress.value" :steps="agent.steps.value" :error-message="agent.run.value?.errorMessage" />
      <div v-if="batch" class="batch num">
        <span>已分析 <b>{{ batch.analyzed }}</b></span>
        <span>跳过人工修正 <b>{{ batch.skippedHuman }}</b></span>
        <span>失败 <b>{{ batch.failed }}</b></span>
        <span>建议人工复核 <b>{{ batch.needsHumanReview }}</b></span>
      </div>
      <p class="sw-muted">已人工修正的评价不会被批量分析覆盖。{{ app.system?.aiProvider === 'fake' ? '当前使用本地规则模拟器，未调用云端模型。' : '' }}</p>
      <template #footer>
        <el-button v-if="agent.watching.value" @click="agent.cancel">取消任务</el-button>
        <el-button type="primary" @click="analyzeOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
.grow { flex: 1; }
.kpis { grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); margin-bottom: 16px; }
.charts { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr)); gap: 16px; }
.none { height: 260px; display: flex; align-items: center; justify-content: center; color: var(--sw-text-3); }
.hot { margin-top: 16px; }
.issues { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 260px), 1fr)); gap: 12px; }
.issue { background: var(--sw-bg); border-radius: 6px; padding: 10px 12px; border-left: 3px solid var(--sw-danger); }
.it { display: flex; justify-content: space-between; margin-bottom: 4px; }
.issue p { margin: 4px 0 0; font-size: 12px; color: var(--sw-text-2); cursor: pointer; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.issue p:hover { color: var(--sw-primary); }
.filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-weight: 400; justify-content: flex-end; }
.content { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.sent { font-weight: 600; }
.need { font-size: 12px; color: #b8740a; }
.pager { margin-top: 12px; justify-content: flex-end; }
.batch { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 12px; padding: 10px; background: var(--sw-bg); border-radius: 6px; }
:deep(.el-table__row) { cursor: pointer; }
</style>
