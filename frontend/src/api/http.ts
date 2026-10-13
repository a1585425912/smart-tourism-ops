import axios, { AxiosError, type AxiosRequestConfig } from 'axios'

/** 后端统一错误体, 见 docs/api-contract.md §0 */
export interface ApiErrorBody {
  code: string
  message: string
  traceId?: string
  details?: Record<string, unknown>
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly traceId?: string
  readonly details?: Record<string, unknown>

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.status = status
    this.code = body.code
    this.traceId = body.traceId
    this.details = body.details
  }

  get isForbidden() {
    return this.status === 403
  }

  get isNotFound() {
    return this.status === 404
  }

  get isConflict() {
    return this.status === 409
  }
}

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  withCredentials: true,
  // 双提交 Cookie: 后端下发 XSRF-TOKEN, axios 自动放入请求头
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  withXSRFToken: true,
})

let unauthorizedHandler: (() => void) | null = null

/** 401 时回调(由路由注册为跳转登录页)。 */
export function onUnauthorized(handler: () => void) {
  unauthorizedHandler = handler
}

let csrfReady: Promise<void> | null = null

/** 确保已拿到 CSRF Cookie。 */
export function ensureCsrf(force = false): Promise<void> {
  if (force || !csrfReady || !document.cookie.includes('XSRF-TOKEN=')) {
    csrfReady = http.get('/auth/csrf').then(() => undefined)
  }
  return csrfReady
}

http.interceptors.request.use(async (config) => {
  const method = (config.method ?? 'get').toLowerCase()
  if (!['get', 'head', 'options'].includes(method) && !config.url?.endsWith('/auth/csrf')) {
    await ensureCsrf()
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  async (error: AxiosError<ApiErrorBody>) => {
    const status = error.response?.status ?? 0
    const body = error.response?.data
    const config = error.config as (AxiosRequestConfig & { _csrfRetried?: boolean }) | undefined
    // 会话变化后 CSRF 令牌失效: 刷新令牌后重试一次
    if (status === 403 && body?.code === 'CSRF_INVALID' && config && !config._csrfRetried) {
      config._csrfRetried = true
      await ensureCsrf(true)
      return http.request(config)
    }
    if (status === 401 && !config?.url?.endsWith('/auth/login')) {
      unauthorizedHandler?.()
    }
    if (body && typeof body === 'object' && 'code' in body) {
      return Promise.reject(new ApiError(status, body))
    }
    const message = status === 0 ? '无法连接服务器，请检查网络' : `请求失败 (${status})`
    return Promise.reject(new ApiError(status, { code: status === 0 ? 'NETWORK' : 'HTTP_' + status, message }))
  },
)

export async function get<T>(url: string, params?: Record<string, unknown>, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.get<T>(url, { params, paramsSerializer: { indexes: null }, ...config })
  return resp.data
}

export async function post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const resp = await http.post<T>(url, data, config)
  return resp.data
}

export async function patch<T>(url: string, data?: unknown): Promise<T> {
  const resp = await http.patch<T>(url, data)
  return resp.data
}

export async function del(url: string): Promise<void> {
  await http.delete(url)
}

export async function upload<T>(url: string, form: FormData, onProgress?: (percent: number) => void): Promise<T> {
  const resp = await http.post<T>(url, form, {
    timeout: 120000,
    onUploadProgress: (e) => {
      if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
    },
  })
  return resp.data
}

/** 把任意错误转换成可展示文本(附 traceId 便于排查)。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    return e.traceId ? `${e.message}（追踪号 ${e.traceId}）` : e.message
  }
  return e instanceof Error ? e.message : String(e)
}

export default http
