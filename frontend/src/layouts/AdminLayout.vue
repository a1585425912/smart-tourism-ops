<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminMenu } from '@/router'
import { useAppStore } from '@/stores/app'
import { buildMenu } from '@/components/admin/logic'
import { ROLE_LABELS } from '@/api/admin'

const app = useAppStore()
const route = useRoute()
const router = useRouter()

const menu = computed(() => buildMenu(adminMenu, app.has))
const collapsed = ref(false)
const drawer = ref(false)
const narrow = ref(false)
const clock = ref('')
let timer: ReturnType<typeof setInterval> | undefined

function tick() {
  clock.value = app.now().format('YYYY-MM-DD HH:mm:ss')
}

function onResize() {
  narrow.value = window.innerWidth < 900
  if (!narrow.value) drawer.value = false
}

onMounted(() => {
  tick()
  timer = setInterval(tick, 1000)
  onResize()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  clearInterval(timer)
  window.removeEventListener('resize', onResize)
})

const aiChip = computed(() => {
  const s = app.system
  if (!s) return null
  return s.aiProvider === 'deepseek' ? `AI：DeepSeek ${s.aiModel}` : 'AI：本地规则模拟'
})

const roleText = computed(() => (app.me?.roles ?? []).map((r) => ROLE_LABELS[r] ?? r).join('、'))
const active = computed(() => {
  const p = route.path
  const hit = menu.value.flatMap((g) => g.items).find((i) => p === i.path || p.startsWith(i.path + '/'))
  return hit?.path ?? p
})

function go(path: string) {
  drawer.value = false
  router.push(path)
}

async function logout() {
  await app.logout()
  router.replace('/login')
}

function openScreen() {
  window.open('/screen', '_blank', 'noopener')
}
</script>

<template>
  <div class="admin" :class="{ collapsed, narrow }">
    <div v-if="narrow && drawer" class="mask" @click="drawer = false" />
    <aside class="side" :class="{ open: drawer }">
      <div class="brand" @click="go('/admin/overview')">
        <svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true">
          <g stroke="#5ec8f2" stroke-width="2" stroke-linecap="round" fill="none">
            <path d="M16 3v26M4.7 9.5l22.6 13M27.3 9.5l-22.6 13" />
            <path d="M16 7l-3-3M16 7l3-3M16 25l-3 3M16 25l3 3M8.2 11.5l-4-.9M8.2 11.5l-1.2-4M23.8 20.5l4 .9M23.8 20.5l1.2 4M23.8 11.5l4-.9M23.8 11.5l1.2-4M8.2 20.5l-4 .9M8.2 20.5l-1.2 4" />
          </g>
        </svg>
        <span v-if="!collapsed || narrow" class="brand-text">智慧文旅管控</span>
      </div>
      <nav class="nav">
        <template v-for="g in menu" :key="g.group">
          <div v-if="!collapsed || narrow" class="group">{{ g.group }}</div>
          <div v-else class="group-sep" />
          <a
            v-for="item in g.items"
            :key="item.path"
            class="item"
            :class="{ on: active === item.path }"
            :title="item.title"
            @click="go(item.path)"
          >
            <el-icon v-if="item.icon" :size="17"><component :is="item.icon" /></el-icon>
            <span v-if="!collapsed || narrow">{{ item.title }}</span>
          </a>
        </template>
      </nav>
      <div v-if="!collapsed || narrow" class="side-foot">地图数据 © OpenStreetMap contributors</div>
    </aside>

    <div class="main">
      <header class="top">
        <button class="icon-btn" type="button" aria-label="菜单" @click="narrow ? (drawer = !drawer) : (collapsed = !collapsed)">
          <el-icon :size="18"><Fold v-if="!collapsed && !narrow" /><Expand v-else /></el-icon>
        </button>
        <el-select
          v-if="app.sites.length"
          class="site-select"
          :model-value="app.isGlobal ? 0 : app.currentSite?.id"
          placeholder="选择景区"
          @update:model-value="(v: number) => app.selectSite(v)"
        >
          <el-option label="全局（全部授权景区）" :value="0" />
          <el-option v-for="s in app.sites" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <span v-else class="sw-muted">暂无授权景区</span>
        <div class="spacer" />
        <span class="clock num" title="业务时钟">{{ clock }}</span>
        <span v-if="app.system?.replay" class="chip amber" title="系统运行在演示回放时钟下，数据为演示数据">演示回放</span>
        <span v-if="aiChip" class="chip" :class="app.system?.aiProvider === 'deepseek' ? 'blue' : 'grey'">{{ aiChip }}</span>
        <router-link v-if="app.has('INSPECTION_READ')" class="link hide-s" to="/m">移动巡检</router-link>
        <el-button class="hide-s" type="primary" plain size="small" @click="openScreen">指挥大屏</el-button>
        <el-dropdown trigger="click">
          <span class="user">
            <el-icon><UserFilled /></el-icon>
            <span class="hide-s">{{ app.me?.displayName }}</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ app.me?.username }} · {{ roleText }}</el-dropdown-item>
              <el-dropdown-item @click="openScreen">指挥大屏</el-dropdown-item>
              <el-dropdown-item v-if="app.has('INSPECTION_READ')" @click="router.push('/m')">移动巡检</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <main class="content">
        <router-view :key="route.path" />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin { display: flex; height: 100%; background: var(--sw-bg); }
.side { width: 216px; flex: none; display: flex; flex-direction: column; color: #c9d7ea;
  background: linear-gradient(180deg, #0b2a5c 0%, #0a1f44 100%); transition: width 0.2s; z-index: 30; }
.collapsed .side { width: 60px; }
.brand { display: flex; align-items: center; gap: 10px; height: 56px; padding: 0 16px; cursor: pointer; color: #fff; font-weight: 600; letter-spacing: 1px; white-space: nowrap; }
.nav { flex: 1; overflow-y: auto; padding: 4px 8px 12px; }
.nav::-webkit-scrollbar { width: 0; }
.group { font-size: 12px; color: #6f86a8; padding: 14px 10px 6px; }
.group-sep { height: 1px; background: rgba(255, 255, 255, 0.08); margin: 8px 6px; }
.item { display: flex; align-items: center; gap: 10px; height: 38px; padding: 0 10px; border-radius: 6px; cursor: pointer; font-size: 14px; white-space: nowrap; color: inherit; }
.item:hover { background: rgba(94, 200, 242, 0.12); color: #fff; }
.item.on { background: var(--sw-primary); color: #fff; }
.collapsed:not(.narrow) .item { justify-content: center; padding: 0; }
.side-foot { font-size: 11px; color: #5b7396; padding: 10px 16px; }
.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.top { height: 56px; flex: none; display: flex; align-items: center; gap: 10px; padding: 0 16px; background: #fff; border-bottom: 1px solid var(--sw-border); }
.icon-btn { border: 0; background: transparent; cursor: pointer; padding: 6px; border-radius: 6px; color: var(--sw-text-2); display: inline-flex; }
.icon-btn:hover { background: var(--sw-bg); }
.site-select { width: 190px; }
.spacer { flex: 1; }
.clock { font-size: 13px; color: var(--sw-text-2); white-space: nowrap; }
.chip { font-size: 12px; padding: 2px 8px; border-radius: 10px; white-space: nowrap; }
.chip.amber { background: rgba(230, 162, 60, 0.16); color: #b8740a; border: 1px solid rgba(230, 162, 60, 0.5); }
.chip.blue { background: rgba(31, 111, 235, 0.1); color: var(--sw-primary); border: 1px solid rgba(31, 111, 235, 0.35); }
.chip.grey { background: #eef2f7; color: var(--sw-text-2); border: 1px solid var(--sw-border); }
.link { font-size: 13px; color: var(--sw-primary); text-decoration: none; white-space: nowrap; }
.user { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-size: 13px; color: var(--sw-text-2); }
.content { flex: 1; overflow: auto; }
.mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.4); z-index: 20; }

@media (max-width: 899px) {
  .side { position: fixed; left: 0; top: 0; bottom: 0; width: 216px; transform: translateX(-100%); transition: transform 0.2s; }
  .side.open { transform: none; }
  .top { padding: 0 10px; gap: 8px; }
  .site-select { width: 130px; }
  .clock { display: none; }
}
@media (max-width: 640px) {
  .hide-s { display: none; }
  .chip.blue, .chip.grey { display: none; }
}
@media print {
  .side, .top { display: none; }
  .content { overflow: visible; }
  .admin { height: auto; }
}
</style>
