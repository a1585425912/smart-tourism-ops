<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts'
import type { EChartsOption } from 'echarts'

/** ECharts 包装: 自适应容器尺寸, option 变化时整体替换。 */
const props = defineProps<{ option: EChartsOption | null; height?: string }>()
const emit = defineEmits<{ click: [params: unknown] }>()

const el = ref<HTMLDivElement>()
const chart = shallowRef<echarts.ECharts>()
let observer: ResizeObserver | null = null

function render() {
  if (!chart.value || !props.option) return
  chart.value.setOption(props.option, { notMerge: true })
}

onMounted(() => {
  if (!el.value) return
  chart.value = echarts.init(el.value, undefined, { renderer: 'canvas' })
  chart.value.on('click', (p) => emit('click', p))
  render()
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(el.value)
})

watch(() => props.option, render, { deep: false })

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
})
</script>

<template>
  <div ref="el" class="echart" :style="{ height: height ?? '280px' }" />
</template>

<style scoped>
.echart { width: 100%; min-width: 0; }
</style>
