import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import dayjs from 'dayjs'
import { authApi } from '@/api/core'
import { ensureCsrf } from '@/api/http'
import type { Me, SystemInfo } from '@/api/types'

const SITE_KEY = 'snow.currentSiteId'

function readSavedSite(): number | null {
  try {
    const v = localStorage.getItem(SITE_KEY)
    return v ? Number(v) : null
  } catch {
    return null
  }
}

/**
 * 会话、系统信息(业务时钟)与当前景区。
 * 业务时钟: 后端 /system/info 返回 now(演示回放时为回放时间), 前端据此推算"当前时间", 所有"今天"均以它为准。
 */
export const useAppStore = defineStore('app', () => {
  const me = ref<Me | null>(null)
  const system = ref<SystemInfo | null>(null)
  const clockOffsetMs = ref(0)
  const currentSiteId = ref<number | null>(readSavedSite())

  const loggedIn = computed(() => me.value !== null)
  const sites = computed(() => me.value?.sites ?? [])
  const currentSite = computed(() => sites.value.find((s) => s.id === currentSiteId.value) ?? sites.value[0] ?? null)

  function has(permission: string) {
    return me.value?.permissions.includes(permission) ?? false
  }

  function hasRole(role: string) {
    return me.value?.roles.includes(role) ?? false
  }

  /** 业务"当前时间"。 */
  function now() {
    return dayjs(Date.now() + clockOffsetMs.value)
  }

  function today() {
    return now().format('YYYY-MM-DD')
  }

  async function loadSystem() {
    const info = await authApi.systemInfo()
    system.value = info
    clockOffsetMs.value = dayjs(info.now).valueOf() - Date.now()
    return info
  }

  async function loadMe() {
    try {
      me.value = await authApi.me()
      if (!sites.value.some((s) => s.id === currentSiteId.value)) {
        selectSite(sites.value[0]?.id ?? null)
      }
    } catch {
      me.value = null
    }
    return me.value
  }

  async function login(username: string, password: string) {
    await ensureCsrf(true)
    me.value = await authApi.login(username, password)
    // 登录后会话 ID 变化, 重新获取 CSRF 令牌
    await ensureCsrf(true)
    if (!sites.value.some((s) => s.id === currentSiteId.value)) {
      selectSite(sites.value[0]?.id ?? null)
    }
    return me.value
  }

  async function logout() {
    try {
      await authApi.logout()
    } finally {
      me.value = null
    }
  }

  function selectSite(id: number | null) {
    currentSiteId.value = id
    try {
      if (id == null) localStorage.removeItem(SITE_KEY)
      else localStorage.setItem(SITE_KEY, String(id))
    } catch {
      /* 浏览器禁用存储时忽略 */
    }
  }

  return {
    me, system, currentSiteId, currentSite, sites, loggedIn,
    has, hasRole, now, today, loadSystem, loadMe, login, logout, selectSite,
  }
})
