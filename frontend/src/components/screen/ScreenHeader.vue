<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { screenApi } from '@/api/screen'
import { useAppStore } from '@/stores/app'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'
import { weatherText } from './screenUtils'

const app = useAppStore()
const ctx = useScreen()

const clock = ref(app.now())
let timer: ReturnType<typeof setInterval>
onMounted(() => (timer = setInterval(() => (clock.value = app.now()), 1000)))
onBeforeUnmount(() => clearInterval(timer))

const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const cal = usePanel(() => screenApi.calendar(ctx.range.value.date, ctx.range.value.tomorrow))
watch(ctx.today, cal.reload, { immediate: true })
const day = computed(() => cal.data.value?.[0] ?? null)

const mode = computed(() => ctx.overview.value?.meta.dataMode ?? (app.system?.demoData ? 'DEMO' : null))
</script>

<template>
  <header class="hd">
    <div class="left">
      <select :value="ctx.selectedSiteId.value ?? ''" class="site" aria-label="选择景区"
        @change="ctx.selectedSiteId.value = ($event.target as HTMLSelectElement).value === '' ? null : Number(($event.target as HTMLSelectElement).value)">
        <option value="">全部景区</option>
        <option v-for="s in ctx.sites.value" :key="s.id" :value="s.id">{{ s.name }}</option>
      </select>
      <div class="wx">
        <template v-if="day?.weather">
          <b>{{ weatherText(day.weather.weatherCode) }}</b>
          <span>{{ day.weather.tempMin ?? '—' }} ~ {{ day.weather.tempMax ?? '—' }}℃</span>
          <span v-if="day.weather.snowfall">降雪 {{ day.weather.snowfall }}cm</span>
          <span v-if="day.weather.isForecast" class="dim">预报</span>
        </template>
        <span v-else class="dim">{{ cal.error.value ? '天气加载失败' : '天气 —' }}</span>
        <span v-if="day?.holiday" class="holiday">{{ day.holiday }}</span>
      </div>
    </div>
    <div class="title">
      <h1>多业态融合智慧文旅管控系统</h1>
      <p>指 挥 中 心</p>
    </div>
    <div class="right">
      <span v-if="mode && mode !== 'REAL'" class="chip demo">{{ mode === 'MIXED' ? '含演示数据' : '演示数据' }}</span>
      <span v-if="app.system?.replay" class="chip replay">演示回放</span>
      <div class="clock">
        <b>{{ clock.format('HH:mm:ss') }}</b>
        <span>{{ clock.format('YYYY-MM-DD') }} 星期{{ WEEK[clock.day()] }}</span>
      </div>
      <router-link to="/admin/overview" class="back">返回后台</router-link>
    </div>
  </header>
</template>

<style scoped>
.hd { position: relative; height: 100%; display: flex; align-items: center; justify-content: space-between; padding: 0 20px;
  background: linear-gradient(180deg, rgba(20, 60, 110, 0.55), rgba(5, 13, 28, 0)); }
.hd::after { content: ''; position: absolute; left: 0; right: 0; bottom: 6px; height: 1px;
  background: linear-gradient(90deg, transparent, rgba(94, 240, 255, 0.7) 30%, rgba(94, 240, 255, 0.7) 70%, transparent); }
.left, .right { width: 600px; display: flex; align-items: center; gap: 14px; }
.right { justify-content: flex-end; }
.title { text-align: center; }
h1 { margin: 0; font-size: 32px; letter-spacing: 6px; font-weight: 700;
  background: linear-gradient(180deg, #ffffff 20%, #7fe3ff 90%); -webkit-background-clip: text; background-clip: text; color: transparent;
  filter: drop-shadow(0 0 12px rgba(94, 240, 255, 0.45)); }
.title p { margin: 0; font-size: 13px; letter-spacing: 8px; color: #7fd4f5; }
.site { height: 32px; min-width: 190px; padding: 0 10px; font-size: 15px; color: #e6f6ff; background: rgba(8, 24, 48, 0.85);
  border: 1px solid rgba(94, 200, 242, 0.5); border-radius: 4px; outline: none; cursor: pointer; }
.site option { background: #08182f; }
.wx { display: flex; align-items: center; gap: 8px; font-size: 14px; color: #a9cbe6; white-space: nowrap; }
.wx b { color: #e6f6ff; }
.dim { color: #6f8fb0; font-size: 12px; }
.holiday { color: #ff9aa9; border: 1px solid rgba(255, 77, 109, 0.5); border-radius: 3px; padding: 0 6px; font-size: 12px; }
.chip { font-size: 12px; padding: 2px 8px; border-radius: 3px; white-space: nowrap; }
.chip.demo { color: #ffcf7a; border: 1px solid rgba(255, 181, 71, 0.6); background: rgba(255, 181, 71, 0.12); }
.chip.replay { color: #04101f; background: #ffb547; font-weight: 700; }
.clock { text-align: right; line-height: 1.15; }
.clock b { display: block; font-size: 26px; color: #e6f6ff; font-variant-numeric: tabular-nums; letter-spacing: 2px; }
.clock span { font-size: 12px; color: #7fa6c9; }
.back { font-size: 13px; color: #9fdcff; text-decoration: none; border: 1px solid rgba(94, 200, 242, 0.45); border-radius: 4px; padding: 4px 10px; white-space: nowrap; }
.back:hover { background: rgba(46, 197, 255, 0.2); }
</style>
