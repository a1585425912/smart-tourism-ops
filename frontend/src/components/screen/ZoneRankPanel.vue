<script setup lang="ts">
import { computed, watch } from 'vue'
import { screenApi, type ZoneLoad } from '@/api/screen'
import ScreenPanel from './ScreenPanel.vue'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'
import { perSite, pct } from './screenData'
import { levelColor, LEVEL_LABELS, mergeMode, type Level } from './screenUtils'

/** 区域承载排行: 承载率 = 区域人数 / 区域容量(后端计算)。 */
const ctx = useScreen()
const { data, loading, error, reload } = usePanel(() => perSite(ctx.siteIds.value, (id) => screenApi.zoneLoads(id)), [ctx.siteIds])
watch(ctx.tick, reload, { immediate: true })

const multi = computed(() => ctx.siteIds.value.length > 1)
const rows = computed(() => {
  const all: (ZoneLoad & { siteId: number })[] = (data.value ?? []).flatMap((r) => r.data.map((z) => ({ ...z, siteId: r.siteId })))
  // 无承载率的区域排在最后, 不当作 0
  return all.sort((a, b) => (b.ratio ?? -1) - (a.ratio ?? -1)).slice(0, 8)
})
const mode = computed(() => mergeMode(rows.value.map((r) => r.meta)))
</script>

<template>
  <ScreenPanel title="区域承载排行" sub="TOP 8" :mode="mode" :loading="loading" :error="data ? null : error" :empty="!rows.length" empty-text="暂无区域人数数据" @retry="reload">
    <ul class="rank">
      <li v-for="(z, i) in rows" :key="z.zoneId">
        <span class="idx" :class="{ top: i < 3 }">{{ i + 1 }}</span>
        <span class="name" :title="z.countSource ?? ''">
          <template v-if="multi"><i>{{ ctx.siteName(z.siteId) }}</i> · </template>{{ z.zoneName }}
        </span>
        <span class="bar"><b :style="{ width: Math.min(100, (z.ratio ?? 0) * 100) + '%', background: levelColor(z.level), boxShadow: `0 0 8px ${levelColor(z.level)}` }" /></span>
        <span class="cnt">{{ z.count === null ? '—' : z.count.toLocaleString('zh-CN') }}</span>
        <span class="r" :style="{ color: levelColor(z.level) }">{{ z.ratio === null ? LEVEL_LABELS[z.level as Level] : pct(z.ratio) }}</span>
      </li>
    </ul>
  </ScreenPanel>
</template>

<style scoped>
.rank { list-style: none; margin: 0; padding: 0; height: 100%; display: flex; flex-direction: column; justify-content: space-between; }
li { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #c5e2f7; }
.idx { width: 18px; height: 18px; line-height: 18px; text-align: center; font-size: 12px; border-radius: 3px; background: rgba(94, 200, 242, 0.14); color: #9fc3e0; }
.idx.top { background: linear-gradient(135deg, #2e9bff, #5ef0ff); color: #04101f; font-weight: 700; }
.name { width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.name i { font-style: normal; color: #7fa6c9; }
.bar { flex: 1; height: 8px; background: rgba(94, 200, 242, 0.1); border-radius: 4px; overflow: hidden; }
.bar b { display: block; height: 100%; border-radius: 4px; transition: width 0.8s ease; }
.cnt { width: 52px; text-align: right; font-variant-numeric: tabular-nums; color: #9fc3e0; }
.r { width: 46px; text-align: right; font-weight: 700; font-variant-numeric: tabular-nums; }
@media (prefers-reduced-motion: reduce) { .bar b { transition: none; } }
</style>
