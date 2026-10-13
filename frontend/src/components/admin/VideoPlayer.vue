<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { scenicApi } from '@/api/core'
import { ApiError, errorText } from '@/api/http'
import type { Device, PlaybackSession } from '@/api/types'
import { useAppStore } from '@/stores/app'
import { fmtDateTime } from '@/utils/format'

/**
 * 视频播放器。向后端申请短期播放会话, 并区分: 无权限 / 设备离线 / 不兼容 / 连接失败。
 * DEMO_SYNTHETIC 时绘制带明显水印的模拟画面, 不冒充实时视频。
 */
const props = defineProps<{ device: Device }>()
const app = useAppStore()

type State = 'loading' | 'playing' | 'forbidden' | 'offline' | 'incompatible' | 'failed'
const state = ref<State>('loading')
const message = ref('')
const session = ref<PlaybackSession | null>(null)
const canvas = ref<HTMLCanvasElement>()
let raf = 0

async function open() {
  cancelAnimationFrame(raf)
  state.value = 'loading'
  message.value = ''
  session.value = null
  try {
    const s = await scenicApi.playback(props.device.id)
    session.value = s
    message.value = s.message
    if (s.status === 'OFFLINE') state.value = 'offline'
    else if (s.status === 'INCOMPATIBLE') state.value = 'incompatible'
    else if (s.type === 'DEMO_SYNTHETIC' || (s.type === 'DEMO_FILE' && s.url)) {
      state.value = 'playing'
      if (s.type === 'DEMO_SYNTHETIC') requestAnimationFrame(startSynthetic)
    } else {
      // HLS 等真实流需接入视频平台播放组件后支持
      state.value = 'incompatible'
      message.value = `暂不支持的播放类型：${s.type ?? '未知'}`
    }
  } catch (e) {
    if (e instanceof ApiError && e.isForbidden) {
      state.value = 'forbidden'
      message.value = e.message
    } else {
      state.value = 'failed'
      message.value = errorText(e)
    }
  }
}

function onVideoError() {
  state.value = 'failed'
  message.value = '视频加载失败，播放会话可能已过期'
}

/** 模拟画面: 渐变夜景 + 随机游走的人群点 + 时间戳 + 水印。纯装饰, 不代表真实人数。 */
function startSynthetic() {
  const cv = canvas.value
  if (!cv) return
  const ctx = cv.getContext('2d')
  if (!ctx) return
  const W = (cv.width = 640)
  const H = (cv.height = 360)
  let seed = props.device.id * 9973 + 7
  const rnd = () => ((seed = (seed * 1664525 + 1013904223) % 4294967296) / 4294967296)
  const people = Array.from({ length: 46 }, () => ({ x: rnd() * W, y: H * 0.5 + rnd() * H * 0.45, vx: (rnd() - 0.5) * 0.6, vy: (rnd() - 0.5) * 0.15, h: 200 + rnd() * 40 }))
  const lights = Array.from({ length: 9 }, (_, i) => ({ x: 30 + i * 72 + rnd() * 20, w: 26 + rnd() * 30, h: 60 + rnd() * 90, hue: 180 + rnd() * 120 }))

  const draw = () => {
    const sky = ctx.createLinearGradient(0, 0, 0, H)
    sky.addColorStop(0, '#07142e')
    sky.addColorStop(0.55, '#123a6b')
    sky.addColorStop(1, '#c7dff2')
    ctx.fillStyle = sky
    ctx.fillRect(0, 0, W, H)
    const t = Date.now() / 1000
    for (const l of lights) {
      ctx.fillStyle = `hsla(${(l.hue + t * 12) % 360}, 80%, 65%, 0.55)`
      ctx.fillRect(l.x, H * 0.52 - l.h, l.w, l.h)
      ctx.fillStyle = `hsla(${(l.hue + t * 12) % 360}, 80%, 80%, 0.5)`
      ctx.beginPath()
      ctx.moveTo(l.x, H * 0.52 - l.h)
      ctx.lineTo(l.x + l.w / 2, H * 0.52 - l.h - 18)
      ctx.lineTo(l.x + l.w, H * 0.52 - l.h)
      ctx.fill()
    }
    ctx.fillStyle = 'rgba(235,245,255,0.85)'
    ctx.fillRect(0, H * 0.52, W, H * 0.48)
    people.sort((a, b) => a.y - b.y)
    for (const p of people) {
      p.x += p.vx
      p.y += p.vy
      if (p.x < -10) p.x = W + 10
      if (p.x > W + 10) p.x = -10
      if (p.y < H * 0.5 || p.y > H * 0.97) p.vy = -p.vy
      const s = 0.5 + ((p.y - H * 0.5) / (H * 0.5)) * 1.1
      ctx.fillStyle = `hsl(${p.h}, 35%, 28%)`
      ctx.fillRect(p.x - 3 * s, p.y - 14 * s, 6 * s, 14 * s)
      ctx.beginPath()
      ctx.arc(p.x, p.y - 17 * s, 3.2 * s, 0, Math.PI * 2)
      ctx.fill()
    }
    ctx.fillStyle = 'rgba(0,0,0,0.45)'
    ctx.fillRect(0, 0, W, 26)
    ctx.fillStyle = '#fff'
    ctx.font = '13px monospace'
    ctx.textAlign = 'left'
    ctx.fillText(`${props.device.code}  ${app.now().format('YYYY-MM-DD HH:mm:ss')}`, 10, 18)
    ctx.save()
    ctx.translate(W / 2, H / 2)
    ctx.rotate(-0.18)
    ctx.textAlign = 'center'
    ctx.font = 'bold 34px sans-serif'
    ctx.fillStyle = 'rgba(255,255,255,0.5)'
    ctx.strokeStyle = 'rgba(0,0,0,0.35)'
    ctx.lineWidth = 1
    ctx.fillText('演示画面 · 非实时视频', 0, 0)
    ctx.strokeText('演示画面 · 非实时视频', 0, 0)
    ctx.restore()
    raf = requestAnimationFrame(draw)
  }
  draw()
}

onMounted(open)
watch(() => props.device.id, open)
onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<template>
  <div class="vp">
    <div class="stage">
      <div v-if="state === 'loading'" class="ov">正在申请播放会话…</div>
      <template v-else-if="state === 'playing' && session">
        <canvas v-if="session.type === 'DEMO_SYNTHETIC'" ref="canvas" />
        <template v-else>
          <video :src="session.url ?? undefined" autoplay muted loop controls playsinline @error="onVideoError" />
          <div class="wm">演示视频 · 非该点位实时画面</div>
        </template>
      </template>
      <div v-else class="ov" :class="state">
        <div class="big">
          {{ state === 'forbidden' ? '无权查看该设备视频' : state === 'offline' ? '设备离线' : state === 'incompatible' ? '无法播放' : '连接失败' }}
        </div>
        <div class="msg">{{ message }}</div>
        <button v-if="state === 'failed' || state === 'offline'" type="button" @click="open">重试</button>
      </div>
    </div>
    <div class="bar">
      <span class="name">{{ device.name }}</span>
      <span v-if="session?.dataMode === 'DEMO' || device.dataMode === 'DEMO'" class="demo">演示</span>
      <span v-if="state === 'playing'" class="hint">{{ message }}</span>
      <span v-if="session?.expiresAt && state === 'playing'" class="hint">会话有效至 {{ fmtDateTime(session.expiresAt) }}</span>
      <span v-if="session?.attribution" class="hint">素材：{{ session.attribution }}</span>
    </div>
  </div>
</template>

<style scoped>
.vp { background: #0b1220; border-radius: 8px; overflow: hidden; color: #cbd5e1; }
.stage { position: relative; aspect-ratio: 16 / 9; background: #000; }
canvas, video { width: 100%; height: 100%; display: block; object-fit: cover; }
.wm { position: absolute; top: 8px; right: 8px; background: rgba(230, 162, 60, 0.9); color: #1c2533; font-size: 12px; padding: 2px 8px; border-radius: 3px; pointer-events: none; }
.ov { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 16px; text-align: center; font-size: 13px; }
.ov .big { font-size: 18px; color: #fff; }
.ov.forbidden .big { color: #f0b657; }
.ov.offline .big { color: #94a3b8; }
.ov.failed .big, .ov.incompatible .big { color: #ff8589; }
.ov button { border: 1px solid #64748b; background: transparent; color: #e2e8f0; border-radius: 4px; padding: 3px 14px; cursor: pointer; }
.bar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 10px; font-size: 12px; }
.name { color: #fff; font-size: 13px; }
.demo { background: rgba(230, 162, 60, 0.2); color: #f0b657; padding: 0 6px; border-radius: 3px; }
.hint { color: #8492a6; }
</style>
