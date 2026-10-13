<script setup lang="ts">
import { computed } from 'vue'
import dayjs from 'dayjs'
import { useScreen } from './screenContext'

/** 告警滚动条: 来自 /overview alerts, 按级别着色。 */
const ctx = useScreen()
const alerts = computed(() => (ctx.overview.value?.alerts ?? []).filter((a) => ctx.siteIds.value.includes(a.siteId)))
const duration = computed(() => Math.max(20, alerts.value.length * 9) + 's')
const critical = computed(() => alerts.value.filter((a) => a.level === 'CRITICAL').length)
</script>

<template>
  <div class="ticker">
    <span class="tag" :class="{ crit: critical > 0 }">告警 {{ ctx.overview.value ? alerts.length : '—' }}</span>
    <div class="view">
      <span v-if="ctx.overviewError.value && !ctx.overview.value" class="none err">告警数据加载失败</span>
      <span v-else-if="!alerts.length" class="none">{{ ctx.overview.value ? '当前无告警' : '加载中' }}</span>
      <div v-else class="track" :style="{ animationDuration: duration }">
        <span v-for="(a, i) in [...alerts, ...alerts]" :key="i" class="a" :class="a.level">
          <i />[{{ ctx.siteName(a.siteId) }}] {{ a.title }}<small>{{ a.detail }} · {{ dayjs(a.at).format('HH:mm') }}</small>
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ticker { display: flex; align-items: center; gap: 10px; height: 100%; padding: 0 10px; background: rgba(8, 22, 44, 0.75); border: 1px solid rgba(94, 200, 242, 0.22); border-radius: 4px; overflow: hidden; }
.tag { flex: none; font-size: 12px; padding: 1px 8px; border-radius: 3px; color: #04101f; background: #5ef0ff; font-weight: 700; font-variant-numeric: tabular-nums; }
.tag.crit { background: #ff4d6d; color: #fff; }
.view { flex: 1; overflow: hidden; white-space: nowrap; mask-image: linear-gradient(90deg, transparent, #000 4%, #000 96%, transparent); }
.track { display: inline-block; animation: tk linear infinite; }
.view:hover .track { animation-play-state: paused; }
@keyframes tk { to { transform: translateX(-50%); } }
.a { display: inline-flex; align-items: center; gap: 6px; margin-right: 48px; font-size: 13px; color: #c5e2f7; }
.a i { width: 7px; height: 7px; border-radius: 50%; background: #5ec8f2; }
.a.WARN { color: #ffcf7a; } .a.WARN i { background: #ffb547; }
.a.CRITICAL { color: #ff9aa9; } .a.CRITICAL i { background: #ff4d6d; box-shadow: 0 0 6px #ff4d6d; }
.a small { color: #7fa6c9; font-size: 12px; }
.none { font-size: 13px; color: #6f8fb0; }
.none.err { color: #ff8a9b; }
@media (prefers-reduced-motion: reduce) { .track { animation: none; } }
</style>
