<script setup lang="ts">
import { ref, watch } from 'vue'
import { errorText } from '@/api/http'
import type { ImportResult } from '@/api/types'

/** CSV 导入对话框: 展示列说明、上传进度、导入结果与逐行错误。 */
const props = defineProps<{
  modelValue: boolean
  title: string
  header: string
  notes: string[]
  uploader: (file: File, onProgress: (p: number) => void) => Promise<ImportResult>
}>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; done: [r: ImportResult] }>()

const file = ref<File | null>(null)
const busy = ref(false)
const percent = ref(0)
const result = ref<ImportResult | null>(null)
const error = ref('')

watch(() => props.modelValue, (v) => {
  if (v) {
    file.value = null
    result.value = null
    error.value = ''
    percent.value = 0
  }
})

function pick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0] ?? null
  file.value = f
  result.value = null
  error.value = ''
}

async function submit() {
  if (!file.value) return
  busy.value = true
  error.value = ''
  try {
    result.value = await props.uploader(file.value, (p) => (percent.value = p))
    emit('done', result.value)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title" width="min(620px, 94vw)" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <div class="doc">
      <div class="sw-muted">CSV 表头（UTF-8 编码）</div>
      <code class="hdr">{{ header }}</code>
      <ul>
        <li v-for="n in notes" :key="n">{{ n }}</li>
      </ul>
    </div>
    <input type="file" accept=".csv,text/csv" :disabled="busy" @change="pick" />
    <el-progress v-if="busy" :percentage="percent" style="margin-top: 10px" />
    <div v-if="error" class="err">导入失败：{{ error }}</div>
    <div v-if="result" class="res">
      <div class="nums num">
        <span>总行数 <b>{{ result.total }}</b></span>
        <span class="ok">新增 <b>{{ result.inserted }}</b></span>
        <span>重复跳过 <b>{{ result.duplicates }}</b></span>
        <span :class="{ bad: result.errors.length }">错误 <b>{{ result.errors.length }}</b></span>
      </div>
      <div class="sw-muted">批次号 {{ result.batchId }}；重复的来源记录不会重复计数。</div>
      <el-table v-if="result.errors.length" :data="result.errors" size="small" max-height="220" style="margin-top: 8px">
        <el-table-column prop="line" label="行号" width="80" />
        <el-table-column prop="message" label="错误原因" />
      </el-table>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :disabled="!file" :loading="busy" @click="submit">开始导入</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.doc { background: var(--sw-bg); border-radius: 6px; padding: 10px 12px; margin-bottom: 12px; font-size: 13px; }
.hdr { display: block; margin: 4px 0; word-break: break-all; color: var(--sw-primary-deep); }
ul { margin: 6px 0 0; padding-left: 18px; color: var(--sw-text-2); }
.err { color: var(--sw-danger); margin-top: 10px; font-size: 13px; }
.res { margin-top: 12px; }
.nums { display: flex; gap: 16px; flex-wrap: wrap; font-size: 14px; }
.ok b { color: var(--sw-ok); }
.bad b { color: var(--sw-danger); }
</style>
