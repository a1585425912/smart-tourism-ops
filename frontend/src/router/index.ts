import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { onUnauthorized } from '@/api/http'
import { useAppStore } from '@/stores/app'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 需要的权限码之一; 缺省表示登录即可 */
    permission?: string
    /** 公开页面, 不要求登录 */
    public?: boolean
    /** 侧边栏分组 */
    group?: string
    icon?: string
    hidden?: boolean
  }
}

const adminChildren: RouteRecordRaw[] = [
  { path: 'overview', component: () => import('@/views/admin/OverviewView.vue'), meta: { title: '运营总览', group: '综合态势', icon: 'DataBoard' } },
  { path: 'sites', component: () => import('@/views/admin/SitesView.vue'), meta: { title: '景区与电子围栏', group: '综合态势', icon: 'MapLocation', permission: 'SITE_READ' } },
  { path: 'devices', component: () => import('@/views/admin/DevicesView.vue'), meta: { title: '点位监控', group: '综合态势', icon: 'VideoCamera', permission: 'DEVICE_READ' } },
  { path: 'flows', component: () => import('@/views/admin/FlowsView.vue'), meta: { title: '客流分析', group: '客流与预测', icon: 'TrendCharts', permission: 'FLOW_READ' } },
  { path: 'queues', component: () => import('@/views/admin/QueuesView.vue'), meta: { title: '排队时长', group: '客流与预测', icon: 'Timer', permission: 'FLOW_READ' } },
  { path: 'forecast', component: () => import('@/views/admin/ForecastView.vue'), meta: { title: '客流预测与调度', group: '客流与预测', icon: 'Histogram', permission: 'FLOW_READ' } },
  { path: 'profiles', component: () => import('@/views/admin/ProfilesView.vue'), meta: { title: '游客画像', group: '客流与预测', icon: 'User', permission: 'FLOW_READ' } },
  { path: 'reviews', component: () => import('@/views/admin/ReviewsView.vue'), meta: { title: '网评舆情', group: '服务优化', icon: 'ChatDotRound', permission: 'REVIEW_READ' } },
  { path: 'reviews/:id', component: () => import('@/views/admin/ReviewDetailView.vue'), meta: { title: '评价详情', permission: 'REVIEW_READ', hidden: true } },
  { path: 'inspections', component: () => import('@/views/admin/InspectionsView.vue'), meta: { title: '日常巡检', group: '服务优化', icon: 'Checked', permission: 'INSPECTION_READ' } },
  { path: 'inspections/:id', component: () => import('@/views/admin/InspectionDetailView.vue'), meta: { title: '巡检详情', permission: 'INSPECTION_READ', hidden: true } },
  { path: 'business', component: () => import('@/views/admin/BusinessView.vue'), meta: { title: '多业态经营', group: '业态融合', icon: 'Shop', permission: 'BUSINESS_READ' } },
  { path: 'groups', component: () => import('@/views/admin/GroupsView.vue'), meta: { title: '旅行社与团队', group: '业态融合', icon: 'Van', permission: 'BUSINESS_READ' } },
  { path: 'marketing', component: () => import('@/views/admin/MarketingView.vue'), meta: { title: '套票与营销方案', group: '业态融合', icon: 'Present', permission: 'MARKETING_WRITE' } },
  { path: 'assistant', component: () => import('@/views/admin/AssistantView.vue'), meta: { title: 'AI 运营助手', group: '智能决策', icon: 'MagicStick', permission: 'AI_USE' } },
  { path: 'reports', component: () => import('@/views/admin/ReportsView.vue'), meta: { title: '运营日报', group: '智能决策', icon: 'Document', permission: 'REPORT_READ' } },
  { path: 'reports/:id', component: () => import('@/views/admin/ReportDetailView.vue'), meta: { title: '报告详情', permission: 'REPORT_READ', hidden: true } },
  { path: 'integrations', component: () => import('@/views/admin/IntegrationsView.vue'), meta: { title: '接入与任务状态', group: '系统管理', icon: 'Connection', permission: 'INTEGRATION_READ' } },
  { path: 'users', component: () => import('@/views/admin/UsersView.vue'), meta: { title: '用户与授权', group: '系统管理', icon: 'Avatar', permission: 'USER_MANAGE' } },
  { path: 'audit', component: () => import('@/views/admin/AuditView.vue'), meta: { title: '审计日志', group: '系统管理', icon: 'Tickets', permission: 'AUDIT_READ' } },
]

export const routes: RouteRecordRaw[] = [
  { path: '/login', component: () => import('@/views/LoginView.vue'), meta: { title: '登录', public: true } },
  { path: '/', redirect: '/admin/overview' },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    redirect: '/admin/overview',
    children: adminChildren,
  },
  { path: '/screen', component: () => import('@/views/screen/ScreenView.vue'), meta: { title: '指挥大屏' } },
  {
    path: '/m',
    component: () => import('@/layouts/MobileLayout.vue'),
    children: [
      { path: '', component: () => import('@/views/mobile/MobileTasksView.vue'), meta: { title: '我的巡检', permission: 'INSPECTION_READ' } },
      { path: 'task/:id', component: () => import('@/views/mobile/MobileTaskView.vue'), meta: { title: '执行巡检', permission: 'INSPECTION_READ' } },
    ],
  },
  { path: '/forbidden', component: () => import('@/views/ForbiddenView.vue'), meta: { title: '无权访问', public: true } },
  { path: '/:pathMatch(.*)*', component: () => import('@/views/NotFoundView.vue'), meta: { title: '页面不存在', public: true } },
]

export const adminMenu = adminChildren

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const app = useAppStore()
  if (!app.system) {
    await app.loadSystem().catch(() => undefined)
  }
  if (to.meta.public) return true
  if (!app.loggedIn) {
    await app.loadMe()
  }
  if (!app.loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.permission && !app.has(to.meta.permission)) {
    return { path: '/forbidden' }
  }
  return true
})

router.afterEach((to) => {
  document.title = `${to.meta.title ?? ''} · 多业态融合智慧文旅管控系统`
})

onUnauthorized(() => {
  const app = useAppStore()
  app.me = null
  const current = router.currentRoute.value
  if (!current.meta.public) {
    router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

export default router
