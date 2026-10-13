<script setup lang="ts">
import { computed, watch } from 'vue'
import { screenApi } from '@/api/screen'
import ScreenPanel from './ScreenPanel.vue'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'
import { perSite, waitColor, waitOf } from './screenData'
import { mergeMode } from './screenUtils'

/** 排队时长 TOP: 实测值与估算值分别标注。 */
const ctx = useScreen()
const { data, loading, error, reload } = usePanel(() => perSite(ctx.siteIds.value, (id) => screenApi.queues(id)), [ctx.siteIds])
watch(ctx.tick, reload, { immediate: true })

const multi = computed(() => ctx.siteIds.value.length > 1)
const rows = computed(() =>
  (data.value ?? [])
    .flatMap((r) => r.data)
    .map((q) => ({ q, w: waitOf(q) }))
    .sort((a, b) => (b.w.value ?? -1) - (a.w.value ?? -1))
    .slice(0, 6),
)
const mode = computed(() => mergeMode(rows.value.map((r) => r.q.meta)))
</script>

<template>
  <ScreenPanel title="排队时长" sub="TOP 6 · 分钟" :mode="mode" :loading="loading" :error="data ? null : error" :empty="!rows.length" empty-text="暂无排队数据" @retry="reload">
    <ul class="q">
      <li v-for="{ q, w } in rows" :key="q.pointId" :title="q.method">
        <span class="name"><template v-if="multi"><i>{{ ctx.siteName(q.siteId) }}</i> · </template>{{ q.name }}</span>
        <span class="len">{{ q.queueLength === null ? '' : `排队 ${q.queueLength} 人` }}</span>
        <span v-if="w.kind" class="kind" :class="{ m: w.kind === '实测' }">{{ w.kind }}</span>
        <b :style="{ color: waitColor(w.value) }">{{ w.value === null ? '—' : Math.round(w.value) }}</b>
      </li>
    </ul>
  </ScreenPanel>
</template>

<style scoped>
.q { list-style: none; margin: 0; padding: 0; height: 100%; display: flex; flex-direction: column; justify-content: space-between; }
li { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #c5e2f7; padding: 0 6px; height: 26px; background: rgba(46, 197, 255, 0.05); border-left: 2px solid rgba(180, 140, 255, 0.7); }
.name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.name i { font-style: normal; color: #7fa6c9; }
.len { font-size: 12px; color: #7fa6c9; white-space: nowrap; font-variant-numeric: tabular-nums; }
.kind { font-size: 10px; padding: 0 4px; line-height: 15px; border-radius: 2px; color: #9fc3e0; border: 1px solid rgba(127, 166, 201, 0.5); }
.kind.m { color: #36e3a0; border-color: rgba(54, 227, 160, 0.6); }
b { width: 36px; text-align: right; font-size: 18px; font-variant-numeric: tabular-nums; }
</style>
