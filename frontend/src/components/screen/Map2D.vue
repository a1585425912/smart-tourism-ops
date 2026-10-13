<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import OlMap from 'ol/Map'
import View from 'ol/View'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import GeoJSON from 'ol/format/GeoJSON'
import Feature from 'ol/Feature'
import Point from 'ol/geom/Point'
import { Circle as CircleStyle, Fill, Stroke, Style, Text } from 'ol/style'
import { fromLonLat, transformExtent } from 'ol/proj'
import { defaults as defaultInteractions } from 'ol/interaction/defaults'
import 'ol/ol.css'
import { loadBaseLayers } from './geoData'
import type { MapScene } from './mapScene'
import { LEVEL_LABELS, type Level } from './screenUtils'

const props = defineProps<{ scene: MapScene }>()
const emit = defineEmits<{ focusDevice: [id: number] }>()

const el = ref<HTMLDivElement>()
const fmt = new GeoJSON({ featureProjection: 'EPSG:3857' })
let map: OlMap | null = null
let lastFocusKey = ''

const siteSource = new VectorSource()
const zoneSource = new VectorSource()
const pointSource = new VectorSource()

const roadStyleMajor = new Style({ stroke: new Stroke({ color: '#2c6fa6', width: 2.2 }) })
const roadStyleMinor = new Style({ stroke: new Stroke({ color: '#173b5e', width: 1 }) })
const railStyle = new Style({ stroke: new Stroke({ color: '#4a6a8a', width: 1.2, lineDash: [4, 4] }) })

function zoneStyle(f: import('ol/Feature').FeatureLike) {
  const color = String(f.get('color'))
  const ratio = f.get('ratio') as number | null
  const label = props.scene.showZoneLabels
    ? `${f.get('name')}\n${typeof ratio === 'number' ? Math.round(ratio * 100) + '%' : LEVEL_LABELS[(f.get('level') as Level) ?? 'UNKNOWN']}`
    : undefined
  return new Style({
    fill: new Fill({ color: color + '59' }),
    stroke: new Stroke({ color, width: 1.6 }),
    text: label
      ? new Text({ text: label, font: '12px "Microsoft YaHei", sans-serif', fill: new Fill({ color: '#e6f8ff' }),
          stroke: new Stroke({ color: '#04101f', width: 3 }) })
      : undefined,
  })
}

function pointStyle(f: import('ol/Feature').FeatureLike) {
  if (f.get('kind') === 'camera') {
    const online = f.get('online')
    return new Style({
      image: new CircleStyle({
        radius: 6,
        fill: new Fill({ color: online ? '#5ef0ff' : '#6b7a90' }),
        stroke: new Stroke({ color: online ? 'rgba(94,240,255,0.45)' : 'rgba(107,122,144,0.5)', width: 6 }),
      }),
    })
  }
  return new Style({
    text: new Text({
      text: String(f.get('label')),
      font: '12px "Microsoft YaHei", sans-serif',
      fill: new Fill({ color: '#efe4ff' }),
      backgroundFill: new Fill({ color: 'rgba(70,40,130,0.85)' }),
      backgroundStroke: new Stroke({ color: '#b48cff', width: 1 }),
      padding: [2, 4, 1, 4],
      offsetY: -12,
    }),
  })
}

function sync() {
  siteSource.clear()
  zoneSource.clear()
  pointSource.clear()
  for (const s of props.scene.sites) {
    if (s.boundary) siteSource.addFeature(fmt.readFeature({ type: 'Feature', geometry: s.boundary, properties: { name: s.name } }) as Feature)
  }
  zoneSource.addFeatures(fmt.readFeatures(props.scene.zones) as Feature[])
  for (const c of props.scene.cameras) {
    pointSource.addFeature(new Feature({ geometry: new Point(fromLonLat([c.lon, c.lat])), kind: 'camera', deviceId: c.id, online: c.status === 'ONLINE' }))
  }
  for (const q of props.scene.queues) {
    const wait = q.measuredWaitMin ?? q.estimatedWaitMin
    pointSource.addFeature(new Feature({ geometry: new Point(fromLonLat([q.lon, q.lat])), kind: 'queue',
      label: `${q.name} ${wait === null ? '—' : Math.round(wait) + '分'}` }))
  }
  const f = props.scene.focus
  if (map && f && f.join(',') !== lastFocusKey) {
    const first = lastFocusKey === ''
    lastFocusKey = f.join(',')
    map.getView().fit(transformExtent(f, 'EPSG:4326', 'EPSG:3857'), { padding: [60, 60, 60, 60], duration: first ? 0 : 1000, maxZoom: 17 })
  }
}

onMounted(async () => {
  if (!el.value) return
  map = new OlMap({
    target: el.value,
    controls: [],
    interactions: defaultInteractions({ altShiftDragRotate: false, pinchRotate: false }),
    view: new View({ center: fromLonLat([126.6, 45.785]), zoom: 13.5 }),
    layers: [],
  })
  map.on('singleclick', (evt) => {
    map?.forEachFeatureAtPixel(evt.pixel, (feat) => {
      if (feat.get('kind') === 'camera') {
        emit('focusDevice', feat.get('deviceId') as number)
        return true
      }
      return false
    })
  })
  map.on('pointermove', (evt) => {
    const hit = map?.hasFeatureAtPixel(evt.pixel, { layerFilter: (l) => l.get('name') === 'points' })
    if (el.value) el.value.style.cursor = hit ? 'pointer' : ''
  })
  try {
    const base = await loadBaseLayers()
    const layer = (data: unknown, style: Style | ((f: import('ol/Feature').FeatureLike) => Style)) =>
      new VectorLayer({ source: new VectorSource({ features: fmt.readFeatures(data) as Feature[] }), style })
    map.addLayer(layer(base.areas, new Style({ fill: new Fill({ color: 'rgba(13,43,58,0.6)' }) })))
    map.addLayer(layer(base.water, new Style({ fill: new Fill({ color: '#0a3557' }), stroke: new Stroke({ color: '#1f78b4', width: 1 }) })))
    map.addLayer(layer(base.roads, (f) => (f.get('_rail') ? railStyle : f.get('_major') ? roadStyleMajor : roadStyleMinor)))
    map.addLayer(layer(base.buildings, new Style({ fill: new Fill({ color: '#16385c' }), stroke: new Stroke({ color: '#1f4f7c', width: 0.5 }) })))
  } catch (e) {
    console.warn('[map2d] base layers', e)
  }
  map.addLayer(new VectorLayer({ source: siteSource, style: [
    new Style({ stroke: new Stroke({ color: 'rgba(94,240,255,0.35)', width: 8 }) }),
    new Style({ stroke: new Stroke({ color: '#b8f8ff', width: 1.6 }), fill: new Fill({ color: 'rgba(46,197,255,0.06)' }) }),
  ] }))
  map.addLayer(new VectorLayer({ source: zoneSource, style: zoneStyle }))
  const points = new VectorLayer({ source: pointSource, style: pointStyle })
  points.set('name', 'points')
  map.addLayer(points)
  sync()
})

watch(() => props.scene, sync)

onBeforeUnmount(() => {
  map?.setTarget(undefined)
  map = null
})
</script>

<template>
  <div ref="el" class="map2d" />
</template>

<style scoped>
.map2d {
  position: absolute;
  inset: 0;
}
</style>
