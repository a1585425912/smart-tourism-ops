<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import { prefersReducedMotion } from './screenUtils'

/** ECharts 容器: 随父容器尺寸自适应, option 变化时增量更新。 */
const props = defineProps<{ option: echarts.EChartsOption }>()

const el = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()
let observer: ResizeObserver | null = null

function apply() {
  if (!chart.value) return
  chart.value.setOption({ animation: !prefersReducedMotion(), ...props.option }, { notMerge: true })
}

onMounted(() => {
  if (!el.value) return
  chart.value = echarts.init(el.value, undefined, { renderer: 'canvas' })
  apply()
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(el.value)
})

watch(() => props.option, apply, { deep: false })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
})
</script>

<template>
  <div ref="el" class="echart" />
</template>

<style scoped>
.echart {
  width: 100%;
  height: 100%;
}
</style>
