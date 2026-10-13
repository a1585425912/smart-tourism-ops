<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="box">
    <div class="code">404</div>
    <h2>页面不存在</h2>
    <p>访问的地址不存在或已被移除。</p>
    <el-button type="primary" @click="router.replace('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
.box { min-height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 24px; text-align: center; }
.code { font-size: 72px; font-weight: 700; color: var(--sw-ice); line-height: 1; }
h2 { margin: 8px 0 0; }
p { color: var(--sw-text-3); font-size: 14px; }
</style>
