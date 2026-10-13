<script setup lang="ts">
import { ref } from 'vue'
import { mobileApi, type FileBizType, type FileRef } from '@/api/mobile'
import { errorText } from '@/api/http'
import { downscaleImage } from './mobileUtils'

/**
 * 拍照/选图上传: 先在浏览器端压缩为 ≤1600px JPEG 再上传; 上传失败保留本地图片, 可单张重试。
 * v-model 为已上传成功的文件列表。
 */
const props = defineProps<{ modelValue: FileRef[]; siteId: number; bizType: FileBizType; bizId?: number | null; disabled?: boolean; max?: number }>()
const emit = defineEmits<{ 'update:modelValue': [files: FileRef[]]; busy: [busy: boolean] }>()

interface Pending {
  key: number
  blob: Blob
  name: string
  preview: string
  progress: number
  error: string | null
}

const pending = ref<Pending[]>([])
const input = ref<HTMLInputElement>()
const pickError = ref<string | null>(null)
let nextKey = 1

function reportBusy() {
  emit('busy', pending.value.some((p) => !p.error))
}

async function send(p: Pending) {
  p.error = null
  p.progress = 0
  reportBusy()
  try {
    const ref = await mobileApi.uploadPhoto(p.blob, p.name, props.siteId, props.bizType, props.bizId ?? null, (v) => (p.progress = v))
    URL.revokeObjectURL(p.preview)
    pending.value = pending.value.filter((x) => x.key !== p.key)
    emit('update:modelValue', [...props.modelValue, ref])
  } catch (e) {
    p.error = errorText(e)
  }
  reportBusy()
}

async function onPick(ev: Event) {
  const el = ev.target as HTMLInputElement
  const files = Array.from(el.files ?? [])
  el.value = ''
  pickError.value = null
  for (const f of files) {
    if ((props.max ?? 6) <= props.modelValue.length + pending.value.length) {
      pickError.value = `最多上传 ${props.max ?? 6} 张照片`
      break
    }
    try {
      const blob = await downscaleImage(f)
      const p: Pending = { key: nextKey++, blob, name: f.name.replace(/\.[^.]+$/, '') + '.jpg', preview: URL.createObjectURL(blob), progress: 0, error: null }
      pending.value.push(p)
      // 取响应式代理, 进度更新才能反映到界面
      void send(pending.value[pending.value.length - 1])
    } catch (e) {
      pickError.value = errorText(e)
    }
  }
}

function discard(p: Pending) {
  URL.revokeObjectURL(p.preview)
  pending.value = pending.value.filter((x) => x.key !== p.key)
  reportBusy()
}

function remove(id: number) {
  emit('update:modelValue', props.modelValue.filter((f) => f.id !== id))
}
</script>

<template>
  <div class="up">
    <div class="grid">
      <div v-for="f in modelValue" :key="f.id" class="ph">
        <img :src="f.url" :alt="f.name" loading="lazy" />
        <button v-if="!disabled" type="button" class="x" aria-label="移除照片" @click="remove(f.id)">×</button>
      </div>
      <div v-for="p in pending" :key="'p' + p.key" class="ph" :class="{ failed: p.error }">
        <img :src="p.preview" alt="待上传照片" />
        <div v-if="p.error" class="mask">
          <span>上传失败</span>
          <button type="button" @click="send(p)">重试</button>
          <button type="button" class="ghost" @click="discard(p)">放弃</button>
        </div>
        <div v-else class="mask"><span>{{ p.progress }}%</span></div>
      </div>
      <button v-if="!disabled" type="button" class="add" @click="input?.click()">
        <b>＋</b><span>拍照</span>
      </button>
    </div>
    <input ref="input" type="file" accept="image/*" capture="environment" hidden @change="onPick" />
    <p v-for="p in pending.filter((x) => x.error)" :key="'e' + p.key" class="err">{{ p.error }}</p>
    <p v-if="pickError" class="err">{{ pickError }}</p>
  </div>
</template>

<style scoped>
.grid { display: flex; flex-wrap: wrap; gap: 8px; }
.ph, .add { position: relative; width: 72px; height: 72px; border-radius: 8px; overflow: hidden; }
.ph img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ph.failed { outline: 2px solid #e5484d; }
.x { position: absolute; right: 2px; top: 2px; width: 20px; height: 20px; border-radius: 50%; border: none; background: rgba(0, 0, 0, 0.6); color: #fff; line-height: 18px; padding: 0; }
.mask { position: absolute; inset: 0; background: rgba(0, 0, 0, 0.55); color: #fff; font-size: 12px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; }
.mask button { font-size: 12px; border: none; border-radius: 4px; padding: 1px 10px; background: #1f6feb; color: #fff; }
.mask button.ghost { background: transparent; text-decoration: underline; padding: 0; }
.add { border: 1px dashed #b6c2d2; background: #f7f9fc; color: #6b7a90; display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: 12px; }
.add b { font-size: 22px; line-height: 22px; font-weight: 400; }
.err { margin: 6px 0 0; font-size: 12px; color: #d03050; }
</style>
