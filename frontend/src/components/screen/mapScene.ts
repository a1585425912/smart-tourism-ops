import type { Device, Site } from '@/api/types'
import type { QueueStatus } from '@/api/screen'
import type { GeoCollection } from './geoData'

/** 地图场景: 由 MapStage 组装, 2D/3D 两个渲染器共用。 */
export interface MapScene {
  sites: Site[]
  /** 区域多边形, properties: zoneId, siteId, name, level, ratio, count, capacity, color, height */
  zones: GeoCollection
  cameras: Device[]
  queues: QueueStatus[]
  /** 需要聚焦的范围 [minLon, minLat, maxLon, maxLat] */
  focus: [number, number, number, number] | null
  /** 单景区时显示区域标签 */
  showZoneLabels: boolean
}

export function ringCentroid(geometry: { type: string; coordinates: unknown }): [number, number] | null {
  const ring =
    geometry.type === 'Polygon'
      ? (geometry.coordinates as number[][][])[0]
      : geometry.type === 'MultiPolygon'
        ? (geometry.coordinates as number[][][][])[0]?.[0]
        : null
  if (!ring || ring.length === 0) return null
  const pts = ring.length > 1 && ring[0][0] === ring[ring.length - 1][0] && ring[0][1] === ring[ring.length - 1][1]
    ? ring.slice(0, -1)
    : ring
  const sum = pts.reduce((acc, p) => [acc[0] + p[0], acc[1] + p[1]], [0, 0])
  return [sum[0] / pts.length, sum[1] / pts.length]
}
