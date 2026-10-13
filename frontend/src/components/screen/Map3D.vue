<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Map as MlMap, Marker, setWorkerUrl, type GeoJSONSource, type StyleSpecification } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?url'
import 'maplibre-gl/dist/maplibre-gl.css'
import { loadBaseLayers, type GeoCollection } from './geoData'
import { ringCentroid, type MapScene } from './mapScene'
import { HEIGHT_COLOR_STOPS, LEVEL_LABELS, type Level } from './screenUtils'

setWorkerUrl(workerUrl)

const props = defineProps<{ scene: MapScene; orbit: boolean }>()
const emit = defineEmits<{ focusDevice: [id: number]; fail: [message: string] }>()

const el = ref<HTMLDivElement>()
let map: MlMap | null = null
let ready = false
let markers: Marker[] = []
let labels: HTMLElement[] = []
let labelFrame = 0
let raf = 0
let interacting = false
let lastFocusKey = ''
let focusedOnce = false

const EMPTY: GeoCollection = { type: 'FeatureCollection', features: [] }

/** 无在线瓦片的自定义样式: 只有深色背景, 其余图层在 load 后加入。 */
const style: StyleSpecification = {
  version: 8,
  sources: {},
  layers: [{ id: 'bg', type: 'background', paint: { 'background-color': '#050d1c' } }],
}

function siteCollection(): GeoCollection {
  return {
    type: 'FeatureCollection',
    features: props.scene.sites
      .filter((s) => s.boundary)
      .map((s) => ({ type: 'Feature' as const, geometry: s.boundary!, properties: { siteId: s.id, name: s.name } })),
  }
}

async function addBaseLayers(m: MlMap) {
  const base = await loadBaseLayers()
  m.addSource('water', { type: 'geojson', data: base.water as never })
  m.addSource('areas', { type: 'geojson', data: base.areas as never })
  m.addSource('roads', { type: 'geojson', data: base.roads as never })
  m.addSource('buildings', { type: 'geojson', data: base.buildings as never })
  m.addLayer({ id: 'areas-fill', type: 'fill', source: 'areas', paint: { 'fill-color': '#0d2b3a', 'fill-opacity': 0.55 } })
  m.addLayer({ id: 'water-fill', type: 'fill', source: 'water', filter: ['!=', ['geometry-type'], 'LineString'],
    paint: { 'fill-color': '#0a3557', 'fill-opacity': 0.9 } })
  m.addLayer({ id: 'water-line', type: 'line', source: 'water', paint: { 'line-color': '#1f78b4', 'line-width': 1, 'line-opacity': 0.7 } })
  m.addLayer({ id: 'roads-minor', type: 'line', source: 'roads', filter: ['all', ['==', ['get', '_major'], 0], ['==', ['get', '_rail'], 0]],
    paint: { 'line-color': '#173b5e', 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 0.4, 16, 2] } })
  m.addLayer({ id: 'roads-major', type: 'line', source: 'roads', filter: ['==', ['get', '_major'], 1],
    paint: { 'line-color': '#2c6fa6', 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 1, 16, 4], 'line-opacity': 0.9 } })
  m.addLayer({ id: 'rail', type: 'line', source: 'roads', filter: ['==', ['get', '_rail'], 1],
    paint: { 'line-color': '#4a6a8a', 'line-width': 1.2, 'line-dasharray': [2, 2] } })
  m.addLayer({
    id: 'buildings-3d', type: 'fill-extrusion', source: 'buildings',
    paint: {
      'fill-extrusion-color': ['interpolate', ['linear'], ['get', '_h'], ...HEIGHT_COLOR_STOPS.flat()] as never,
      'fill-extrusion-height': ['get', '_h'],
      'fill-extrusion-base': 0,
      'fill-extrusion-opacity': 0.82,
      'fill-extrusion-vertical-gradient': true,
    },
  })
}

function addDynamicLayers(m: MlMap) {
  m.addSource('sites', { type: 'geojson', data: siteCollection() as never })
  m.addSource('zones', { type: 'geojson', data: props.scene.zones as never })
  m.addLayer({ id: 'sites-fill', type: 'fill', source: 'sites', paint: { 'fill-color': '#2ec5ff', 'fill-opacity': 0.06 } })
  m.addLayer({ id: 'sites-glow', type: 'line', source: 'sites', paint: { 'line-color': '#5ef0ff', 'line-width': 10, 'line-blur': 8, 'line-opacity': 0.45 } })
  m.addLayer({ id: 'sites-line', type: 'line', source: 'sites', paint: { 'line-color': '#b8f8ff', 'line-width': 1.6 } })
  m.addLayer({
    id: 'zones-3d', type: 'fill-extrusion', source: 'zones',
    paint: {
      'fill-extrusion-color': ['get', 'color'],
      'fill-extrusion-height': ['get', 'height'],
      'fill-extrusion-base': 0,
      'fill-extrusion-opacity': 0.5,
    },
  })
  m.addLayer({ id: 'zones-line', type: 'line', source: 'zones', paint: { 'line-color': ['get', 'color'], 'line-width': 1.5, 'line-opacity': 0.9 } })
}

function syncDynamic() {
  if (!map || !ready) return
  ;(map.getSource('sites') as GeoJSONSource | undefined)?.setData(siteCollection() as never)
  ;(map.getSource('zones') as GeoJSONSource | undefined)?.setData((props.scene.zones ?? EMPTY) as never)
  renderMarkers()
  focus()
}

function renderMarkers() {
  if (!map) return
  markers.forEach((mk) => mk.remove())
  markers = []
  labels = []
  for (const cam of props.scene.cameras) {
    const node = document.createElement('div')
    node.className = 'sw-cam-marker' + (cam.status === 'ONLINE' ? '' : ' off')
    node.title = `${cam.name}（${cam.status === 'ONLINE' ? '在线' : '离线'}）点击查看画面`
    node.innerHTML = '<i></i><b></b>'
    node.addEventListener('click', (e) => {
      e.stopPropagation()
      emit('focusDevice', cam.id)
    })
    markers.push(new Marker({ element: node }).setLngLat([cam.lon, cam.lat]).addTo(map))
  }
  for (const q of [...props.scene.queues].sort((a, b) =>
    (b.measuredWaitMin ?? b.estimatedWaitMin ?? -1) - (a.measuredWaitMin ?? a.estimatedWaitMin ?? -1))) {
    const wait = q.measuredWaitMin ?? q.estimatedWaitMin
    const node = document.createElement('div')
    node.className = 'sw-queue-marker'
    const label = document.createElement('span')
    label.textContent = `${q.name} ${wait === null ? '—' : Math.round(wait) + '分'}`
    node.append(label)
    node.title = `${label.textContent}（${q.measuredWaitMin !== null ? '实测' : '估算'}）`
    labels.push(label)
    markers.push(new Marker({ element: node }).setLngLat([q.lon, q.lat]).addTo(map))
  }
  if (props.scene.showZoneLabels) {
    for (const f of props.scene.zones.features) {
      const c = ringCentroid(f.geometry)
      if (!c) continue
      const p = f.properties
      const node = document.createElement('div')
      node.className = 'sw-zone-label'
      const ratio = typeof p.ratio === 'number' ? `${Math.round(p.ratio * 100)}%` : LEVEL_LABELS[(p.level as Level) ?? 'UNKNOWN']
      node.innerHTML = `<span></span><em></em>`
      node.querySelector('span')!.textContent = String(p.name)
      node.querySelector('em')!.textContent = ratio
      ;(node.querySelector('em') as HTMLElement).style.color = String(p.color)
      markers.push(new Marker({ element: node }).setLngLat(c).addTo(map))
      labels.push(node)
    }
  }
  scheduleLabels()
}

/** 按排队时长优先保留标签，避开其他标签和地图控件；点位始终保留。 */
function layoutLabels() {
  if (!el.value) return
  const bounds = el.value.getBoundingClientRect()
  const occupied = [...(el.value.parentElement?.querySelectorAll('.toolbar, .legend, .notes') ?? [])]
    .map((node) => node.getBoundingClientRect())
  for (const label of labels) {
    const rect = label.getBoundingClientRect()
    const outside = rect.left < bounds.left + 4 || rect.right > bounds.right - 4
      || rect.top < bounds.top + 4 || rect.bottom > bounds.bottom - 4
    const overlaps = occupied.some((r) => rect.left < r.right + 4 && rect.right > r.left - 4
      && rect.top < r.bottom + 4 && rect.bottom > r.top - 4)
    label.style.visibility = outside || overlaps ? 'hidden' : 'visible'
    if (!outside && !overlaps) occupied.push(rect)
  }
}

function scheduleLabels() {
  cancelAnimationFrame(labelFrame)
  labelFrame = requestAnimationFrame(layoutLabels)
}

function focus() {
  const f = props.scene.focus
  if (!map || !f) return
  const key = f.join(',')
  if (key === lastFocusKey) return
  lastFocusKey = key
  map.fitBounds([[f[0], f[1]], [f[2], f[3]]], { padding: 70, pitch: 55, bearing: map.getBearing() || -18, duration: focusedOnce ? 1800 : 0, maxZoom: 16.2 })
  focusedOnce = true
}

function spin() {
  cancelAnimationFrame(raf)
  if (!props.orbit) return
  const step = () => {
    if (map && !interacting && !map.isMoving()) map.setBearing(map.getBearing() + 0.04)
    raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}

onMounted(() => {
  if (!el.value) return
  try {
    map = new MlMap({
      container: el.value,
      style,
      center: [126.6, 45.785],
      zoom: 13.2,
      pitch: 55,
      bearing: -18,
      attributionControl: false,
      maxPitch: 70,
      canvasContextAttributes: { antialias: true },
    })
  } catch (e) {
    emit('fail', e instanceof Error ? e.message : 'WebGL 不可用')
    return
  }
  map.on('error', (e) => console.warn('[map3d]', e.error?.message))
  map.on('move', scheduleLabels)
  map.on('resize', scheduleLabels)
  map.on('mousedown', () => (interacting = true))
  map.on('touchstart', () => (interacting = true))
  map.on('mouseup', () => (interacting = false))
  map.on('touchend', () => (interacting = false))
  map.on('load', async () => {
    try {
      await addBaseLayers(map!)
    } catch (e) {
      console.warn('[map3d] base layers', e)
    }
    if (!map) return
    addDynamicLayers(map)
    ready = true
    lastFocusKey = ''
    syncDynamic()
    spin()
  })
})

watch(() => props.scene, syncDynamic)
watch(() => props.orbit, spin)

onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  cancelAnimationFrame(labelFrame)
  markers.forEach((m) => m.remove())
  map?.remove()
  map = null
})
</script>

<template>
  <div ref="el" class="map3d" />
</template>

<style scoped>
.map3d {
  position: absolute;
  inset: 0;
}
</style>

<style>
/* Marker 节点由 MapLibre 挂载到地图容器内, 需使用全局样式 */
.sw-cam-marker {
  position: absolute;
  width: 14px;
  height: 14px;
  cursor: pointer;
}
.sw-cam-marker i {
  position: absolute;
  inset: 3px;
  border-radius: 50%;
  background: #5ef0ff;
  box-shadow: 0 0 10px #5ef0ff;
}
.sw-cam-marker b {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid rgba(94, 240, 255, 0.8);
  animation: sw-pulse 1.8s ease-out infinite;
}
.sw-cam-marker.off i {
  background: #6b7a90;
  box-shadow: none;
}
.sw-cam-marker.off b {
  border-color: rgba(107, 122, 144, 0.6);
  animation: none;
}
@keyframes sw-pulse {
  from { transform: scale(0.4); opacity: 1; }
  to { transform: scale(1.6); opacity: 0; }
}
.sw-queue-marker {
  width: 10px;
  height: 10px;
  background: #b48cff;
  border: 1px solid #efe4ff;
  border-radius: 2px;
  cursor: help;
}
.sw-queue-marker > span {
  position: absolute;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  padding: 2px 6px;
  font-size: 12px;
  color: #efe4ff;
  background: rgba(70, 40, 130, 0.82);
  border: 1px solid #b48cff;
  border-radius: 3px;
  white-space: nowrap;
  box-shadow: 0 0 8px rgba(180, 140, 255, 0.5);
  font-variant-numeric: tabular-nums;
}
.sw-zone-label {
  display: flex;
  flex-direction: column;
  align-items: center;
  font-size: 12px;
  line-height: 15px;
  color: #dff6ff;
  text-shadow: 0 0 4px #000, 0 0 2px #000;
  pointer-events: none;
  white-space: nowrap;
}
.sw-zone-label em {
  font-style: normal;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
@media (prefers-reduced-motion: reduce) {
  .sw-cam-marker b { animation: none; }
}
</style>
