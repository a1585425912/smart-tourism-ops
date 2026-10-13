<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import type { EChartsOption } from 'echarts'
import { flowApi, type QueueStatus } from '@/api/flow'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import DataMetaBar from '@/components/common/DataMetaBar.vue'
import EChart from '@/components/charts/EChart.vue'
import { C } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { fmtNum, fmtTime } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)
const queues = useAsync(async () => (siteId.value ? flowApi.queues(siteId.value) : []), [siteId])

let timer: ReturnType<typeof setInterval>
onMounted(() => (timer = setInterval(queues.run, 60000)))
onBeforeUnmount(() => clearInterval(timer))

const TYPES: Record<QueueStatus['pointType'], string> = { RIDE: '游乐项目', TICKET: '售票', GATE: '检票口', SHOW: '演出', SERVICE: '服务点' }

const sorted = computed(() => [...(queues.data.value ?? [])].sort((a, b) => (b.estimatedWaitMin ?? -1) - (a.estimatedWaitMin ?? -1)))

function waitColor(v: number | null) {
  if (v == null) return C.grey
  return v >= 60 ? C.danger : v >= 30 ? C.warn : C.ok
}

function spark(q: QueueStatus): EChartsOption | null {
  if (!q.predicted.length) return null
  return {
    grid: { left: 30, right: 8, top: 10, bottom: 20 },
    tooltip: { trigger: 'axis', confine: true, valueFormatter: (v) => `${v} 分钟` },
    xAxis: { type: 'time', axisLine: { lineStyle: { color: C.axis } }, axisTick: { show: false }, axisLabel: { color: C.text, fontSize: 10, formatter: '{HH}:{mm}' }, splitLine: { show: false } },
    yAxis: { type: 'value', min: 0, axisLabel: { color: C.text, fontSize: 10 }, splitLine: { lineStyle: { color: C.split } } },
    series: [{ name: '预计等待', type: 'line', smooth: true, showSymbol: false, lineStyle: { width: 2, type: 'dashed' }, color: C.primary, areaStyle: { opacity: 0.08 }, data: q.predicted.map((p) => [p.t, p.waitMin]) }],
  }
}
</script>

<template>
  <div class="sw-page">
    <div class="head">
      <h2>排队时长</h2>
      <span class="sw-muted">“估计”由排队人数 ÷ 服务能力推算，“实测”来自现场测量；二者分开展示，没有数据时显示暂无数据</span>
    </div>
    <StatePanel :loading="queues.loading.value && !queues.data.value" :error="queues.error.value" :empty="!sorted.length" empty-text="该景区尚未配置排队点位" min-height="200px" @retry="queues.run">
      <div class="cards">
        <div v-for="q in sorted" :key="q.pointId" class="sw-card">
          <div class="sw-card-title">
            <span>{{ q.name }} <span class="type">{{ TYPES[q.pointType] }}</span></span>
            <DataMetaBar :meta="q.meta" compact />
          </div>
          <div class="vals num">
            <div>
              <span class="k">估计等待</span>
              <b :style="{ color: waitColor(q.estimatedWaitMin) }">{{ q.estimatedWaitMin == null ? '暂无数据' : fmtNum(q.estimatedWaitMin) }}</b>
              <i v-if="q.estimatedWaitMin != null">分钟</i>
            </div>
            <div>
              <span class="k">实测等待</span>
              <b>{{ q.measuredWaitMin == null ? '暂无数据' : fmtNum(q.measuredWaitMin) }}</b>
              <i v-if="q.measuredWaitMin != null">分钟</i>
            </div>
            <div>
              <span class="k">排队人数</span>
              <b>{{ q.queueLength == null ? '暂无数据' : fmtNum(q.queueLength) }}</b>
              <i v-if="q.queueLength != null">人</i>
            </div>
          </div>
          <div class="sw-muted">服务能力 {{ fmtNum(q.serviceRatePerMin, 1) }} 人/分钟 · 更新 {{ fmtTime(q.at) }}</div>
          <div class="sw-muted method">估算方法：{{ q.method }}</div>
          <div class="pred">
            <span class="sw-muted">未来数小时预计等待（预测值）</span>
            <EChart v-if="spark(q)" :option="spark(q)" height="110px" />
            <div v-else class="none">暂无预测</div>
          </div>
        </div>
      </div>
    </StatePanel>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 330px), 1fr)); gap: 16px; }
.type { font-size: 12px; font-weight: 400; color: var(--sw-primary); background: rgba(31, 111, 235, 0.1); padding: 0 6px; border-radius: 3px; margin-left: 4px; }
.vals { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin-bottom: 8px; }
.vals .k { display: block; font-size: 12px; color: var(--sw-text-3); }
.vals b { font-size: 22px; }
.vals i { font-style: normal; font-size: 12px; color: var(--sw-text-3); margin-left: 2px; }
.vals b:not(:has(+ i)) { font-size: 14px; font-weight: 400; color: var(--sw-text-3); }
.method { margin-top: 2px; }
.pred { margin-top: 10px; border-top: 1px dashed var(--sw-border); padding-top: 8px; }
.none { height: 60px; display: flex; align-items: center; justify-content: center; color: var(--sw-text-3); font-size: 12px; }
</style>
