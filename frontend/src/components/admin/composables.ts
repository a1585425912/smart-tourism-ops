import { onBeforeUnmount, ref, shallowRef, watch, type Ref, type WatchSource } from 'vue'
import { agentApi, TERMINAL, type AgentRun, type RunStep } from '@/api/agent'

/**
 * 简单的异步数据加载状态。并发调用时只保留最后一次结果, 避免旧请求覆盖新数据。
 * deps 变化时自动重新加载。
 */
export function useAsync<T>(fn: () => Promise<T>, deps: WatchSource[] = [], immediate = true) {
  const data = shallowRef<T | null>(null) as Ref<T | null>
  const loading = ref(false)
  const error = shallowRef<unknown>(null)
  let seq = 0

  async function run() {
    const mine = ++seq
    loading.value = true
    error.value = null
    try {
      const result = await fn()
      if (mine === seq) data.value = result
    } catch (e) {
      if (mine === seq) error.value = e
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  if (deps.length) watch(deps, run)
  if (immediate) run()
  return { data, loading, error, run }
}

/**
 * 跟踪 Agent 任务进度: 优先 SSE(/agent/runs/{id}/events), 断开后降级为每 1.5 秒轮询。
 * 结束时(SUCCEEDED/FAILED/CANCELLED)再拉取一次完整任务。
 */
export function useAgentRun() {
  const run = shallowRef<AgentRun | null>(null)
  const steps = ref<RunStep[]>([])
  const status = ref<string | null>(null)
  const progress = ref<string | null>(null)
  const watching = ref(false)
  const error = shallowRef<unknown>(null)
  let es: EventSource | null = null
  let timer: ReturnType<typeof setInterval> | null = null
  let currentId: number | null = null
  const doneCallbacks: ((r: AgentRun) => void)[] = []

  function stop() {
    es?.close()
    es = null
    if (timer) clearInterval(timer)
    timer = null
    watching.value = false
  }

  async function refresh(id: number) {
    try {
      const r = await agentApi.get(id)
      if (id !== currentId) return
      run.value = r
      steps.value = r.steps ?? []
      status.value = r.status
      progress.value = r.progress
      if (TERMINAL.includes(r.status)) {
        stop()
        doneCallbacks.forEach((cb) => cb(r))
      }
    } catch (e) {
      error.value = e
    }
  }

  function poll(id: number) {
    if (timer) return
    timer = setInterval(() => refresh(id), 1500)
  }

  function track(id: number) {
    stop()
    currentId = id
    run.value = null
    steps.value = []
    status.value = 'QUEUED'
    progress.value = null
    error.value = null
    watching.value = true
    if (typeof EventSource === 'undefined') {
      poll(id)
      return
    }
    es = new EventSource(`/api/v1/agent/runs/${id}/events`, { withCredentials: true })
    es.addEventListener('status', (ev) => {
      const d = safeParse((ev as MessageEvent).data)
      if (d?.status) status.value = d.status
      if (d && 'progress' in d) progress.value = d.progress ?? null
    })
    es.addEventListener('step', (ev) => {
      const d = safeParse((ev as MessageEvent).data) as RunStep | null
      if (d && !steps.value.some((s) => s.seq === d.seq)) steps.value = [...steps.value, d]
    })
    es.addEventListener('done', () => {
      es?.close()
      es = null
      refresh(id)
    })
    es.onerror = () => {
      // SSE 不可用或中断: 降级轮询
      es?.close()
      es = null
      if (watching.value) poll(id)
    }
    refresh(id)
  }

  async function cancel() {
    if (currentId == null) return
    await agentApi.cancel(currentId)
    await refresh(currentId)
  }

  function onDone(cb: (r: AgentRun) => void) {
    doneCallbacks.push(cb)
  }

  onBeforeUnmount(stop)
  return { run, steps, status, progress, watching, error, track, cancel, stop, onDone }
}

function safeParse(s: string): Record<string, any> | null {
  try {
    return JSON.parse(s)
  } catch {
    return null
  }
}
