// 共享类型, 与 docs/api-contract.md 保持一致。各领域的扩展类型放在 src/api/<domain>.ts。

export type DataMode = 'REAL' | 'DEMO' | 'MIXED'
export type Quality = 'GOOD' | 'STALE' | 'PARTIAL' | 'MISSING'

export interface DataMeta {
  dataAsOf: string | null
  sourceIds: string[]
  dataMode: DataMode
  quality: Quality
}

export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface ImportResult {
  batchId: string
  total: number
  inserted: number
  duplicates: number
  errors: { line: number; message: string }[]
}

export interface SiteBrief {
  id: number
  code: string
  name: string
  shortName: string
}

export interface Me {
  id: number
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
  sites: SiteBrief[]
}

export interface SystemInfo {
  name: string
  now: string
  replay: boolean
  demoData: boolean
  aiProvider: 'fake' | 'deepseek'
  aiModel: string
  videoProvider: string
  mapAttribution: string
}

export interface GeoPoint {
  type: 'Point'
  coordinates: [number, number]
}

export interface GeoPolygon {
  type: 'Polygon'
  coordinates: number[][][]
}

export interface GeoMultiPolygon {
  type: 'MultiPolygon'
  coordinates: number[][][][]
}

export interface Site {
  id: number
  code: string
  name: string
  shortName: string
  timezone: string
  capacity: number
  warnRatio: number
  openTime: string | null
  closeTime: string | null
  seasonNote: string | null
  description: string | null
  center: GeoPoint
  boundary: GeoMultiPolygon | null
  boundarySrc: string | null
  status: 'OPEN' | 'CLOSED' | 'SEASONAL'
  sortOrder: number
  areaSqm: number | null
}

export type ZoneType = 'AREA' | 'ENTRANCE' | 'ATTRACTION' | 'SERVICE' | 'PARKING' | 'COMMERCIAL'

export interface Zone {
  id: number
  siteId: number
  code: string
  name: string
  zoneType: ZoneType
  boundary: GeoPolygon
  capacity: number | null
  warnRatio: number
  countSource: string | null
  remark: string | null
  version: number
  active: boolean
  areaSqm: number
  outsideSite: boolean
  updatedAt: string
}

export type DeviceType = 'CAMERA' | 'GATE' | 'COUNTER' | 'SENSOR' | 'SCREEN'
export type DeviceStatus = 'ONLINE' | 'OFFLINE' | 'FAULT' | 'UNKNOWN'

export interface Device {
  id: number
  siteId: number
  zoneId: number | null
  zoneName: string | null
  code: string
  name: string
  deviceType: DeviceType
  keyPoint: boolean
  lon: number
  lat: number
  vendor: string | null
  protocol: string | null
  sourceDeviceId: string | null
  streamRef: string | null
  status: DeviceStatus
  statusAt: string | null
  dataMode: 'REAL' | 'DEMO'
}

export interface PlaybackSession {
  status: 'OK' | 'OFFLINE' | 'INCOMPATIBLE'
  type: 'DEMO_SYNTHETIC' | 'DEMO_FILE' | 'HLS' | null
  url: string | null
  expiresAt: string | null
  message: string
  dataMode: string
  attribution: string | null
}

export interface Poi {
  id: number
  siteId: number
  osmRef: string
  name: string | null
  category: string
  subType: string | null
  lon: number
  lat: number
}
