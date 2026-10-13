<script setup lang="ts">
import { computed, ref } from 'vue'
import { adminApi } from '@/api/admin'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import { useAsync } from '@/components/admin/composables'
import { fmtTime } from '@/utils/format'

const app = useAppStore()
const limit = ref(100)
const keyword = ref('')
const logs = useAsync(() => adminApi.audit(limit.value), [limit])

const rows = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  const list = logs.data.value ?? []
  return k ? list.filter((l) => [l.actorName, l.action, l.targetType, l.targetId, l.detail, l.traceId].some((v) => v?.toLowerCase().includes(k))) : list
})
const siteName = (id: number | null) => (id == null ? '—' : app.sites.find((s) => s.id === id)?.shortName ?? `#${id}`)
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>审计日志</h2>
      <el-input v-model="keyword" placeholder="筛选操作人 / 动作 / 对象 / 追踪号" clearable size="small" style="width: 260px" />
      <el-select v-model="limit" size="small" style="width: 130px">
        <el-option :value="100" label="最近 100 条" /><el-option :value="200" label="最近 200 条" /><el-option :value="500" label="最近 500 条" />
      </el-select>
      <el-button size="small" @click="logs.run">刷新</el-button>
    </div>
    <div class="sw-card">
      <StatePanel :loading="logs.loading.value && !logs.data.value" :error="logs.error.value" :empty="!rows.length" empty-text="没有审计记录" @retry="logs.run">
        <el-table :data="rows" size="small" max-height="640">
          <el-table-column label="时间" width="150"><template #default="{ row }">{{ fmtTime(row.createdAt, 'MM-DD HH:mm:ss') }}</template></el-table-column>
          <el-table-column label="操作人" width="130"><template #default="{ row }">{{ row.actorName ?? '系统' }}</template></el-table-column>
          <el-table-column prop="action" label="动作" width="170" />
          <el-table-column label="对象" width="150"><template #default="{ row }">{{ row.targetType ?? '—' }}<template v-if="row.targetId"> #{{ row.targetId }}</template></template></el-table-column>
          <el-table-column label="景区" width="100"><template #default="{ row }">{{ siteName(row.siteId) }}</template></el-table-column>
          <el-table-column label="结果" width="80"><template #default="{ row }"><el-tag size="small" :type="row.outcome === 'OK' ? 'success' : 'danger'">{{ row.outcome === 'OK' ? '成功' : row.outcome }}</el-tag></template></el-table-column>
          <el-table-column label="详情" min-width="220"><template #default="{ row }">{{ row.detail ?? '' }}</template></el-table-column>
          <el-table-column label="追踪号" width="150"><template #default="{ row }"><code>{{ row.traceId ?? '' }}</code></template></el-table-column>
        </el-table>
      </StatePanel>
    </div>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
h2 { margin: 0 6px 0 0; font-size: 20px; }
code { font-size: 12px; color: var(--sw-text-3); }
</style>
