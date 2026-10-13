<script setup lang="ts">
import { computed } from 'vue'
import { ApiError, errorText } from '@/api/http'

/**
 * 统一的加载/空/失败/无权限状态容器。所有数据面板都用它包裹内容。
 * 有数据时渲染默认插槽; 否则渲染对应状态并提供重试。
 */
const props = defineProps<{
  loading?: boolean
  error?: unknown
  empty?: boolean
  emptyText?: string
  minHeight?: string
}>()
const emit = defineEmits<{ retry: [] }>()

const forbidden = computed(() => props.error instanceof ApiError && props.error.isForbidden)
</script>

<template>
  <div class="state-panel" :style="{ minHeight: minHeight ?? '120px' }">
    <div v-if="loading && !error" class="state loading">
      <span class="spinner" />加载中…
    </div>
    <div v-else-if="forbidden" class="state forbidden">无权访问该数据</div>
    <div v-else-if="error" class="state error">
      <div>加载失败：{{ errorText(error) }}</div>
      <button class="retry" type="button" @click="emit('retry')">重试</button>
    </div>
    <div v-else-if="empty" class="state empty">{{ emptyText ?? '暂无数据' }}</div>
    <slot v-else />
  </div>
</template>

<style scoped>
.state-panel { position: relative; display: flex; flex-direction: column; }
.state { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
  color: var(--sw-text-3); font-size: 13px; padding: 16px; text-align: center; }
.state.error { color: #d03050; }
.state.forbidden { color: #c27c0e; }
.retry { border: 1px solid currentColor; background: transparent; color: inherit; border-radius: 4px; padding: 2px 12px; cursor: pointer; }
.spinner { display: inline-block; width: 14px; height: 14px; margin-right: 6px; border: 2px solid currentColor; border-right-color: transparent;
  border-radius: 50%; animation: spin 0.8s linear infinite; vertical-align: -2px; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
