<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { reportApi, type ReportListItem } from '@/api/report'
import StatePanel from '@/components/common/StatePanel.vue'
import { useAsync } from '@/components/admin/composables'
import { fmtDateTime, fmtNum } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

const report = useAsync(() => reportApi.get(id))
const r = computed(() => report.data.value)

// 同一景区同一日期的其它版本
const versions = ref<ReportListItem[]>([])
watch(r, async (v) => {
  if (!v) return
  try {
    const page = await reportApi.list({ siteId: v.siteId, page: 1, size: 200, reportDate: v.reportDate })
    versions.value = page.items.filter((x) => x.reportDate === v.reportDate).sort((a, b) => b.version - a.version)
  } catch {
    versions.value = []
  }
})

const KIND: Record<string, string> = { FACT: '事实', ANALYSIS: '分析', ADVICE: '建议' }
const BASIS: Record<string, string> = { entries:'入园人次', exits:'出园人次', peak:'在园峰值', inPark:'在园人数', capacity:'承载量', revenue:'经营支付金额', orders:'经营订单', hotelOccupancy:'酒店入住率', groups:'预约及入园团队', groupVisitors:'团队游客', reviews:'当日网评', negativeReviews:'负面网评', rating:'平均评分', inspectionTasks:'新建巡检', inspectionCompleted:'当日任务完成', rectification:'整改及复核任务', closed:'开放状态' }
const showSnapshot = ref(false)
const snapshotText = computed(() => (r.value ? JSON.stringify(r.value.snapshot, null, 2) : ''))

function fmtVal(v: number | string | null) {
  if (v == null) return '暂无数据'
  return typeof v === 'number' ? fmtNum(v, Number.isInteger(v) ? 0 : 2) : v
}
function print() {
  window.print()
}
</script>

<template>
  <div class="sw-page">
    <div class="bar no-print">
      <el-button link type="primary" @click="router.push('/admin/reports')">← 返回列表</el-button>
      <span class="grow" />
      <el-select v-if="versions.length > 1" :model-value="id" size="small" style="width: 150px" @update:model-value="(v: number) => router.push(`/admin/reports/${v}`)">
        <el-option v-for="v in versions" :key="v.id" :label="`第 ${v.version} 版 · ${fmtDateTime(v.createdAt).slice(5)}`" :value="v.id" />
      </el-select>
      <el-button size="small" :disabled="!r" @click="print">打印 / 导出 PDF</el-button>
    </div>

    <StatePanel :loading="report.loading.value" :error="report.error.value" :empty="!r" min-height="300px" @retry="report.run">
      <article v-if="r" class="paper">
        <header>
          <h1>{{ r.title }}</h1>
          <div class="meta">
            <span>{{ r.siteName }}</span><span>报告日期 {{ r.reportDate }}</span><span>第 {{ r.version }} 版</span>
            <span>数据截止 {{ fmtDateTime(r.dataAsOf) }}</span>
            <span class="mode" :class="{ real: r.dataMode === 'REAL' }">{{ r.dataMode === 'REAL' ? '真实接入数据' : r.dataMode === 'MIXED' ? '含演示数据' : '演示数据' }}</span>
          </div>
          <div class="meta sub">
            <span>生成：{{ r.provider === 'deepseek' ? 'DeepSeek' : '本地规则模拟' }} {{ r.model ?? '' }}</span>
            <span>提示版本 {{ r.promptVersion ?? '—' }}</span><span>{{ r.createdByName ?? '—' }} · {{ fmtDateTime(r.createdAt) }}</span>
          </div>
        </header>

        <section class="summary">{{ r.content.summary }}</section>

        <section v-for="(s, i) in r.content.sections" :key="i" class="sec" :class="s.kind.toLowerCase()">
          <h2><span class="kind">{{ KIND[s.kind] ?? s.kind }}</span>{{ s.title }}</h2>
          <p v-for="(p, j) in s.paragraphs" :key="j">{{ p }}</p>
          <table v-if="s.facts.length" class="facts">
            <thead><tr><th>指标</th><th>数值</th><th>来源</th></tr></thead>
            <tbody>
              <tr v-for="(f, k) in s.facts" :key="k">
                <td>{{ f.metric }}</td>
                <td class="num"><b>{{ fmtVal(f.value) }}</b> {{ f.value == null ? '' : f.unit }}</td>
                <td><code v-for="x in f.sourceIds" :key="x">{{ x }}</code></td>
              </tr>
            </tbody>
          </table>
        </section>

        <section v-if="r.content.risks.length" class="sec risk">
          <h2><span class="kind">风险</span>风险提示</h2>
          <ul><li v-for="x in r.content.risks" :key="x">{{ x }}</li></ul>
        </section>

        <section v-if="r.content.suggestions.length" class="sec advice">
          <h2><span class="kind">行动</span>后续运营策略与行动计划</h2>
          <div v-for="(s, i) in r.content.suggestions" :key="i" class="sug">
            <b>{{ i + 1 }}. {{ s.title }}</b>
            <div v-if="s.horizon || s.owner" class="meta">
              <span v-if="s.horizon">执行时段：{{ s.horizon }}</span><span v-if="s.owner">责任岗位：{{ s.owner }}</span><span v-if="s.priority">优先级：{{ s.priority }}</span>
            </div>
            <p>{{ s.detail }}</p>
            <p v-if="s.kpi" class="note">目标与复盘：{{ s.kpi }}</p>
            <div v-if="s.basis.length" class="basis">依据：<code v-for="b in s.basis" :key="b">{{ BASIS[b] ?? b }}</code></div>
          </div>
          <p class="note">建议段由 AI 根据事实段生成，仅供参考，需由管理人员判断后执行。</p>
        </section>

        <footer>
          <div>数据覆盖：{{ r.content.coverage }}</div>
          <div class="no-print">
            <el-button link type="primary" size="small" @click="showSnapshot = !showSnapshot">{{ showSnapshot ? '收起' : '查看' }}统计快照（生成时固定保存的原始统计）</el-button>
            <pre v-if="showSnapshot">{{ snapshotText }}</pre>
          </div>
        </footer>
      </article>
    </StatePanel>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; }
.grow { flex: 1; }
.paper { background: #fff; border: 1px solid var(--sw-border); border-radius: 8px; padding: 32px 40px; max-width: 920px; margin: 0 auto; width: 100%; }
h1 { margin: 0 0 10px; font-size: 24px; }
.meta { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 13px; color: var(--sw-text-2); }
.meta.sub { color: var(--sw-text-3); font-size: 12px; margin-top: 4px; }
.mode { color: #b8740a; background: rgba(230, 162, 60, 0.14); padding: 0 6px; border-radius: 3px; }
.mode.real { color: var(--sw-ok); background: rgba(47, 168, 107, 0.12); }
.summary { margin: 20px 0; padding: 14px 16px; background: var(--sw-bg); border-left: 4px solid var(--sw-primary); border-radius: 0 6px 6px 0; line-height: 1.9; white-space: pre-wrap; }
.sec { margin-top: 22px; }
h2 { font-size: 17px; margin: 0 0 10px; display: flex; align-items: center; gap: 8px; }
.kind { font-size: 12px; font-weight: 400; padding: 1px 8px; border-radius: 3px; background: rgba(31, 111, 235, 0.1); color: var(--sw-primary); }
.sec.analysis .kind { background: rgba(139, 92, 246, 0.12); color: #7c3aed; }
.sec.advice .kind { background: rgba(47, 168, 107, 0.12); color: var(--sw-ok); }
.sec.risk .kind { background: rgba(229, 72, 77, 0.1); color: var(--sw-danger); }
.sec.advice, .sec.risk { border: 1px dashed var(--sw-border); border-radius: 6px; padding: 14px 16px; }
.sec.advice { background: rgba(47, 168, 107, 0.04); }
.sec p { line-height: 1.9; margin: 0 0 8px; color: var(--sw-text-1); white-space: pre-wrap; }
.facts { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 6px; }
.facts th { text-align: left; background: var(--sw-bg); color: var(--sw-text-3); font-weight: 500; padding: 6px 10px; }
.facts td { padding: 6px 10px; border-bottom: 1px solid var(--sw-border); }
code { font-size: 11px; background: rgba(31, 111, 235, 0.08); color: var(--sw-primary-deep); border-radius: 3px; padding: 0 5px; margin-right: 4px; word-break: break-all; }
.risk ul { margin: 0; padding-left: 20px; line-height: 1.9; }
.sug { margin-bottom: 10px; }
.sug p { margin: 2px 0 4px; }
.basis { font-size: 12px; color: var(--sw-text-3); }
.note { font-size: 12px; color: var(--sw-text-3) !important; }
footer { margin-top: 24px; padding-top: 12px; border-top: 1px solid var(--sw-border); font-size: 12px; color: var(--sw-text-3); }
pre { background: #0b1220; color: #cbd5e1; padding: 12px; border-radius: 6px; overflow: auto; max-height: 360px; font-size: 12px; }
@media (max-width: 700px) { .paper { padding: 18px 16px; } }
@media print {
  .no-print { display: none !important; }
  .paper { border: 0; padding: 0; max-width: none; }
  .sec { break-inside: avoid; }
}
</style>
