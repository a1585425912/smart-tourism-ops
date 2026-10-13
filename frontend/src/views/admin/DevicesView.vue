<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { scenicApi } from '@/api/core'
import type { Device, DeviceStatus, DeviceType } from '@/api/types'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import SiteMap from '@/components/map/SiteMap.vue'
import VideoPlayer from '@/components/admin/VideoPlayer.vue'
import { useAsync } from '@/components/admin/composables'
import { fmtDateTime, label } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)
const canVideo = computed(() => app.has('VIDEO_VIEW'))

const TYPE_LABELS: Record<DeviceType, string> = { CAMERA: '摄像头', GATE: '闸机', COUNTER: '客流计数器', SENSOR: '传感器', SCREEN: '信息屏' }
const STATUS_TAG: Record<DeviceStatus, string> = { ONLINE: 'success', OFFLINE: 'info', FAULT: 'danger', UNKNOWN: 'warning' }

const filter = reactive<{ type: string; status: string; keyPoint: boolean }>({ type: '', status: '', keyPoint: false })

const all = useAsync(async () => (siteId.value ? scenicApi.devices({ siteId: siteId.value }) : []), [siteId])
const site = useAsync(async () => (siteId.value ? scenicApi.site(siteId.value) : null), [siteId])
const zones = useAsync(async () => (siteId.value ? scenicApi.zones(siteId.value) : []), [siteId])

const devices = computed(() => (all.data.value ?? []).filter((d) =>
  (!filter.type || d.deviceType === filter.type) && (!filter.status || d.status === filter.status) && (!filter.keyPoint || d.keyPoint)))

// 状态汇总由设备清单统计得出
const summary = computed(() => {
  const list = all.data.value ?? []
  const by = (s: DeviceStatus) => list.filter((d) => d.status === s).length
  return { total: list.length, online: by('ONLINE'), offline: by('OFFLINE'), fault: by('FAULT'), unknown: by('UNKNOWN'), cameras: list.filter((d) => d.deviceType === 'CAMERA').length }
})

const selectedId = ref<number | null>(null)
const playing = ref<Device | null>(null)
const wall = ref(false)
const keyCameras = computed(() => (all.data.value ?? []).filter((d) => d.deviceType === 'CAMERA' && d.keyPoint).slice(0, 6))

function pick(id: number) {
  selectedId.value = id
  const d = all.data.value?.find((x) => x.id === id)
  if (d && d.deviceType === 'CAMERA' && canVideo.value) playing.value = d
}
</script>

<template>
  <div class="sw-page">
    <div class="chips">
      <span class="chip">设备总数 <b class="num">{{ summary.total }}</b></span>
      <span class="chip ok">在线 <b class="num">{{ summary.online }}</b></span>
      <span class="chip off">离线 <b class="num">{{ summary.offline }}</b></span>
      <span class="chip bad">故障 <b class="num">{{ summary.fault }}</b></span>
      <span v-if="summary.unknown" class="chip warn">未知 <b class="num">{{ summary.unknown }}</b></span>
      <span class="chip">摄像头 <b class="num">{{ summary.cameras }}</b></span>
      <span class="grow" />
      <el-button v-if="canVideo" type="primary" plain size="small" :disabled="!keyCameras.length" @click="wall = !wall">{{ wall ? '收起视频墙' : '重点点位视频墙' }}</el-button>
    </div>

    <div v-if="wall" class="wall">
      <VideoPlayer v-for="d in keyCameras" :key="d.id" :device="d" />
    </div>

    <div class="layout">
      <div class="sw-card">
        <div class="sw-card-title">
          <span>点位分布</span>
          <span class="legend"><i class="g" />在线<i class="o" />离线<i class="r" />故障<i class="y" />未知</span>
        </div>
        <SiteMap :sites="site.data.value ? [site.data.value] : []" :zones="zones.data.value ?? []" :devices="devices" :focus-site-id="siteId" :selected-device-id="selectedId" height="480px" @select-device="pick" />
      </div>

      <div class="sw-card">
        <div class="sw-card-title">
          <span>设备列表</span>
          <span class="filters">
            <el-select v-model="filter.type" placeholder="类型" clearable size="small" style="width: 120px">
              <el-option v-for="(n, k) in TYPE_LABELS" :key="k" :label="n" :value="k" />
            </el-select>
            <el-select v-model="filter.status" placeholder="状态" clearable size="small" style="width: 96px">
              <el-option label="在线" value="ONLINE" /><el-option label="离线" value="OFFLINE" /><el-option label="故障" value="FAULT" /><el-option label="未知" value="UNKNOWN" />
            </el-select>
            <el-checkbox v-model="filter.keyPoint" size="small">仅重点点位</el-checkbox>
          </span>
        </div>
        <StatePanel :loading="all.loading.value" :error="all.error.value" :empty="!devices.length" empty-text="没有符合条件的设备" @retry="all.run">
          <el-table :data="devices" size="small" max-height="480" highlight-current-row @row-click="(r: Device) => (selectedId = r.id)">
            <el-table-column label="名称" min-width="150">
              <template #default="{ row }">
                <span>{{ row.name }}</span>
                <el-tag v-if="row.keyPoint" size="small" type="warning" effect="plain" style="margin-left: 4px">重点</el-tag>
                <el-tag v-if="row.dataMode === 'DEMO'" size="small" type="info" effect="plain" style="margin-left: 4px">演示</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100"><template #default="{ row }">{{ TYPE_LABELS[row.deviceType as DeviceType] }}</template></el-table-column>
            <el-table-column prop="zoneName" label="所属区域" min-width="100"><template #default="{ row }">{{ row.zoneName ?? '—' }}</template></el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }"><el-tag size="small" :type="STATUS_TAG[row.status as DeviceStatus]">{{ label(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="状态时间" width="140"><template #default="{ row }">{{ fmtDateTime(row.statusAt) }}</template></el-table-column>
            <el-table-column v-if="canVideo" label="" width="64" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.deviceType === 'CAMERA'" link type="primary" size="small" @click.stop="playing = row">播放</el-button>
              </template>
            </el-table-column>
          </el-table>
        </StatePanel>
      </div>
    </div>

    <el-dialog :model-value="!!playing" :title="playing?.name" width="min(760px, 96vw)" destroy-on-close @close="playing = null">
      <VideoPlayer v-if="playing" :device="playing" />
      <div v-if="playing" class="sw-muted" style="margin-top: 8px">
        编号 {{ playing.code }} · {{ playing.vendor ?? '厂商未登记' }} · {{ playing.protocol ?? '协议未登记' }} · 位置 {{ playing.lon.toFixed(5) }}, {{ playing.lat.toFixed(5) }}
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.chips { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.chip { background: #fff; border: 1px solid var(--sw-border); border-radius: 16px; padding: 4px 12px; font-size: 13px; color: var(--sw-text-2); }
.chip b { margin-left: 4px; color: var(--sw-text-1); }
.chip.ok b { color: var(--sw-ok); }
.chip.off b { color: var(--sw-text-3); }
.chip.bad b { color: var(--sw-danger); }
.chip.warn b { color: #b8740a; }
.grow { flex: 1; }
.wall { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 320px), 1fr)); gap: 12px; }
.layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.1fr); gap: 16px; align-items: start; }
.legend { font-size: 12px; font-weight: 400; color: var(--sw-text-3); display: flex; align-items: center; gap: 4px; }
.legend i { width: 9px; height: 9px; border-radius: 50%; margin-left: 8px; }
.legend .g { background: #2fa86b; } .legend .o { background: #94a3b8; } .legend .r { background: #e5484d; } .legend .y { background: #e6a23c; }
.filters { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-weight: 400; }
@media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } }
</style>
