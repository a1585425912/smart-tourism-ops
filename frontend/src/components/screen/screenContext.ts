import { computed, inject, provide, ref, type ComputedRef, type InjectionKey, type Ref } from 'vue'
import type { Site } from '@/api/types'
import type { Overview } from '@/api/screen'
import { addDays, dayStartIso } from './screenUtils'

export interface ScreenContext {
  /** null 表示全部授权景区 */
  selectedSiteId: Ref<number | null>
  sites: Ref<Site[]>
  /** 当前范围内的景区 id */
  siteIds: ComputedRef<number[]>
  /** 自动刷新计数, 面板监听它重新加载 */
  tick: Ref<number>
  /** 业务日(大屏打开时的业务时钟日期) */
  today: Ref<string>
  range: ComputedRef<{ start: string; end: string; date: string; tomorrow: string }>
  overview: Ref<Overview | null>
  overviewError: Ref<unknown>
  siteName: (id: number) => string
  /** 地图点击摄像头后, 在大屏中央放大播放 */
  focusDeviceId: Ref<number | null>
}

const KEY: InjectionKey<ScreenContext> = Symbol('screen')

export function createScreenContext(today: string): ScreenContext {
  const selectedSiteId = ref<number | null>(null)
  const sites = ref<Site[]>([])
  const todayRef = ref(today)
  const ctx: ScreenContext = {
    selectedSiteId,
    sites,
    siteIds: computed(() =>
      selectedSiteId.value !== null ? [selectedSiteId.value] : sites.value.map((s) => s.id),
    ),
    tick: ref(0),
    today: todayRef,
    range: computed(() => {
      const tomorrow = addDays(todayRef.value, 1)
      return { start: dayStartIso(todayRef.value), end: dayStartIso(tomorrow), date: todayRef.value, tomorrow }
    }),
    overview: ref(null),
    overviewError: ref(null),
    siteName: (id: number) => sites.value.find((s) => s.id === id)?.shortName ?? `#${id}`,
    focusDeviceId: ref(null),
  }
  provide(KEY, ctx)
  return ctx
}

export function useScreen(): ScreenContext {
  const ctx = inject(KEY)
  if (!ctx) throw new Error('useScreen() must be used inside ScreenView')
  return ctx
}
