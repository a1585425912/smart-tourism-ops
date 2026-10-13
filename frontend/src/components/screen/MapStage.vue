<script setup lang="ts">
import { computed, defineAsyncComponent, ref, shallowRef, watch } from 'vue'
import { screenApi, type QueueStatus, type ZoneLoad } from '@/api/screen'
import type { Device, Zone } from '@/api/types'
import { errorText } from '@/api/http'
import { useScreen } from './screenContext'
import { bboxOf, levelColor, LEVEL_COLORS, LEVEL_LABELS, prefersReducedMotion, zoneExtrusionHeight, type Level } from './screenUtils'
import type { MapScene } from './mapScene'
import type { GeoCollection } from './geoData'

const Map3D = defineAsyncComponent(() => import('./Map3D.vue'))
const Map2D = defineAsyncComponent(() => import('./Map2D.vue'))

const ctx = useScreen()
const mode = ref<'3D' | '2D'>('3D')
const orbit = ref(!prefersReducedMotion())
const failure3d = ref<string | null>(null)

const zonesBySite = shallowRef(new Map<number, Zone[]>())
const loadsBySite = shallowRef(new Map<number, ZoneLoad[]>())
const cameras = shallowRef<Device[]>([])
const queues = shallowRef<QueueStatus[]>([])
const loadError = ref<string | null>(null)

async function loadZones(ids: number[]) {
  const next = new Map(zonesBySite.value)
  await Promise.all(
    ids.filter((id) => !next.has(id)).map(async (id) => {
      try {
        next.set(id, await screenApi.zones(id))
      } catch (e) {
        loadError.value = errorText(e)
      }
    }),
  )
  zonesBySite.value = next
}

async function loadDynamic() {
  const ids = ctx.siteIds.value
  if (!ids.length) return
  loadError.value = null
  await loadZones(ids)
  const loads = new Map<number, ZoneLoad[]>()
  const qs: QueueStatus[] = []
  await Promise.all(
    ids.map(async (id) => {
      const [l, q] = await Promise.allSettled([screenApi.zoneLoads(id), screenApi.queues(id)])
      if (l.status === 'fulfilled') loads.set(id, l.value)
      if (q.status === 'fulfilled') qs.push(...q.value)
    }),
  )
  loadsBySite.value = loads
  queues.value = qs
  try {
    cameras.value = await screenApi.keyCameras(ctx.selectedSiteId.value ?? undefined)
  } catch (e) {
    loadError.value = errorText(e)
  }
}

watch([ctx.siteIds, ctx.tick], loadDynamic, { immediate: true })

const scene = computed<MapScene>(() => {
  const ids = ctx.siteIds.value
  const sites = ctx.sites.value.filter((s) => ids.includes(s.id))
  const features: GeoCollection['features'] = []
  for (const id of ids) {
    const loads = new Map((loadsBySite.value.get(id) ?? []).map((l) => [l.zoneId, l]))
    for (const z of zonesBySite.value.get(id) ?? []) {
      const l = loads.get(z.id)
      const level = (l?.level ?? 'UNKNOWN') as Level
      features.push({
        type: 'Feature',
        geometry: z.boundary,
        properties: {
          zoneId: z.id,
          siteId: id,
          name: z.name,
          level,
          ratio: l?.ratio ?? null,
          count: l?.count ?? null,
          capacity: l?.capacity ?? z.capacity,
          color: levelColor(level),
          height: zoneExtrusionHeight(l?.ratio),
        },
      })
    }
  }
  return {
    sites,
    zones: { type: 'FeatureCollection', features },
    cameras: cameras.value.filter((c) => ids.includes(c.siteId)),
    queues: queues.value.filter((q) => ids.includes(q.siteId)),
    focus: bboxOf(sites.map((s) => s.boundary ?? s.center)),
    showZoneLabels: ids.length === 1,
  }
})

function on3dFail(msg: string) {
  failure3d.value = msg
  mode.value = '2D'
}

const legend = (['LOW', 'NORMAL', 'WARN', 'FULL', 'UNKNOWN'] as Level[]).map((l) => ({ l, c: LEVEL_COLORS[l], t: LEVEL_LABELS[l] }))
</script>

<template>
  <div class="stage">
    <Map3D v-if="mode === '3D'" :scene="scene" :orbit="orbit" @focus-device="(id: number) => (ctx.focusDeviceId.value = id)" @fail="on3dFail" />
    <Map2D v-else :scene="scene" @focus-device="(id: number) => (ctx.focusDeviceId.value = id)" />

    <div class="frame" />
    <div class="toolbar">
      <div class="seg">
        <button type="button" :class="{ on: mode === '3D' }" :disabled="!!failure3d" @click="mode = '3D'">3D</button>
        <button type="button" :class="{ on: mode === '2D' }" @click="mode = '2D'">2D</button>
      </div>
      <button v-if="mode === '3D'" type="button" class="orbit" :class="{ on: orbit }" @click="orbit = !orbit">
        {{ orbit ? '环绕中' : '环绕' }}
      </button>
    </div>

    <div class="legend">
      <div class="legend-title">区域承载</div>
      <div v-for="i in legend" :key="i.l" class="legend-item"><i :style="{ background: i.c }" />{{ i.t }}</div>
      <div class="legend-item"><i class="cam" />重点点位</div>
      <div class="legend-item"><i class="q" />排队点</div>
    </div>

    <div class="notes">
      <div v-if="mode === '3D'">三维示意：基于 OSM 建筑轮廓生成，非实景数字孪生</div>
      <div v-if="failure3d" class="warn">三维渲染不可用（{{ failure3d }}），已切换二维</div>
      <div v-if="loadError" class="warn">部分图层数据加载失败：{{ loadError }}</div>
      <div class="attr">© OpenStreetMap contributors (ODbL)</div>
    </div>
  </div>
</template>

<style scoped>
.stage {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: 6px;
  background: radial-gradient(ellipse at 50% 40%, #0b2340 0%, #050d1c 70%);
}
.frame {
  pointer-events: none;
  position: absolute;
  inset: 0;
  border: 1px solid rgba(94, 200, 242, 0.25);
  border-radius: 6px;
  box-shadow: inset 0 0 60px rgba(5, 13, 28, 0.85);
}
.toolbar {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  gap: 8px;
  z-index: 3;
}
.seg {
  display: flex;
  border: 1px solid rgba(94, 200, 242, 0.45);
  border-radius: 4px;
  overflow: hidden;
}
.toolbar button {
  background: rgba(8, 24, 48, 0.8);
  color: #9fc3e0;
  border: none;
  padding: 4px 14px;
  font-size: 13px;
  cursor: pointer;
}
.seg button + button {
  border-left: 1px solid rgba(94, 200, 242, 0.3);
}
.toolbar button.on {
  background: rgba(46, 197, 255, 0.3);
  color: #e8fbff;
}
.toolbar button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.orbit {
  border: 1px solid rgba(94, 200, 242, 0.45) !important;
  border-radius: 4px;
}
.legend {
  position: absolute;
  left: 12px;
  top: 52px;
  z-index: 3;
  padding: 8px 10px;
  background: rgba(5, 16, 34, 0.72);
  border: 1px solid rgba(94, 200, 242, 0.2);
  border-radius: 4px;
  font-size: 12px;
  color: #a9cbe6;
}
.legend-title {
  color: #e6f6ff;
  margin-bottom: 4px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  line-height: 20px;
}
.legend-item i {
  width: 12px;
  height: 8px;
  border-radius: 2px;
}
.legend-item i.cam {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #5ef0ff;
  box-shadow: 0 0 6px #5ef0ff;
}
.legend-item i.q {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  background: #b48cff;
}
.notes {
  position: absolute;
  left: 12px;
  bottom: 44px;
  z-index: 3;
  font-size: 12px;
  color: #6f8fb0;
  line-height: 18px;
}
.notes .warn {
  color: #ffb547;
}
.notes .attr {
  color: #8aa7c4;
}
</style>
