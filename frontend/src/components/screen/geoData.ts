// 静态开放地图图层(© OpenStreetMap contributors, ODbL), 来自 public/geo, 只加载一次。
import { buildingHeight, heightColor } from './screenUtils'

export interface GeoFeature {
  type: 'Feature'
  properties: Record<string, unknown>
  geometry: { type: string; coordinates: unknown }
}

export interface GeoCollection {
  type: 'FeatureCollection'
  features: GeoFeature[]
}

export interface BaseLayers {
  water: GeoCollection
  roads: GeoCollection
  areas: GeoCollection
  buildings: GeoCollection
}

let cache: Promise<BaseLayers> | null = null

async function fetchLayer(name: string): Promise<GeoCollection> {
  const resp = await fetch(`/geo/${name}.geojson`)
  if (!resp.ok) throw new Error(`底图图层 ${name} 加载失败 (${resp.status})`)
  return (await resp.json()) as GeoCollection
}

const MAJOR_ROADS = new Set(['motorway', 'trunk', 'primary', 'secondary'])

export function loadBaseLayers(): Promise<BaseLayers> {
  if (!cache) {
    cache = Promise.all(['water', 'roads', 'areas', 'buildings'].map(fetchLayer))
      .then(([water, roads, areas, buildings]) => {
        // 只保留面状建筑, 预先计算拉伸高度与配色
        buildings.features = buildings.features.filter((f) => f.geometry.type === 'Polygon' || f.geometry.type === 'MultiPolygon')
        for (const f of buildings.features) {
          const h = buildingHeight(f.properties)
          f.properties._h = h
          f.properties._c = heightColor(h)
        }
        for (const f of roads.features) {
          f.properties._major = MAJOR_ROADS.has(String(f.properties.highway ?? '')) ? 1 : 0
          f.properties._rail = f.properties.railway ? 1 : 0
        }
        water.features = water.features.filter((f) => f.geometry.type !== 'Point')
        areas.features = areas.features.filter((f) => f.geometry.type === 'Polygon' || f.geometry.type === 'MultiPolygon')
        return { water, roads, areas, buildings }
      })
      .catch((e) => {
        cache = null
        throw e
      })
  }
  return cache
}
