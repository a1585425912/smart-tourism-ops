<script setup lang="ts">
import { computed } from 'vue'
import { TOOL_LABELS, type RunStep } from '@/api/agent'
import { label } from '@/utils/format'

/** Agent 任务进度: 状态 + 工具调用步骤时间线。只显示工具摘要, 不显示敏感参数。 */
const props = defineProps<{
  status: string | null
  progress?: string | null
  steps: RunStep[]
  errorMessage?: string | null
  compact?: boolean
}>()

const running = computed(() => props.status === 'QUEUED' || props.status === 'RUNNING')
const STEP_STATUS: Record<string, string> = { OK: '完成', ERROR: '出错', DENIED: '无权限', INVALID: '参数无效' }
</script>

<template>
  <div class="run" :class="{ compact }">
    <div class="head">
      <span class="dot" :class="status?.toLowerCase()" />
      <span class="st">{{ label(status) }}</span>
      <span v-if="running && progress" class="pg">{{ progress }}</span>
      <span v-if="running" class="spin" />
    </div>
    <ol v-if="steps.length" class="steps">
      <li v-for="s in steps" :key="s.seq" :class="s.status.toLowerCase()">
        <div class="line">
          <span class="tool">{{ TOOL_LABELS[s.toolName] ?? s.toolName }}</span>
          <span class="tag">{{ STEP_STATUS[s.status] ?? s.status }}</span>
          <span v-if="s.durationMs != null" class="ms num">{{ s.durationMs }} ms</span>
        </div>
        <div v-if="s.resultSummary" class="sum">{{ s.resultSummary }}</div>
      </li>
    </ol>
    <div v-if="status === 'FAILED' && errorMessage" class="err">{{ errorMessage }}</div>
  </div>
</template>

<style scoped>
.run { font-size: 13px; }
.head { display: flex; align-items: center; gap: 8px; color: var(--sw-text-2); }
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--sw-text-3); }
.dot.running, .dot.queued { background: var(--sw-primary); }
.dot.succeeded { background: var(--sw-ok); }
.dot.failed { background: var(--sw-danger); }
.dot.cancelled { background: var(--sw-warn); }
.st { font-weight: 600; }
.pg { color: var(--sw-text-3); }
.spin { width: 12px; height: 12px; border: 2px solid var(--sw-primary); border-right-color: transparent; border-radius: 50%; animation: sp 0.8s linear infinite; }
@keyframes sp { to { transform: rotate(360deg); } }
.steps { list-style: none; margin: 8px 0 0; padding: 0 0 0 4px; }
.steps li { position: relative; padding: 0 0 10px 16px; border-left: 2px solid var(--sw-border); }
.steps li:last-child { padding-bottom: 2px; }
.steps li::before { content: ''; position: absolute; left: -5px; top: 5px; width: 8px; height: 8px; border-radius: 50%; background: var(--sw-ok); }
.steps li.error::before, .steps li.invalid::before { background: var(--sw-danger); }
.steps li.denied::before { background: var(--sw-warn); }
.line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tool { font-weight: 500; color: var(--sw-text-1); }
.tag { font-size: 12px; color: var(--sw-ok); }
li.error .tag, li.invalid .tag { color: var(--sw-danger); }
li.denied .tag { color: #b8740a; }
.ms { font-size: 12px; color: var(--sw-text-3); }
.sum { color: var(--sw-text-3); font-size: 12px; margin-top: 2px; word-break: break-all; }
.err { margin-top: 8px; color: var(--sw-danger); }
</style>
