<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import 'ol/ol.css'
import OlMap from 'ol/Map'
import View from 'ol/View'
import Feature from 'ol/Feature'
import Collection from 'ol/Collection'
import GeoJSON from 'ol/format/GeoJSON'
import Point from 'ol/geom/Point'
import type Geometry from 'ol/geom/Geometry'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import Draw from 'ol/interaction/Draw'
import Modify from 'ol/interaction/Modify'
import { fromLonLat } from 'ol/proj'
import { createEmpty, extend, isEmpty } from 'ol/extent'
import { Circle as CircleStyle, Fill, Stroke, Style, Text } from 'ol/style'
import type { FeatureLike } from 'ol/Feature'
import type { Device, GeoPolygon, Site, Zone } from '@/api/types'

/**
 * 可复用的景区地图。底图为本地开放矢量数据(/geo/*.geojson, © OpenStreetMap contributors, ODbL),
 * 叠加景区边界、区域围栏(按类型或调用方给定颜色着色)与设备点位。坐标统一 WGS84, 显示投影 EPSG:3857。
 * mode: view 浏览 / draw 绘制新多边形 / modify 编辑选中区域的顶点。
 */
const props = withDefaults(defineProps<{
  sites?: Site[]
  zones?: Zone[]
  devices?: Device[]
  focusSiteId?: number | null
  selectedZoneId?: number | null
  selectedDeviceId?: number | null
  mode?: 'view' | 'draw' | 'modify'
  /** 覆盖区域颜色(如按热度着色): zoneId -> 颜色 */
  zoneColors?: Record<number, string>
  height?: string
}>(), { sites: () => [], zones: () => [], devices: () => [], mode: 'view', height: '460px' })

const emit = defineEmits<{
  drawn: [geometry: GeoPolygon]
  modified: [zoneId: number, geometry: GeoPolygon]
  selectZone: [zoneId: number | null]
  selectDevice: [deviceId: number]
}>()

const ZONE_COLORS: Record<string, string> = {
  AREA: '#1f6feb', ENTRANCE: '#2fa86b', ATTRACTION: '#8b5cf6', SERVICE: '#14b8a6', PARKING: '#64748b', COMMERCIAL: '#e6a23c',
}
const STATUS_COLORS: Record<string, string> = { ONLINE: '#2fa86b', OFFLINE: '#94a3b8', FAULT: '#e5484d', UNKNOWN: '#e6a23c' }

const el = ref<HTMLDivElement>()
const map = shallowRef<OlMap>()
const baseFailed = ref(false)
const fmt = new GeoJSON()
const PROJ = { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' }

const siteSource = new VectorSource()
const zoneSource = new VectorSource()
const deviceSource = new VectorSource()
const drawSource = new VectorSource()
let draw: Draw | null = null
let modify: Modify | null = null

function rgba(hex: string, a: number) {
  const n = parseInt(hex.slice(1), 16)
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`
}

function baseLayer(name: string, style: Style | ((f: FeatureLike) => Style | undefined), minZoom?: number) {
  const source = new VectorSource({ url: `/geo/${name}.geojson`, format: new GeoJSON() })
  source.on('featuresloaderror', () => (baseFailed.value = true))
  return new VectorLayer({ source, style, minZoom, declutter: false, updateWhileInteracting: false })
}

const roadStyles: Record<string, Style> = {}
function roadStyle(f: FeatureLike) {
  const hw = f.get('highway') as string | undefined
  const key = f.get('railway') ? 'rail' : hw ?? 'other'
  if (!roadStyles[key]) {
    const major = ['motorway', 'trunk', 'primary', 'secondary'].includes(key)
    roadStyles[key] = key === 'rail'
      ? new Style({ stroke: new Stroke({ color: '#c3cad5', width: 1, lineDash: [6, 4] }) })
      : new Style({ stroke: new Stroke({ color: major ? '#ffffff' : '#fbfcfd', width: major ? 3 : key === 'pedestrian' ? 2 : 1.4 }) })
  }
  return roadStyles[key]
}

function zoneStyle(f: FeatureLike) {
  const id = f.get('zoneId') as number
  const color = props.zoneColors?.[id] ?? ZONE_COLORS[f.get('zoneType') as string] ?? '#1f6feb'
  const selected = id === props.selectedZoneId
  return new Style({
    fill: new Fill({ color: rgba(color, selected ? 0.45 : 0.25) }),
    stroke: new Stroke({ color, width: selected ? 3 : 1.5 }),
    text: new Text({
      text: f.get('name') as string, font: '12px sans-serif', overflow: true,
      fill: new Fill({ color: '#1c2533' }), stroke: new Stroke({ color: '#fff', width: 3 }),
    }),
  })
}

function deviceStyle(f: FeatureLike) {
  const selected = f.get('deviceId') === props.selectedDeviceId
  const key = f.get('keyPoint') as boolean
  return new Style({
    image: new CircleStyle({
      radius: selected ? 9 : key ? 7 : 5,
      fill: new Fill({ color: STATUS_COLORS[f.get('status') as string] ?? '#94a3b8' }),
      stroke: new Stroke({ color: selected ? '#0b3d91' : '#fff', width: selected ? 3 : 1.5 }),
    }),
    text: selected ? new Text({ text: f.get('name') as string, offsetY: -18, font: '12px sans-serif', fill: new Fill({ color: '#1c2533' }), stroke: new Stroke({ color: '#fff', width: 3 }) }) : undefined,
  })
}

function syncSites() {
  siteSource.clear()
  for (const s of props.sites) {
    if (!s.boundary) continue
    const f = new Feature({ geometry: fmt.readGeometry(s.boundary, PROJ) as Geometry, name: s.name, siteId: s.id })
    siteSource.addFeature(f)
  }
}

function syncZones() {
  zoneSource.clear()
  drawSource.clear()
  for (const z of props.zones) {
    const f = new Feature({ geometry: fmt.readGeometry(z.boundary, PROJ) as Geometry, name: z.name, zoneId: z.id, zoneType: z.zoneType })
    f.setId(z.id)
    zoneSource.addFeature(f)
  }
  syncMode()
}

function syncDevices() {
  deviceSource.clear()
  for (const d of props.devices) {
    deviceSource.addFeature(new Feature({ geometry: new Point(fromLonLat([d.lon, d.lat])), name: d.name, deviceId: d.id, status: d.status, keyPoint: d.keyPoint }))
  }
}

function fit() {
  const m = map.value
  if (!m) return
  const extent = createEmpty()
  const focus = siteSource.getFeatures().filter((f) => props.focusSiteId == null || f.get('siteId') === props.focusSiteId)
  focus.forEach((f) => extend(extent, f.getGeometry()!.getExtent()))
  if (isEmpty(extent)) {
    zoneSource.getFeatures().forEach((f) => extend(extent, f.getGeometry()!.getExtent()))
    deviceSource.getFeatures().forEach((f) => extend(extent, f.getGeometry()!.getExtent()))
  }
  if (isEmpty(extent)) {
    const site = props.sites.find((s) => s.id === props.focusSiteId) ?? props.sites[0]
    if (site) m.getView().animate({ center: fromLonLat(site.center.coordinates), zoom: 15, duration: 300 })
    return
  }
  m.getView().fit(extent, { padding: [40, 40, 40, 40], maxZoom: 17, duration: 300 })
}

function syncMode() {
  const m = map.value
  if (!m) return
  if (draw) m.removeInteraction(draw)
  if (modify) m.removeInteraction(modify)
  draw = modify = null
  if (props.mode !== 'draw') drawSource.clear()
  if (props.mode === 'draw') {
    draw = new Draw({ source: drawSource, type: 'Polygon' })
    draw.on('drawstart', () => drawSource.clear())
    draw.on('drawend', (e) => {
      emit('drawn', fmt.writeGeometryObject(e.feature.getGeometry()!, { ...PROJ, decimals: 7 }) as GeoPolygon)
    })
    m.addInteraction(draw)
  } else if (props.mode === 'modify' && props.selectedZoneId != null) {
    const target = zoneSource.getFeatureById(props.selectedZoneId)
    if (target) {
      modify = new Modify({ features: new Collection([target]) })
      modify.on('modifyend', () => {
        emit('modified', props.selectedZoneId!, fmt.writeGeometryObject(target.getGeometry()!, { ...PROJ, decimals: 7 }) as GeoPolygon)
      })
      m.addInteraction(modify)
    }
  }
}

onMounted(() => {
  const zoneLayer = new VectorLayer({ source: zoneSource, style: zoneStyle, zIndex: 20 })
  const deviceLayer = new VectorLayer({ source: deviceSource, style: deviceStyle, zIndex: 30 })
  map.value = new OlMap({
    target: el.value,
    controls: [],
    layers: [
      baseLayer('areas', new Style({ fill: new Fill({ color: 'rgba(178, 218, 178, 0.4)' }) })),
      baseLayer('water', new Style({ fill: new Fill({ color: '#cbe4f6' }), stroke: new Stroke({ color: '#b5d6ee', width: 1 }) })),
      baseLayer('roads', roadStyle, 12),
      baseLayer('buildings', new Style({ fill: new Fill({ color: '#e3e7ed' }), stroke: new Stroke({ color: '#d5dbe3', width: 0.5 }) }), 14.5),
      new VectorLayer({ source: siteSource, zIndex: 10, style: new Style({ stroke: new Stroke({ color: '#0b3d91', width: 2, lineDash: [8, 6] }), fill: new Fill({ color: 'rgba(31,111,235,0.04)' }) }) }),
      zoneLayer,
      deviceLayer,
      new VectorLayer({ source: drawSource, zIndex: 40, style: new Style({ stroke: new Stroke({ color: '#e5484d', width: 2 }), fill: new Fill({ color: 'rgba(229,72,77,0.2)' }) }) }),
    ],
    view: new View({ center: fromLonLat([126.6, 45.78]), zoom: 13, minZoom: 11, maxZoom: 19 }),
  })
  map.value.on('singleclick', (e) => {
    if (props.mode === 'draw') return
    const hit = map.value!.forEachFeatureAtPixel(e.pixel, (f) => f, { layerFilter: (l) => l === deviceLayer || l === zoneLayer, hitTolerance: 4 })
    if (hit?.get('deviceId') != null) emit('selectDevice', hit.get('deviceId'))
    else if (props.mode !== 'modify') emit('selectZone', hit?.get('zoneId') ?? null)
  })
  syncSites()
  syncZones()
  syncDevices()
  fit()
})

watch(() => props.sites, () => { syncSites(); fit() })
watch(() => props.zones, syncZones)
watch(() => props.devices, syncDevices)
watch(() => props.focusSiteId, fit)
watch(() => [props.mode, props.selectedZoneId], () => { syncMode(); zoneSource.changed() })
watch(() => props.selectedDeviceId, () => deviceSource.changed())
watch(() => props.zoneColors, () => zoneSource.changed())

onBeforeUnmount(() => map.value?.setTarget(undefined))

function zoom(delta: number) {
  const v = map.value?.getView()
  if (v) v.animate({ zoom: (v.getZoom() ?? 13) + delta, duration: 200 })
}

defineExpose({ fit, clearDraft: () => drawSource.clear() })
</script>

<template>
  <div class="site-map" :style="{ height }">
    <div ref="el" class="canvas" />
    <div class="ctrl">
      <button type="button" title="放大" @click="zoom(1)">＋</button>
      <button type="button" title="缩小" @click="zoom(-1)">－</button>
      <button type="button" title="回到景区" @click="fit">⌂</button>
    </div>
    <div v-if="mode !== 'view'" class="mode">{{ mode === 'draw' ? '绘制模式：单击添加顶点，双击结束' : '编辑模式：拖动顶点调整围栏' }}</div>
    <div v-if="baseFailed" class="warn">底图图层加载失败，仅显示业务图层</div>
    <div class="attr">© OpenStreetMap contributors · WGS84</div>
  </div>
</template>

<style scoped>
.site-map { position: relative; border-radius: 8px; overflow: hidden; border: 1px solid var(--sw-border); background: #f1f4f8; min-height: 240px; }
.canvas { position: absolute; inset: 0; }
.ctrl { position: absolute; top: 10px; left: 10px; display: flex; flex-direction: column; gap: 4px; }
.ctrl button { width: 30px; height: 30px; border: 1px solid var(--sw-border); background: #fff; border-radius: 6px; cursor: pointer; font-size: 16px; color: var(--sw-text-2); }
.ctrl button:hover { color: var(--sw-primary); border-color: var(--sw-primary); }
.mode { position: absolute; top: 10px; left: 50%; transform: translateX(-50%); background: rgba(11, 61, 145, 0.9); color: #fff; font-size: 12px; padding: 4px 12px; border-radius: 14px; white-space: nowrap; }
.warn { position: absolute; bottom: 26px; left: 8px; font-size: 12px; color: #b8740a; background: rgba(255, 255, 255, 0.9); padding: 2px 8px; border-radius: 4px; }
.attr { position: absolute; right: 6px; bottom: 4px; font-size: 11px; color: var(--sw-text-2); background: rgba(255, 255, 255, 0.8); padding: 1px 6px; border-radius: 3px; }
</style>
