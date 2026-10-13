<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { reportApi, type ReportListItem } from '@/api/report'
import type { ReportRef } from '@/api/agent'
import { ApiError, errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import RunProgress from '@/components/admin/RunProgress.vue'
import { useAgentRun, useAsync } from '@/components/admin/composables'
import { fmtDateTime } from '@/utils/format'

const app = useAppStore()
const router = useRouter()
const siteId = computed(() => app.currentSite?.id ?? null)

const q = reactive({ page: 1, size: 20 })
const list = useAsync(() => reportApi.list({ siteId: siteId.value ?? undefined, page: q.page, size: q.size, latestOnly: true }), [siteId, () => q.page, () => q.size])
watch(siteId, () => (q.page = 1))

const dialog = ref(false)
const form = reactive<{ siteId: number | null; reportDate: string }>({ siteId: null, reportDate: '' })
const starting = ref(false)
const genError = ref('')
const agent = useAgentRun()
const made = ref<ReportRef | null>(null)
agent.onDone((r) => {
  if (r.status === 'SUCCEEDED') {
    made.value = r.result as ReportRef | null
    list.run()
  }
})

function openGen() {
  form.siteId = siteId.value
  form.reportDate = app.now().subtract(1, 'day').format('YYYY-MM-DD')
  genError.value = ''
  made.value = null
  agent.stop()
  agent.status.value = null
  dialog.value = true
}

async function start() {
  if (!form.siteId || !form.reportDate) {
    genError.value = '请选择景区和日期'
    return
  }
  starting.value = true
  genError.value = ''
  try {
    const r = await reportApi.generate(form.siteId, form.reportDate)
    agent.track(r.runId)
  } catch (e) {
    genError.value = e instanceof ApiError && e.code === 'QUOTA_EXCEEDED' ? '今日 AI 调用额度已用完' : errorText(e)
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>运营日报</h2>
      <span class="sw-muted">日报由固定统计快照 + AI 分析生成；重复生成会建立新版本，不覆盖历史版本</span>
      <span class="grow" />
      <el-button v-if="app.has('REPORT_WRITE')" type="primary" size="small" @click="openGen">生成日报</el-button>
    </div>

    <div class="sw-card">
      <StatePanel :loading="list.loading.value && !list.data.value" :error="list.error.value" :empty="!list.data.value?.items.length" empty-text="暂无日报，可点击“生成日报”" min-height="240px" @retry="list.run">
        <el-table :data="list.data.value?.items ?? []" size="small" @row-click="(r: ReportListItem) => router.push(`/admin/reports/${r.id}`)">
          <el-table-column prop="reportDate" label="报告日期" width="110" />
          <el-table-column prop="siteName" label="景区" width="140" />
          <el-table-column label="标题" min-width="220">
            <template #default="{ row }">{{ row.title }} <el-tag v-if="row.dataMode !== 'REAL'" size="small" type="warning" effect="plain">{{ row.dataMode === 'MIXED' ? '含演示数据' : '演示数据' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="版本" width="70" align="center"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
          <el-table-column label="数据截止" width="150"><template #default="{ row }">{{ fmtDateTime(row.dataAsOf) }}</template></el-table-column>
          <el-table-column label="生成方式" width="170"><template #default="{ row }">{{ row.provider === 'deepseek' ? 'DeepSeek' : '本地规则模拟' }} {{ row.model ?? '' }}</template></el-table-column>
          <el-table-column label="生成人 / 时间" width="190"><template #default="{ row }">{{ row.createdByName ?? '—' }} · {{ fmtDateTime(row.createdAt) }}</template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="q.page" v-model:page-size="q.size" class="pager" small background layout="total, prev, pager, next" :total="list.data.value?.total ?? 0" />
      </StatePanel>
    </div>

    <el-dialog v-model="dialog" title="生成运营日报" width="min(480px, 94vw)" :close-on-click-modal="false">
      <template v-if="!agent.status.value">
        <el-form label-width="72px">
          <el-form-item label="景区" required>
            <el-select v-model="form.siteId" placeholder="选择景区" style="width: 100%">
              <el-option v-for="s in app.sites" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="日期" required><el-date-picker v-model="form.reportDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 100%" /></el-form-item>
        </el-form>
        <div v-if="genError" class="err">{{ genError }}</div>
      </template>
      <template v-else>
        <RunProgress :status="agent.status.value" :progress="agent.progress.value" :steps="agent.steps.value" :error-message="agent.run.value?.errorMessage" />
        <div v-if="made" class="ok">日报已生成（第 {{ made.version }} 版）。</div>
      </template>
      <template #footer>
        <el-button v-if="agent.watching.value" @click="agent.cancel">取消任务</el-button>
        <el-button v-else @click="dialog = false">关闭</el-button>
        <el-button v-if="!agent.status.value" type="primary" :loading="starting" @click="start">开始生成</el-button>
        <el-button v-else-if="made" type="primary" @click="router.push(`/admin/reports/${made.reportId}`)">查看日报</el-button>
        <el-button v-else-if="!agent.watching.value" type="primary" @click="agent.status.value = null">重试</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
.grow { flex: 1; }
.pager { margin-top: 12px; justify-content: flex-end; }
.err { color: var(--sw-danger); font-size: 13px; }
.ok { margin-top: 12px; color: var(--sw-ok); }
:deep(.el-table__row) { cursor: pointer; }
</style>
