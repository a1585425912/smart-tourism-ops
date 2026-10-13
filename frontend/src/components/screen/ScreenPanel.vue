<script setup lang="ts">
import { computed } from 'vue'
import { ApiError, errorText } from '@/api/http'

/** 大屏玻璃面板: 标题、数据模式角标、独立的加载/失败/无数据状态。 */
const props = defineProps<{
  title: string
  sub?: string
  mode?: 'REAL' | 'DEMO' | 'MIXED' | null
  loading?: boolean
  error?: unknown
  empty?: boolean
  emptyText?: string
}>()
const emit = defineEmits<{ retry: [] }>()

const errorMsg = computed(() => {
  if (!props.error) return ''
  if (props.error instanceof ApiError && props.error.isForbidden) return '无权访问该数据'
  return errorText(props.error)
})
</script>

<template>
  <section class="sp">
    <header class="sp-head">
      <i class="sp-mark" />
      <h3>{{ title }}</h3>
      <span v-if="sub" class="sp-sub">{{ sub }}</span>
      <span class="sp-gap" />
      <slot name="extra" />
      <span v-if="mode && mode !== 'REAL'" class="sp-demo">{{ mode === 'MIXED' ? '含演示数据' : '演示数据' }}</span>
    </header>
    <div class="sp-body">
      <div v-if="loading" class="sp-state"><span class="sp-spin" />加载中</div>
      <div v-else-if="error" class="sp-state err">
        <span>{{ errorMsg }}</span>
        <button type="button" @click="emit('retry')">重试</button>
      </div>
      <div v-else-if="empty" class="sp-state">{{ emptyText ?? '暂无数据' }}</div>
      <slot v-else />
    </div>
  </section>
</template>

<style scoped>
.sp {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: linear-gradient(160deg, rgba(20, 52, 92, 0.55), rgba(8, 22, 44, 0.72));
  border: 1px solid rgba(94, 200, 242, 0.22);
  border-radius: 6px;
  box-shadow: inset 0 0 24px rgba(46, 197, 255, 0.06), 0 6px 24px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(6px);
  overflow: hidden;
}
.sp::before {
  content: '';
  position: absolute;
  inset: 0 auto auto 0;
  width: 46px;
  height: 2px;
  background: linear-gradient(90deg, #5ef0ff, transparent);
}
.sp::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: 0;
  width: 46px;
  height: 2px;
  background: linear-gradient(270deg, rgba(94, 240, 255, 0.7), transparent);
}
.sp-head {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 14px;
  border-bottom: 1px solid rgba(94, 200, 242, 0.12);
  background: linear-gradient(90deg, rgba(46, 197, 255, 0.12), transparent 70%);
}
.sp-mark {
  width: 4px;
  height: 14px;
  border-radius: 2px;
  background: linear-gradient(#5ef0ff, #2e7dff);
  box-shadow: 0 0 8px #5ef0ff;
}
h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
  color: #e6f6ff;
  white-space: nowrap;
}
.sp-sub {
  font-size: 12px;
  color: #7fa6c9;
  white-space: nowrap;
}
.sp-gap {
  flex: 1;
}
.sp-demo {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  color: #ffcf7a;
  border: 1px solid rgba(255, 181, 71, 0.55);
  background: rgba(255, 181, 71, 0.12);
  white-space: nowrap;
}
.sp-body {
  position: relative;
  flex: 1;
  min-height: 0;
  padding: 10px 14px;
}
.sp-state {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #6f8fb0;
  font-size: 13px;
}
.sp-state.err {
  color: #ff8a9b;
  text-align: center;
}
.sp-state button {
  background: transparent;
  color: #9fdcff;
  border: 1px solid rgba(94, 200, 242, 0.5);
  border-radius: 3px;
  padding: 2px 14px;
  cursor: pointer;
}
.sp-spin {
  width: 16px;
  height: 16px;
  border: 2px solid #5ec8f2;
  border-right-color: transparent;
  border-radius: 50%;
  animation: sp-rot 0.8s linear infinite;
}
@keyframes sp-rot {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .sp-spin {
    animation: none;
  }
}
</style>
