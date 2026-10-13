<script setup lang="ts">
/** 指标卡: value 由调用方格式化(缺失值传 "—"), 可带副标题与变化趋势。 */
defineProps<{
  label: string
  value: string
  unit?: string
  sub?: string
  trend?: { text: string; dir: 'up' | 'down' | 'flat' | 'na' } | null
  trendLabel?: string
  accent?: string
}>()
</script>

<template>
  <div class="kpi" :style="accent ? { borderTopColor: accent } : undefined">
    <div class="label">{{ label }}</div>
    <div class="value num">{{ value }}<span v-if="unit && value !== '—'" class="unit">{{ unit }}</span></div>
    <div class="foot">
      <span v-if="trend" class="trend" :class="trend.dir">{{ trendLabel }} {{ trend.text }}</span>
      <span v-if="sub" class="sub">{{ sub }}</span>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.kpi { background: var(--sw-panel); border: 1px solid var(--sw-border); border-top: 3px solid var(--sw-primary); border-radius: 8px; padding: 14px 16px; min-width: 0; }
.label { font-size: 13px; color: var(--sw-text-3); }
.value { font-size: 28px; font-weight: 600; line-height: 1.3; margin-top: 4px; color: var(--sw-text-1); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.unit { font-size: 13px; font-weight: 400; color: var(--sw-text-3); margin-left: 4px; }
.foot { min-height: 18px; display: flex; flex-wrap: wrap; gap: 8px; font-size: 12px; color: var(--sw-text-3); margin-top: 4px; }
.trend.up { color: var(--sw-danger); }
.trend.down { color: var(--sw-ok); }
.trend.na { color: var(--sw-text-3); }
</style>
