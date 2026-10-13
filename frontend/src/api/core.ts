import { get, post } from './http'
import type { Device, Me, PlaybackSession, Poi, Site, SystemInfo, Zone } from './types'

export const authApi = {
  me: () => get<Me>('/auth/me'),
  login: (username: string, password: string) => post<Me>('/auth/login', { username, password }),
  logout: () => post<{ ok: boolean }>('/auth/logout'),
  systemInfo: () => get<SystemInfo>('/system/info'),
}

export const scenicApi = {
  sites: () => get<Site[]>('/scenic-sites'),
  site: (id: number) => get<Site>(`/scenic-sites/${id}`),
  zones: (siteId: number) => get<Zone[]>(`/scenic-sites/${siteId}/zones`),
  devices: (params: { siteId?: number | number[]; type?: string; status?: string; keyPoint?: boolean } = {}) =>
    get<Device[]>('/devices', params),
  playback: (deviceId: number) => post<PlaybackSession>(`/devices/${deviceId}/playback-session`),
  pois: (params: { siteId?: number | number[]; category?: string } = {}) => get<Poi[]>('/map/pois', params),
}
