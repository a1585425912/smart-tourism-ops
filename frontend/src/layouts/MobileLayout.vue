<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const app = useAppStore()

const isRoot = computed(() => route.path === '/m' || route.path === '/m/')

async function logout() {
  await app.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="m-root">
    <header class="m-bar">
      <button v-if="!isRoot" type="button" class="m-back" aria-label="返回" @click="router.push('/m')">‹</button>
      <div class="m-title">
        <b>{{ route.meta.title ?? '移动巡检' }}</b>
        <span>{{ app.today() }}<template v-if="app.system?.replay"> · 演示回放</template> · {{ app.me?.displayName }}</span>
      </div>
      <button v-if="isRoot" type="button" class="m-out" @click="logout">退出</button>
    </header>
    <div class="m-online" role="note">当前为在线模式，暂不支持离线巡检；请在有网络时填写并提交。</div>
    <main class="m-main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.m-root { min-height: 100%; max-width: 560px; margin: 0 auto; background: #f3f6fa; display: flex; flex-direction: column; }
.m-bar { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 8px; padding: calc(8px + env(safe-area-inset-top, 0px)) 12px 8px;
  background: linear-gradient(120deg, #0b3d91, #1f6feb); color: #fff; }
.m-back { width: 32px; height: 32px; border: none; background: rgba(255, 255, 255, 0.16); color: #fff; border-radius: 50%; font-size: 22px; line-height: 28px; padding: 0; }
.m-title { flex: 1; min-width: 0; }
.m-title b { display: block; font-size: 17px; }
.m-title span { font-size: 12px; opacity: 0.85; }
.m-out { border: 1px solid rgba(255, 255, 255, 0.6); background: transparent; color: #fff; border-radius: 14px; padding: 3px 12px; font-size: 13px; }
.m-online { padding: 6px 12px; font-size: 12px; color: #8a5a00; background: #fff4d6; border-bottom: 1px solid #f1dca0; }
.m-main { flex: 1; padding: 12px 12px calc(84px + env(safe-area-inset-bottom, 0px)); }
</style>
