<script setup lang="ts">
import { computed } from 'vue'
import type { DataMeta } from '@/api/types'
import { fmtTime } from '@/utils/format'

/** 数据来源条: 显示数据模式、质量与截止时间。演示数据必须可见地标注。 */
const props = defineProps<{ meta?: DataMeta | null; compact?: boolean }>()

const modeLabel = computed(() => {
  switch (props.meta?.dataMode) {
    case 'REAL': return '真实接入'
    case 'MIXED': return '含演示数据'
    default: return '演示数据'
  }
})

const qualityLabel = computed(() => {
  switch (props.meta?.quality) {
    case 'GOOD': return null
    case 'STALE': return '数据已过期'
    case 'PARTIAL': return '数据有缺口'
    case 'MISSING': return '暂无数据'
    default: return null
  }
})
</script>

<template>
  <span v-if="meta" class="data-meta" :class="{ compact }">
    <span class="tag" :class="meta.dataMode === 'REAL' ? 'real' : 'demo'">{{ modeLabel }}</span>
    <span v-if="qualityLabel" class="tag warn">{{ qualityLabel }}</span>
    <span v-if="!compact" class="asof">截至 {{ meta.dataAsOf ? fmtTime(meta.dataAsOf) : '—' }}</span>
    <span v-if="!compact && meta.sourceIds.length" class="src" :title="meta.sourceIds.join(', ')">来源 {{ meta.sourceIds.length }} 个</span>
  </span>
</template>

<style scoped>
.data-meta { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--sw-text-3); flex-wrap: wrap; }
.tag { padding: 1px 6px; border-radius: 3px; line-height: 18px; white-space: nowrap; }
.tag.demo { background: rgba(230, 162, 60, 0.14); color: #c27c0e; border: 1px solid rgba(230, 162, 60, 0.4); }
.tag.real { background: rgba(103, 194, 58, 0.12); color: #4e9a2a; border: 1px solid rgba(103, 194, 58, 0.4); }
.tag.warn { background: rgba(245, 108, 108, 0.12); color: #d03050; border: 1px solid rgba(245, 108, 108, 0.4); }
.src { cursor: help; }
</style>
