import { onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref, type WatchSource } from 'vue'

/**
 * 面板级数据加载: 每个面板独立的 loading/error, 一个接口失败不影响其它面板。
 * 刷新时保留上一次数据(避免闪烁), 只有首次加载显示 loading。
 */
export function usePanel<T>(loader: () => Promise<T>, deps: WatchSource[] = []) {
  const data = shallowRef<T | null>(null) as Ref<T | null>
  const loading = ref(false)
  const error = shallowRef<unknown>(null)
  let seq = 0

  async function reload() {
    const mine = ++seq
    if (data.value === null) loading.value = true
    try {
      const result = await loader()
      if (mine !== seq) return
      data.value = result
      error.value = null
    } catch (e) {
      if (mine !== seq) return
      error.value = e
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  if (deps.length) {
    watch(deps, () => {
      data.value = null
      reload()
    })
  }

  return { data, loading, error, reload }
}

/** 定时刷新, 页面不可见时暂停, 重新可见时立即刷新一次。 */
export function useAutoRefresh(fn: () => void, intervalMs = 30000) {
  let timer: ReturnType<typeof setInterval> | null = null

  const start = () => {
    stop()
    timer = setInterval(fn, intervalMs)
  }
  const stop = () => {
    if (timer) clearInterval(timer)
    timer = null
  }
  const onVisibility = () => {
    if (document.hidden) stop()
    else {
      fn()
      start()
    }
  }

  onMounted(() => {
    fn()
    start()
    document.addEventListener('visibilitychange', onVisibility)
  })
  onBeforeUnmount(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibility)
  })
}
