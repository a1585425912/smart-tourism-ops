<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { screenApi } from '@/api/screen'
import ScreenPanel from './ScreenPanel.vue'
import VideoTile from './VideoTile.vue'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'

/** 重点点位监控 2×2; 超过 4 路时可翻页。设备列表随刷新更新, 但已打开的画面不重复申请会话。 */
const ctx = useScreen()
const { data, loading, error, reload } = usePanel(() => screenApi.keyCameras(ctx.selectedSiteId.value ?? undefined), [ctx.selectedSiteId])
watch(ctx.tick, reload, { immediate: true })

const page = ref(0)
watch(ctx.selectedSiteId, () => (page.value = 0))
const pages = computed(() => Math.max(1, Math.ceil((data.value?.length ?? 0) / 4)))
const shown = computed(() => (data.value ?? []).slice(page.value * 4, page.value * 4 + 4))
const online = computed(() => (data.value ?? []).filter((d) => d.status === 'ONLINE').length)
const mode = computed(() => ((data.value ?? []).some((d) => d.dataMode === 'DEMO') ? ('DEMO' as const) : data.value?.length ? ('REAL' as const) : null))
</script>

<template>
  <ScreenPanel title="重点点位监控" :sub="data ? `在线 ${online} / ${data.length}` : ''" :mode="mode" :loading="loading" :error="data ? null : error"
    :empty="!data?.length" empty-text="暂无重点视频点位" @retry="reload">
    <template #extra>
      <span v-if="pages > 1" class="pager">
        <button type="button" :disabled="page === 0" @click="page--">‹</button>
        {{ page + 1 }}/{{ pages }}
        <button type="button" :disabled="page >= pages - 1" @click="page++">›</button>
      </span>
    </template>
    <div class="wall">
      <div v-for="d in shown" :key="d.id" class="cell" title="点击放大" @click="ctx.focusDeviceId.value = d.id">
        <VideoTile :device="d" />
      </div>
    </div>
  </ScreenPanel>
</template>

<style scoped>
.wall { height: 100%; display: grid; grid-template-columns: 1fr 1fr; grid-template-rows: 1fr 1fr; gap: 8px; }
.cell { min-height: 0; cursor: zoom-in; }
.pager { font-size: 12px; color: #9fc3e0; display: inline-flex; align-items: center; gap: 4px; }
.pager button { background: transparent; border: 1px solid rgba(94, 200, 242, 0.4); color: #9fdcff; border-radius: 3px; width: 20px; height: 20px; line-height: 16px; cursor: pointer; padding: 0; }
.pager button:disabled { opacity: 0.35; cursor: default; }
</style>
