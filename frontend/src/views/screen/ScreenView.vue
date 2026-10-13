<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Device } from '@/api/types'
import { screenApi } from '@/api/screen'
import { errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import { createScreenContext } from '@/components/screen/screenContext'
import { useAutoRefresh } from '@/components/screen/usePanel'
import { DESIGN_H, DESIGN_W, fitScale } from '@/components/screen/screenUtils'
import ScreenHeader from '@/components/screen/ScreenHeader.vue'
import MapStage from '@/components/screen/MapStage.vue'
import FlowKpiPanel from '@/components/screen/FlowKpiPanel.vue'
import TrendPanel from '@/components/screen/TrendPanel.vue'
import ZoneRankPanel from '@/components/screen/ZoneRankPanel.vue'
import VideoWall from '@/components/screen/VideoWall.vue'
import VideoTile from '@/components/screen/VideoTile.vue'
import ReviewPanel from '@/components/screen/ReviewPanel.vue'
import QueuePanel from '@/components/screen/QueuePanel.vue'
import ForecastPanel from '@/components/screen/ForecastPanel.vue'
import MiniPanels from '@/components/screen/MiniPanels.vue'
import AlertTicker from '@/components/screen/AlertTicker.vue'

/**
 * 指挥大屏: 1920×1080 设计画布等比缩放。
 * 地图舞台不放在缩放画布内(WebGL 地图在 CSS transform 下指针坐标会偏移), 而是按缩放后的矩形单独定位。
 */
const app = useAppStore()
const ctx = createScreenContext(app.today())
const sitesError = ref<string | null>(null)

// 地图舞台在设计画布中的矩形
const STAGE = { x: 468, y: 84, w: 984, h: 716 }

const win = ref({ w: window.innerWidth, h: window.innerHeight })
const onResize = () => (win.value = { w: window.innerWidth, h: window.innerHeight })
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const fit = computed(() => fitScale(win.value.w, win.value.h))
const canvasStyle = computed(() => ({
  width: DESIGN_W + 'px',
  height: DESIGN_H + 'px',
  transform: `translate(${fit.value.offsetX}px, ${fit.value.offsetY}px) scale(${fit.value.scale})`,
}))
const stageStyle = computed(() => {
  const { scale, offsetX, offsetY } = fit.value
  return {
    left: offsetX + STAGE.x * scale + 'px',
    top: offsetY + STAGE.y * scale + 'px',
    width: STAGE.w * scale + 'px',
    height: STAGE.h * scale + 'px',
  }
})

async function loadSites() {
  try {
    ctx.sites.value = await screenApi.sites()
    sitesError.value = null
    if (ctx.selectedSiteId.value !== null && !ctx.sites.value.some((s) => s.id === ctx.selectedSiteId.value)) {
      ctx.selectedSiteId.value = null
    }
  } catch (e) {
    sitesError.value = errorText(e)
  }
}

async function refresh() {
  ctx.today.value = app.today()
  if (!ctx.sites.value.length) await loadSites()
  try {
    ctx.overview.value = await screenApi.overview()
    ctx.overviewError.value = null
  } catch (e) {
    ctx.overviewError.value = e
  }
  ctx.tick.value++
}
useAutoRefresh(refresh, 30000)

const focusDevice = ref<Device | null>(null)
watch(ctx.focusDeviceId, async (id) => {
  if (id === null) {
    focusDevice.value = null
    return
  }
  try {
    const all = await screenApi.keyCameras(ctx.selectedSiteId.value ?? undefined)
    focusDevice.value = all.find((d) => d.id === id) ?? null
  } catch {
    focusDevice.value = null
  }
  if (!focusDevice.value) ctx.focusDeviceId.value = null
})
const closeFocus = () => (ctx.focusDeviceId.value = null)
</script>

<template>
  <div class="screen-root" @keydown.esc="closeFocus">
    <div class="canvas" :style="canvasStyle">
      <div class="aurora" />
      <div class="hd"><ScreenHeader /></div>

      <div class="col left">
        <FlowKpiPanel class="p-kpi" />
        <TrendPanel class="p-trend" />
        <ZoneRankPanel class="p-rank" />
      </div>

      <div class="col right">
        <VideoWall class="p-video" />
        <ReviewPanel class="p-review" />
        <QueuePanel class="p-queue" />
      </div>

      <div class="bottom">
        <ForecastPanel class="p-forecast" />
        <MiniPanels />
      </div>
      <div class="ticker"><AlertTicker /></div>

      <div v-if="sitesError" class="fatal">景区列表加载失败：{{ sitesError }} <button type="button" @click="refresh">重试</button></div>
    </div>

    <div class="stage" :style="stageStyle">
      <MapStage v-if="ctx.sites.value.length" />
      <div v-else class="stage-empty">{{ sitesError ? '地图不可用' : '正在加载景区…' }}</div>
    </div>

    <!-- 放大播放层位于缩放画布之外, 盖在地图之上 -->
    <div v-if="focusDevice" class="focus" @click.self="closeFocus">
      <div class="focus-box">
        <VideoTile :key="focusDevice.id" :device="focusDevice" large />
        <button type="button" class="close" aria-label="关闭" @click="closeFocus">×</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.screen-root { position: fixed; inset: 0; overflow: hidden; background: #02060f; color: #d8f1ff;
  font-family: 'PingFang SC', 'Microsoft YaHei', 'Noto Sans SC', system-ui, sans-serif; }
.canvas { position: absolute; left: 0; top: 0; transform-origin: 0 0;
  background: radial-gradient(ellipse at 50% -10%, #12345e 0%, #071428 45%, #030915 100%); }
.aurora { position: absolute; inset: 0; pointer-events: none; opacity: 0.55;
  background:
    radial-gradient(ellipse 700px 220px at 18% 0%, rgba(54, 227, 160, 0.16), transparent 70%),
    radial-gradient(ellipse 800px 240px at 82% 0%, rgba(120, 110, 255, 0.18), transparent 70%),
    radial-gradient(ellipse 900px 300px at 50% 100%, rgba(46, 155, 255, 0.12), transparent 70%); }
.hd { position: absolute; left: 0; top: 0; width: 1920px; height: 76px; }
.col { position: absolute; top: 84px; height: 980px; display: grid; gap: 12px; }
.col.left { left: 16px; width: 440px; grid-template-rows: 250px 1fr 310px; }
.col.right { left: 1464px; width: 440px; grid-template-rows: 380px 1fr 232px; }
.bottom { position: absolute; left: 468px; top: 812px; width: 984px; height: 214px; display: grid; grid-template-columns: 372px 1fr 1fr 1fr; gap: 8px; }
.ticker { position: absolute; left: 468px; top: 1034px; width: 984px; height: 30px; }
.fatal { position: absolute; left: 50%; top: 90px; transform: translateX(-50%); z-index: 20; padding: 6px 16px; font-size: 14px; color: #ffb0bb;
  background: rgba(60, 10, 20, 0.9); border: 1px solid #ff4d6d; border-radius: 4px; }
.fatal button { margin-left: 8px; background: transparent; color: #fff; border: 1px solid #ff8a9b; border-radius: 3px; cursor: pointer; }
.stage { position: absolute; z-index: 1; }
.stage-empty { height: 100%; display: flex; align-items: center; justify-content: center; color: #6f8fb0; border: 1px solid rgba(94, 200, 242, 0.25); border-radius: 6px; }
.focus { position: absolute; inset: 0; z-index: 30; background: rgba(2, 6, 15, 0.72); display: flex; align-items: center; justify-content: center; }
.focus-box { position: relative; width: min(70vw, 124vh); aspect-ratio: 16 / 9; box-shadow: 0 0 60px rgba(94, 240, 255, 0.35); }
.close { position: absolute; right: -16px; top: -16px; width: 34px; height: 34px; border-radius: 50%; font-size: 22px; line-height: 28px;
  background: #08182f; color: #e6f6ff; border: 1px solid rgba(94, 240, 255, 0.7); cursor: pointer; }
</style>
