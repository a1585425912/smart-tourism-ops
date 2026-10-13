<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const app = useAppStore()

async function relogin() {
  await app.logout().catch(() => undefined)
  router.replace('/login')
}
</script>

<template>
  <div class="box">
    <div class="code">403</div>
    <h2>无权访问该页面</h2>
    <p>当前账号没有访问该功能的权限，或未被授权对应景区。如需开通，请联系集团管理员。</p>
    <div class="ops">
      <el-button type="primary" @click="router.replace('/')">返回首页</el-button>
      <el-button @click="relogin">切换账号</el-button>
    </div>
  </div>
</template>

<style scoped>
.box { min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 24px; text-align: center; }
.code { font-size: 72px; font-weight: 700; color: var(--sw-warn); line-height: 1; }
h2 { margin: 8px 0 0; }
p { color: var(--sw-text-3); max-width: 420px; font-size: 14px; }
.ops { margin-top: 8px; }
</style>
