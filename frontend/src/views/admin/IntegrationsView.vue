<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { opsApi, type IntegrationSource, type Job } from '@/api/ops'
import { errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import { useAsync } from '@/components/admin/composables'
import { fmtDateTime, label } from '@/utils/format'

const app = useAppStore()
const sources = useAsync(() => opsApi.integrations())
const q = reactive({ type: '', status: '' })
const jobs = useAsync(() => opsApi.jobs({ type: q.type || undefined, status: q.status || undefined, limit: 100 }), [() => q.type, () => q.status])

let timer: ReturnType<typeof setInterval>
onMounted(() => (timer = setInterval(() => { sources.run(); jobs.run() }, 15000)))
onBeforeUnmount(() => clearInterval(timer))

const TYPES: Record<string, string> = {
  FLOW: '客流', REVIEW: '网评', QUEUE: '排队', BUSINESS: '业态经营', GROUP: '旅行社团队', PROFILE: '游客画像', VIDEO: '视频', WEATHER: '天气', HOLIDAY: '节假日', MAP: '地图',
}
const HEALTH: Record<IntegrationSource['health'], { n: string; type: string }> = {
  OK: { n: '正常', type: 'success' }, FAILED: { n: '失败', type: 'danger' }, STALE: { n: '数据过期', type: 'warning' }, UNKNOWN: { n: '未知', type: 'info' },
}
const JOB_STATUS: Record<Job['status'], string> = { QUEUED: 'info', RUNNING: 'primary', SUCCEEDED: 'success', FAILED: 'danger', CANCELLED: 'warning' }
const JOB_TYPES: Record<string, string> = { AGENT_RUN: 'AI 任务', FORECAST_REFRESH: '预测训练', FLOW_AGGREGATE: '客流汇总重算', REVIEW_IMPORT: '网评导入' }

const siteName = (id: number | null) => (id == null ? '全部' : app.sites.find((s) => s.id === id)?.shortName ?? `#${id}`)
const health = computed(() => {
  const list = sources.data.value ?? []
  return { total: list.length, bad: list.filter((s) => s.health === 'FAILED' || s.health === 'STALE').length, real: list.filter((s) => s.dataMode === 'REAL').length }
})
const jobTypes = computed(() => [...new Set((jobs.data.value ?? []).map((j) => j.jobType))])

function refreshText(s: number | null) {
  if (s == null) return '—'
  if (s >= 86400) return `${Math.round(s / 86400)} 天`
  if (s >= 3600) return `${Math.round(s / 3600)} 小时`
  if (s >= 60) return `${Math.round(s / 60)} 分钟`
  return `${s} 秒`
}

async function retry(j: Job) {
  try {
    await opsApi.retryJob(j.id)
    ElMessage.success('已重新排队')
    jobs.run()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>接入与任务状态</h2>
      <span class="chip">数据源 <b class="num">{{ health.total }}</b></span>
      <span class="chip" :class="{ bad: health.bad }">异常 <b class="num">{{ health.bad }}</b></span>
      <span class="chip">真实接入 <b class="num">{{ health.real }}</b></span>
      <span class="sw-muted">真实接入失败时显示失败或过期，不会自动切换为演示数据</span>
    </div>

    <div class="sw-card">
      <div class="sw-card-title"><span>数据源</span><span class="sw-muted">每 15 秒自动刷新</span></div>
      <StatePanel :loading="sources.loading.value && !sources.data.value" :error="sources.error.value" :empty="!sources.data.value?.length" empty-text="尚未配置数据源" @retry="sources.run">
        <el-table :data="sources.data.value ?? []" size="small">
          <el-table-column label="数据源" min-width="200">
            <template #default="{ row }">{{ row.name }}<div class="sw-muted"><code>{{ row.code }}</code></div></template>
          </el-table-column>
          <el-table-column label="类型" width="100"><template #default="{ row }">{{ TYPES[row.sourceType] ?? row.sourceType }}</template></el-table-column>
          <el-table-column label="模式" width="90"><template #default="{ row }"><el-tag size="small" :type="row.dataMode === 'REAL' ? 'success' : 'warning'" effect="plain">{{ row.dataMode === 'REAL' ? '真实接入' : '演示' }}</el-tag></template></el-table-column>
          <el-table-column label="景区" width="100"><template #default="{ row }">{{ siteName(row.siteId) }}</template></el-table-column>
          <el-table-column label="提供方 / 许可" min-width="200">
            <template #default="{ row }">{{ row.provider ?? '—' }}<div v-if="row.licenseNote" class="sw-muted">{{ row.licenseNote }}</div></template>
          </el-table-column>
          <el-table-column label="刷新周期" width="90"><template #default="{ row }">{{ refreshText(row.refreshSeconds) }}</template></el-table-column>
          <el-table-column label="健康" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="HEALTH[row.health as IntegrationSource['health']].type">{{ HEALTH[row.health as IntegrationSource['health']].n }}</el-tag>
              <div v-if="!row.enabled" class="sw-muted">已停用</div>
            </template>
          </el-table-column>
          <el-table-column label="最近成功" width="140"><template #default="{ row }">{{ fmtDateTime(row.lastSuccessAt) }}</template></el-table-column>
          <el-table-column label="最近失败" min-width="200">
            <template #default="{ row }">
              <template v-if="row.lastErrorAt"><span class="bad-text">{{ fmtDateTime(row.lastErrorAt) }}</span><div class="sw-muted">{{ row.lastError }}</div></template>
              <span v-else class="sw-muted">—</span>
            </template>
          </el-table-column>
        </el-table>
      </StatePanel>
    </div>

    <div class="sw-card">
      <div class="sw-card-title">
        <span>后台任务</span>
        <span class="filters">
          <el-select v-model="q.type" placeholder="任务类型" clearable size="small" style="width: 160px">
            <el-option v-for="t in jobTypes" :key="t" :label="JOB_TYPES[t] ?? t" :value="t" />
          </el-select>
          <el-select v-model="q.status" placeholder="状态" clearable size="small" style="width: 110px">
            <el-option v-for="(_, k) in JOB_STATUS" :key="k" :label="label(k)" :value="k" />
          </el-select>
        </span>
      </div>
      <StatePanel :loading="jobs.loading.value && !jobs.data.value" :error="jobs.error.value" :empty="!jobs.data.value?.length" empty-text="暂无任务记录" @retry="jobs.run">
        <el-table :data="jobs.data.value ?? []" size="small" max-height="460">
          <el-table-column prop="id" label="任务号" width="80" />
          <el-table-column label="类型" min-width="140"><template #default="{ row }">{{ JOB_TYPES[row.jobType] ?? row.jobType }}</template></el-table-column>
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="JOB_STATUS[row.status as Job['status']]">{{ label(row.status) }}</el-tag>
              <div v-if="row.cancelRequested && row.status === 'RUNNING'" class="sw-muted">取消中</div>
            </template>
          </el-table-column>
          <el-table-column label="尝试" width="70" align="center"><template #default="{ row }"><span class="num">{{ row.attempts }} / {{ row.maxAttempts }}</span></template></el-table-column>
          <el-table-column label="创建" width="140"><template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template></el-table-column>
          <el-table-column label="心跳 / 完成" width="140"><template #default="{ row }">{{ fmtDateTime(row.finishedAt ?? row.heartbeatAt) }}</template></el-table-column>
          <el-table-column label="失败原因" min-width="220"><template #default="{ row }"><span class="bad-text">{{ row.lastError ?? '' }}</span></template></el-table-column>
          <el-table-column v-if="app.has('INTEGRATION_MANAGE')" label="" width="70" fixed="right">
            <template #default="{ row }"><el-button v-if="row.status === 'FAILED'" link type="primary" size="small" @click="retry(row)">重试</el-button></template>
          </el-table-column>
        </el-table>
      </StatePanel>
    </div>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
h2 { margin: 0 6px 0 0; font-size: 20px; }
.chip { background: #fff; border: 1px solid var(--sw-border); border-radius: 16px; padding: 3px 12px; font-size: 13px; color: var(--sw-text-2); }
.chip b { margin-left: 4px; }
.chip.bad b { color: var(--sw-danger); }
.filters { display: flex; gap: 8px; font-weight: 400; }
.bad-text { color: var(--sw-danger); font-size: 12px; word-break: break-all; }
code { font-size: 12px; }
</style>
