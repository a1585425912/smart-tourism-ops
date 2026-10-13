<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch, nextTick } from 'vue'
import { screenApi } from '@/api/screen'
import { ApiError, errorText } from '@/api/http'
import type { Device, PlaybackSession } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { SyntheticFeed } from './syntheticFeed'
import { prefersReducedMotion } from './screenUtils'

/**
 * 单路视频: 申请短期播放会话后渲染。
 * 明确区分: 无权限 / 设备离线 / 不兼容 / 连接失败; 演示画面始终带水印。
 */
const props = defineProps<{ device: Device; large?: boolean }>()
const app = useAppStore()

type State = 'loading' | 'synthetic' | 'file' | 'forbidden' | 'offline' | 'incompatible' | 'error'
const state = ref<State>('loading')
const message = ref('')
const session = ref<PlaybackSession | null>(null)
const canvas = ref<HTMLCanvasElement>()
let feed: SyntheticFeed | null = null
let seq = 0

const STATE_TEXT: Record<string, { icon: string; title: string }> = {
  forbidden: { icon: '🔒', title: '无权限查看' },
  offline: { icon: '⏻', title: '设备离线' },
  incompatible: { icon: '⚠', title: '编码/协议不兼容' },
  error: { icon: '✕', title: '连接失败' },
}
const info = computed(() => STATE_TEXT[state.value])

async function open() {
  const mine = ++seq
  feed?.stop()
  feed = null
  state.value = 'loading'
  message.value = ''
  session.value = null
  try {
    const s = await screenApi.playback(props.device.id)
    if (mine !== seq) return
    session.value = s
    message.value = s.message
    if (s.status === 'OFFLINE') state.value = 'offline'
    else if (s.status === 'INCOMPATIBLE') state.value = 'incompatible'
    else if (s.type === 'DEMO_SYNTHETIC') {
      state.value = 'synthetic'
      await nextTick()
      startFeed()
    } else if (s.type === 'DEMO_FILE' && s.url) state.value = 'file'
    else if (s.type === 'HLS' && s.url && document.createElement('video').canPlayType('application/vnd.apple.mpegurl')) state.value = 'file'
    else {
      state.value = 'incompatible'
      message.value = '当前浏览器无法播放该视频流格式'
    }
  } catch (e) {
    if (mine !== seq) return
    if (e instanceof ApiError && e.isForbidden) {
      state.value = 'forbidden'
      message.value = e.message
    } else {
      state.value = 'error'
      message.value = errorText(e)
    }
  }
}

function startFeed() {
  if (!canvas.value) return
  canvas.value.width = props.large ? 960 : 480
  canvas.value.height = props.large ? 540 : 270
  feed = new SyntheticFeed(canvas.value, {
    seed: props.device.id,
    title: props.device.name,
    clock: () => app.now().format('YYYY-MM-DD HH:mm:ss'),
    density: ((props.device.id * 37) % 100) / 100,
    animate: !prefersReducedMotion(),
  })
  feed.start()
}

function onVideoError() {
  state.value = 'error'
  message.value = '视频加载失败，播放会话可能已过期'
}

watch(() => props.device.id, open, { immediate: true })
onBeforeUnmount(() => {
  seq++
  feed?.stop()
})
</script>

<template>
  <div class="tile" :class="[state, { large }]">
    <canvas v-if="state === 'synthetic'" ref="canvas" />
    <template v-else-if="state === 'file' && session?.url">
      <video :src="session.url" autoplay muted loop playsinline @error="onVideoError" />
      <div class="wm">演示画面 · 非该点位实时视频</div>
      <div v-if="session.attribution" class="credit">{{ session.attribution }}</div>
    </template>
    <div v-else-if="state === 'loading'" class="msg"><span class="spin" />正在申请播放会话</div>
    <div v-else class="msg">
      <span class="icon">{{ info?.icon }}</span>
      <b>{{ info?.title }}</b>
      <small>{{ message }}</small>
      <button type="button" @click="open">重试</button>
    </div>
    <div class="bar">
      <i :class="device.status === 'ONLINE' ? 'on' : 'off'" />
      <span class="name">{{ device.name }}</span>
      <span v-if="device.zoneName" class="zone">{{ device.zoneName }}</span>
      <span v-if="device.dataMode === 'DEMO'" class="demo">演示</span>
    </div>
  </div>
</template>

<style scoped>
.tile { position: relative; width: 100%; height: 100%; background: #030a16; border: 1px solid rgba(94, 200, 242, 0.25); border-radius: 4px; overflow: hidden; }
.tile.offline { border-color: rgba(107, 122, 144, 0.6); background: repeating-linear-gradient(135deg, #0a121f 0 8px, #0d1726 8px 16px); }
.tile.forbidden { border-color: rgba(255, 181, 71, 0.6); }
.tile.error, .tile.incompatible { border-color: rgba(255, 77, 109, 0.6); }
canvas, video { width: 100%; height: 100%; object-fit: cover; display: block; }
.msg { height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; padding: 6px 10px 26px; text-align: center; color: #7fa6c9; font-size: 12px; }
.msg .icon { font-size: 22px; line-height: 1; }
.msg b { font-size: 14px; }
.offline .msg b { color: #9aa9bd; }
.forbidden .msg b { color: #ffb547; }
.error .msg b, .incompatible .msg b { color: #ff8a9b; }
.msg small { max-height: 30px; overflow: hidden; color: #6f8fb0; }
.msg button { margin-top: 2px; background: transparent; color: #9fdcff; border: 1px solid rgba(94, 200, 242, 0.5); border-radius: 3px; padding: 1px 12px; cursor: pointer; font-size: 12px; }
.spin { width: 16px; height: 16px; border: 2px solid #5ec8f2; border-right-color: transparent; border-radius: 50%; animation: vt-rot 0.8s linear infinite; }
@keyframes vt-rot { to { transform: rotate(360deg); } }
.wm { position: absolute; left: 0; right: 0; bottom: 30px; text-align: center; color: rgba(255, 200, 110, 0.85); font-weight: 700; font-size: 14px; text-shadow: 0 0 4px #000; pointer-events: none; }
.large .wm { font-size: 22px; bottom: 44px; }
.credit { position: absolute; right: 6px; top: 4px; font-size: 10px; color: rgba(255, 255, 255, 0.6); text-shadow: 0 0 3px #000; }
.bar { position: absolute; left: 0; right: 0; bottom: 0; height: 24px; display: flex; align-items: center; gap: 6px; padding: 0 8px; font-size: 12px; color: #d8f1ff;
  background: linear-gradient(transparent, rgba(3, 10, 22, 0.92)); }
.bar i { width: 7px; height: 7px; border-radius: 50%; }
.bar i.on { background: #36e3a0; box-shadow: 0 0 6px #36e3a0; }
.bar i.off { background: #6b7a90; }
.bar .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bar .zone { color: #7fa6c9; white-space: nowrap; }
.bar .demo { color: #ffcf7a; border: 1px solid rgba(255, 181, 71, 0.55); border-radius: 2px; padding: 0 3px; font-size: 10px; line-height: 14px; }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
