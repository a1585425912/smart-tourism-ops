<script setup lang="ts">
import { computed } from 'vue'
import ScreenPanel from './ScreenPanel.vue'
import CountUp from './CountUp.vue'
import { useScreen } from './screenContext'
import { levelColor, LEVEL_LABELS, type Level } from './screenUtils'
import { pct, signedPct } from './screenData'

/** 今日客流: 数据来自 /overview (各景区 today 汇总)。 */
const ctx = useScreen()

const rows = computed(() => (ctx.overview.value?.sites ?? []).filter((s) => ctx.siteIds.value.includes(s.siteId)))
const single = computed(() => (ctx.selectedSiteId.value !== null ? rows.value[0] ?? null : null))

const kpi = computed(() => {
  const ov = ctx.overview.value
  if (!ov) return null
  if (single.value) {
    const t = single.value.today
    return { entries: t.entries, inPark: t.inParkCurrent, capacity: t.capacity, utilization: t.utilization, yoy: t.entriesYoy, peak: t.inParkPeak, level: t.loadLevel as Level }
  }
  const { entries, inPark, capacity } = ov.totals
  // 全部景区的承载率 = 在园合计 / 承载量合计(由接口返回的两个合计值相除)
  const utilization = inPark !== null && capacity > 0 ? inPark / capacity : null
  return { entries, inPark, capacity, utilization, yoy: null, peak: null, level: null as Level | null }
})

const ring = computed(() => {
  const u = kpi.value?.utilization
  const r = 52
  const len = 2 * Math.PI * r
  const k = u === null || u === undefined ? 0 : Math.min(1, Math.max(0, u))
  const color = u === null || u === undefined ? '#6b7a90' : u >= 1 ? '#ff4d6d' : u >= 0.8 ? '#ffb547' : '#5ef0ff'
  return { r, len, dash: len * k, color }
})
</script>

<template>
  <ScreenPanel
    title="今日客流"
    :sub="single ? single.shortName : '全部景区'"
    :mode="ctx.overview.value?.meta.dataMode"
    :loading="!ctx.overview.value && !ctx.overviewError.value"
    :error="ctx.overview.value ? null : ctx.overviewError.value"
    :empty="!kpi"
  >
    <div v-if="kpi" class="kpi">
      <div class="ring">
        <svg viewBox="0 0 128 128">
          <circle cx="64" cy="64" :r="ring.r" class="track" />
          <circle cx="64" cy="64" :r="ring.r" class="val" :stroke="ring.color" :stroke-dasharray="`${ring.dash} ${ring.len}`" />
        </svg>
        <div class="ring-text">
          <b :style="{ color: ring.color }">{{ pct(kpi.utilization) }}</b>
          <span>承载率</span>
        </div>
      </div>
      <div class="nums">
        <div class="n main">
          <label>入园人次</label>
          <b><CountUp :value="kpi.entries" /></b>
        </div>
        <div class="n">
          <label>当前在园</label>
          <b class="cy"><CountUp :value="kpi.inPark" /></b>
          <small>/ 承载 {{ kpi.capacity.toLocaleString('zh-CN') }}</small>
        </div>
        <div v-if="single" class="pair">
          <span>同比 <em :class="{ up: (kpi.yoy ?? 0) > 0, down: (kpi.yoy ?? 0) < 0 }">{{ kpi.yoy === null ? '不可计算' : signedPct(kpi.yoy) }}</em></span>
          <span>在园峰值 <em>{{ kpi.peak === null ? '—' : kpi.peak.toLocaleString('zh-CN') }}</em></span>
          <span v-if="kpi.level" :style="{ color: levelColor(kpi.level === 'NORMAL' ? 'NORMAL' : kpi.level) }">{{ LEVEL_LABELS[kpi.level] }}</span>
        </div>
      </div>
    </div>
    <div v-if="kpi && !single" class="sites">
      <div v-for="s in rows" :key="s.siteId" class="site" @click="ctx.selectedSiteId.value = s.siteId">
        <span class="name">{{ s.shortName }}</span>
        <span class="v">入园 <b>{{ s.today.entries === null ? '—' : s.today.entries.toLocaleString('zh-CN') }}</b></span>
        <span class="v">在园 <b>{{ s.today.inParkCurrent === null ? '—' : s.today.inParkCurrent.toLocaleString('zh-CN') }}</b></span>
        <span class="u" :style="{ color: levelColor(s.today.loadLevel) }">{{ pct(s.today.utilization) }}</span>
      </div>
    </div>
  </ScreenPanel>
</template>

<style scoped>
.kpi { display: flex; align-items: center; gap: 18px; }
.ring { position: relative; width: 128px; height: 128px; flex: none; }
.ring svg { width: 100%; height: 100%; transform: rotate(-90deg); }
.ring circle { fill: none; stroke-width: 9; }
.ring .track { stroke: rgba(94, 200, 242, 0.14); }
.ring .val { stroke-linecap: round; transition: stroke-dasharray 0.9s ease; filter: drop-shadow(0 0 6px currentColor); }
.ring-text { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.ring-text b { font-size: 26px; font-variant-numeric: tabular-nums; }
.ring-text span { font-size: 12px; color: #7fa6c9; }
.nums { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.n label { display: block; font-size: 12px; color: #7fa6c9; }
.n b { font-size: 26px; color: #e6f6ff; font-variant-numeric: tabular-nums; letter-spacing: 1px; }
.n.main b { font-size: 38px; background: linear-gradient(#ffffff, #7fe3ff); -webkit-background-clip: text; background-clip: text; color: transparent; }
.n b.cy { color: #5ef0ff; }
.n small { margin-left: 6px; font-size: 12px; color: #7fa6c9; }
.pair { display: flex; gap: 12px; font-size: 12px; color: #7fa6c9; flex-wrap: wrap; }
.pair em { font-style: normal; color: #d8f1ff; font-variant-numeric: tabular-nums; }
.pair em.up { color: #ff8a9b; }
.pair em.down { color: #36e3a0; }
.sites { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.site { display: flex; align-items: center; gap: 10px; padding: 4px 8px; font-size: 13px; color: #9fc3e0; background: rgba(46, 197, 255, 0.06);
  border-left: 2px solid rgba(94, 240, 255, 0.5); cursor: pointer; }
.site:hover { background: rgba(46, 197, 255, 0.14); }
.site .name { width: 92px; color: #e6f6ff; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.site .v { flex: 1; white-space: nowrap; }
.site b { color: #d8f1ff; font-variant-numeric: tabular-nums; }
.site .u { width: 46px; text-align: right; font-weight: 700; font-variant-numeric: tabular-nums; }
@media (prefers-reduced-motion: reduce) { .ring .val { transition: none; } }
</style>
