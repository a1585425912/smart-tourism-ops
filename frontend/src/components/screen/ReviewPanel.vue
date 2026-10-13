<script setup lang="ts">
import { computed, watch } from 'vue'
import type { EChartsOption } from 'echarts'
import { screenApi } from '@/api/screen'
import ScreenPanel from './ScreenPanel.vue'
import EChart from './EChart.vue'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'
import { addDays, dayStartIso } from './screenUtils'

/** 网评舆情: 近 7 日情绪分布、热点主题与最新评价(脱敏文本, 纯文本渲染)。 */
const ctx = useScreen()

const SENTIMENT: Record<string, { label: string; color: string }> = {
  POSITIVE: { label: '正面', color: '#36e3a0' },
  NEUTRAL: { label: '中性', color: '#5ec8f2' },
  MIXED: { label: '褒贬兼有', color: '#b48cff' },
  NEGATIVE: { label: '负面', color: '#ff4d6d' },
  UNKNOWN: { label: '未分析', color: '#6b7a90' },
}

const summary = usePanel(() => {
  const site = ctx.selectedSiteId.value ?? undefined
  return screenApi.reviewSummary(site, dayStartIso(addDays(ctx.today.value, -6)), ctx.range.value.end)
}, [ctx.selectedSiteId])
const latest = usePanel(() => screenApi.reviews(ctx.selectedSiteId.value ?? undefined, 10), [ctx.selectedSiteId])
watch(ctx.tick, () => { summary.reload(); latest.reload() }, { immediate: true })

const s = computed(() => summary.data.value)
const topics = computed(() => [...(s.value?.byTopic ?? [])].sort((a, b) => b.count - a.count).slice(0, 5))
const topicMax = computed(() => Math.max(1, ...topics.value.map((t) => t.count)))
const negativeShare = computed(() => {
  const total = s.value?.total ?? 0
  return total > 0 ? (s.value!.bySentiment['NEGATIVE'] ?? 0) / total : null
})

const ring = computed<EChartsOption>(() => ({
  tooltip: { trigger: 'item', backgroundColor: 'rgba(6,20,40,0.92)', borderColor: 'rgba(94,200,242,0.4)', textStyle: { color: '#d8f1ff', fontSize: 12 } },
  series: [{
    type: 'pie', radius: ['62%', '86%'], center: ['50%', '50%'], label: { show: false }, padAngle: 2,
    itemStyle: { borderRadius: 3 },
    data: Object.entries(s.value?.bySentiment ?? {}).filter(([, v]) => v > 0)
      .map(([k, v]) => ({ name: SENTIMENT[k]?.label ?? k, value: v, itemStyle: { color: SENTIMENT[k]?.color ?? '#6b7a90' } })),
  }],
}))

const items = computed(() => latest.data.value?.items ?? [])
// 条目足够多时复制一份实现无缝滚动
const scrolling = computed(() => items.value.length > 3)
</script>

<template>
  <ScreenPanel title="网评舆情" sub="近 7 日" :mode="s?.meta.dataMode" :loading="summary.loading.value" :error="s ? null : summary.error.value"
    :empty="!!s && s.total === 0" empty-text="近 7 日暂无评价" @retry="summary.reload">
    <div v-if="s" class="rv">
      <div class="top">
        <div class="ring">
          <EChart :option="ring" />
          <div class="ring-c"><b>{{ s.total }}</b><span>评价</span></div>
        </div>
        <div class="side">
          <div class="stats">
            <span>均分 <b>{{ s.avgRating === null ? '—' : s.avgRating.toFixed(1) }}</b></span>
            <span>负面占比 <b class="neg">{{ negativeShare === null ? '—' : (negativeShare * 100).toFixed(1) + '%' }}</b></span>
            <span>投诉 <b>{{ s.complaintCount }}</b></span>
          </div>
          <ul class="topics">
            <li v-for="t in topics" :key="t.topic">
              <span class="tn">{{ t.name }}</span>
              <span class="tb">
                <i :style="{ width: (t.count / topicMax) * 100 + '%' }" />
                <i class="n" :style="{ width: (t.negative / topicMax) * 100 + '%' }" />
              </span>
              <span class="tc">{{ t.count }}</span>
            </li>
          </ul>
        </div>
      </div>
      <div class="feed">
        <div v-if="latest.error.value && !items.length" class="feed-err">最新评价加载失败</div>
        <div v-else-if="!items.length" class="feed-err">暂无最新评价</div>
        <div v-else class="track" :class="{ scrolling }" :style="{ animationDuration: items.length * 4 + 's' }">
          <div v-for="(r, i) in scrolling ? [...items, ...items] : items" :key="i" class="item">
            <i :style="{ background: SENTIMENT[r.analysis?.sentiment ?? 'UNKNOWN']?.color ?? '#6b7a90' }" />
            <span class="plat">{{ r.platform }}</span>
            <span class="txt">{{ r.contentMasked }}</span>
          </div>
        </div>
      </div>
    </div>
  </ScreenPanel>
</template>

<style scoped>
.rv { height: 100%; display: flex; flex-direction: column; gap: 8px; }
.top { display: flex; gap: 12px; height: 124px; flex: none; }
.ring { position: relative; width: 124px; flex: none; }
.ring-c { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; pointer-events: none; }
.ring-c b { font-size: 22px; color: #e6f6ff; font-variant-numeric: tabular-nums; }
.ring-c span { font-size: 11px; color: #7fa6c9; }
.side { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.stats { display: flex; gap: 12px; font-size: 12px; color: #7fa6c9; white-space: nowrap; }
.stats b { color: #e6f6ff; font-variant-numeric: tabular-nums; }
.stats b.neg { color: #ff8a9b; }
.topics { list-style: none; margin: 0; padding: 0; flex: 1; display: flex; flex-direction: column; justify-content: space-between; }
.topics li { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #c5e2f7; }
.tn { width: 62px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tb { position: relative; flex: 1; height: 6px; background: rgba(94, 200, 242, 0.1); border-radius: 3px; overflow: hidden; }
.tb i { position: absolute; left: 0; top: 0; bottom: 0; background: #2e9bff; border-radius: 3px; }
.tb i.n { background: #ff4d6d; }
.tc { width: 30px; text-align: right; font-variant-numeric: tabular-nums; color: #9fc3e0; }
.feed { flex: 1; min-height: 0; overflow: hidden; border-top: 1px dashed rgba(94, 200, 242, 0.18); padding-top: 6px;
  mask-image: linear-gradient(transparent, #000 12%, #000 88%, transparent); }
.feed-err { font-size: 12px; color: #6f8fb0; text-align: center; padding-top: 10px; }
.track.scrolling { animation: rv-scroll linear infinite; }
.feed:hover .track { animation-play-state: paused; }
@keyframes rv-scroll { to { transform: translateY(-50%); } }
.item { display: flex; align-items: center; gap: 6px; height: 26px; font-size: 12px; color: #b9d6ec; }
.item i { width: 6px; height: 6px; border-radius: 50%; flex: none; }
.plat { color: #7fa6c9; flex: none; }
.txt { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (prefers-reduced-motion: reduce) { .track.scrolling { animation: none; } }
</style>
