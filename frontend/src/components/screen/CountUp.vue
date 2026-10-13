<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { easeOutCubic, prefersReducedMotion } from './screenUtils'

/** 数字滚动; value 为 null 时显示占位符, 不显示 0。 */
const props = withDefaults(
  defineProps<{ value: number | null | undefined; digits?: number; duration?: number; compact?: boolean }>(),
  { digits: 0, duration: 900, compact: false },
)

const shown = ref<number | null>(null)
let raf = 0

function format(v: number | null): string {
  if (v === null || !Number.isFinite(v)) return '—'
  if (props.compact && Math.abs(v) >= 1e4) return (v / 1e4).toFixed(1) + '万'
  return v.toLocaleString('zh-CN', { minimumFractionDigits: props.digits, maximumFractionDigits: props.digits })
}

watch(
  () => props.value,
  (to) => {
    cancelAnimationFrame(raf)
    if (to === null || to === undefined || !Number.isFinite(to)) {
      shown.value = null
      return
    }
    const from = shown.value ?? 0
    if (prefersReducedMotion() || from === to) {
      shown.value = to
      return
    }
    const t0 = performance.now()
    const step = (now: number) => {
      const k = easeOutCubic((now - t0) / props.duration)
      shown.value = from + (to - from) * k
      if (k < 1) raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
  },
  { immediate: true },
)

onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<template>
  <span class="count-up">{{ format(shown) }}</span>
</template>

<style scoped>
.count-up {
  font-variant-numeric: tabular-nums;
}
</style>
