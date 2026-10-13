<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { errorText } from '@/api/http'

const app = useAppStore()
const route = useRoute()
const router = useRouter()

const form = reactive({ username: '', password: '' })
const loading = ref(false)
const error = ref('')

// 演示账号清单见 docs/api-contract.md §1; 密码不在页面展示
const accounts = [
  { u: 'admin', d: '集团管理员 · 全部景区' },
  { u: 'bxdsj_admin', d: '冰雪大世界景区管理员' },
  { u: 'tyd_admin', d: '太阳岛景区管理员' },
  { u: 'operator', d: '运营人员 · 全部景区' },
  { u: 'inspector1', d: '巡检人员' },
  { u: 'reviewer', d: '复核人员' },
  { u: 'viewer', d: '只读查看者' },
]

async function submit() {
  if (!form.username || !form.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await app.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : '/admin/overview'
    router.replace(redirect)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="art" aria-hidden="true">
      <svg class="scene" viewBox="0 0 600 600" preserveAspectRatio="xMidYMax slice">
        <defs>
          <linearGradient id="ice" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#bfeaff" stop-opacity="0.95" />
            <stop offset="1" stop-color="#3f8fe0" stop-opacity="0.55" />
          </linearGradient>
          <linearGradient id="ice2" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#e6f7ff" stop-opacity="0.9" />
            <stop offset="1" stop-color="#5ec8f2" stop-opacity="0.45" />
          </linearGradient>
          <radialGradient id="glow" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0" stop-color="#9fdcff" stop-opacity="0.55" />
            <stop offset="1" stop-color="#9fdcff" stop-opacity="0" />
          </radialGradient>
        </defs>
        <circle cx="300" cy="380" r="260" fill="url(#glow)" />
        <!-- 冰雕城堡剪影 -->
        <g fill="url(#ice)" stroke="#e6f7ff" stroke-opacity="0.5">
          <path d="M120 520V360l30-40 30 40v160z" />
          <path d="M200 520V300l20-30v-40l20-30 20 30v40l20 30v220z" />
          <path d="M290 520V250l25-35v-55l15-30 15 30v55l25 35v270z" />
          <path d="M400 520V320l25-35 25 35v200z" />
          <path d="M460 520V380l20-28 20 28v140z" />
        </g>
        <g fill="url(#ice2)">
          <path d="M60 520l60-90 50 60 40-50 70 80z" />
          <path d="M360 520l60-70 50 40 70-90 50 120z" />
        </g>
        <rect x="0" y="518" width="600" height="82" fill="#eaf6ff" opacity="0.9" />
        <path d="M0 530q150-26 300 0t300 0v70H0z" fill="#fff" />
      </svg>
      <div class="flakes"><i v-for="n in 18" :key="n" :style="{ left: (n * 53) % 100 + '%', animationDelay: (n % 7) * 0.9 + 's', animationDuration: 7 + (n % 5) * 2 + 's' }" /></div>
      <div class="art-text">
        <h1>多业态融合<br />智慧文旅管控系统</h1>
        <p>点位监控 · 客流热度 · 电子围栏 · 网评舆情 · 日常巡检 · AI 运营助手</p>
      </div>
    </section>

    <section class="panel">
      <form class="card" @submit.prevent="submit">
        <h2>登录</h2>
        <p class="sub">{{ app.system?.name ?? '多业态融合智慧文旅管控系统' }}</p>
        <label>用户名
          <input v-model="form.username" autocomplete="username" maxlength="64" autofocus />
        </label>
        <label>密码
          <input v-model="form.password" type="password" autocomplete="current-password" maxlength="128" />
        </label>
        <div v-if="error" class="err" role="alert">{{ error }}</div>
        <button class="go" type="submit" :disabled="loading">{{ loading ? '登录中…' : '登 录' }}</button>

        <div v-if="app.system?.demoData" class="demo">
          <div class="demo-title"><span class="tag">演示环境</span>演示账号（密码见启动说明）</div>
          <ul>
            <li v-for="a in accounts" :key="a.u" @click="form.username = a.u">
              <code>{{ a.u }}</code><span>{{ a.d }}</span>
            </li>
          </ul>
        </div>
      </form>
      <footer>地图数据 © OpenStreetMap contributors (ODbL)</footer>
    </section>
  </div>
</template>

<style scoped>
.login { display: flex; min-height: 100%; background: #fff; }
.art { position: relative; flex: 1.15; overflow: hidden; background: linear-gradient(160deg, #061a3d 0%, #0b3d91 55%, #1f6feb 100%); color: #fff; }
.scene { position: absolute; inset: 0; width: 100%; height: 100%; }
.art-text { position: relative; padding: 64px 56px; }
.art-text h1 { margin: 0 0 16px; font-size: 38px; line-height: 1.3; letter-spacing: 2px; text-shadow: 0 2px 16px rgba(0, 20, 60, 0.5); }
.art-text p { margin: 0; color: #cfe8ff; font-size: 14px; letter-spacing: 1px; }
.flakes i { position: absolute; top: -10px; width: 5px; height: 5px; border-radius: 50%; background: #fff; opacity: 0.7; animation: fall linear infinite; }
@keyframes fall { to { transform: translate(30px, 105vh); opacity: 0.1; } }
.panel { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 32px 20px; min-width: 0; }
.card { width: 100%; max-width: 380px; display: flex; flex-direction: column; gap: 14px; }
h2 { margin: 0; font-size: 26px; color: var(--sw-text-1); }
.sub { margin: -8px 0 8px; color: var(--sw-text-3); font-size: 13px; }
label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--sw-text-2); }
input { height: 42px; border: 1px solid var(--sw-border); border-radius: 8px; padding: 0 12px; font-size: 15px; outline: none; transition: border-color 0.15s, box-shadow 0.15s; }
input:focus { border-color: var(--sw-primary); box-shadow: 0 0 0 3px rgba(31, 111, 235, 0.15); }
.err { color: var(--sw-danger); font-size: 13px; }
.go { height: 44px; border: 0; border-radius: 8px; background: linear-gradient(90deg, #1f6feb, #3aa0f0); color: #fff; font-size: 16px; cursor: pointer; }
.go:disabled { opacity: 0.6; cursor: default; }
.demo { margin-top: 10px; border: 1px dashed rgba(230, 162, 60, 0.6); background: rgba(230, 162, 60, 0.06); border-radius: 8px; padding: 12px; }
.demo-title { font-size: 12px; color: var(--sw-text-2); margin-bottom: 6px; }
.tag { background: rgba(230, 162, 60, 0.2); color: #b8740a; padding: 1px 6px; border-radius: 3px; margin-right: 6px; }
.demo ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 2px; }
.demo li { display: flex; gap: 10px; font-size: 12px; padding: 3px 4px; border-radius: 4px; cursor: pointer; color: var(--sw-text-2); }
.demo li:hover { background: rgba(31, 111, 235, 0.08); }
.demo code { min-width: 92px; color: var(--sw-primary); }
footer { margin-top: 28px; font-size: 11px; color: var(--sw-text-3); }
@media (max-width: 860px) {
  .login { flex-direction: column; }
  .art { flex: none; height: 200px; }
  .art-text { padding: 24px 20px; }
  .art-text h1 { font-size: 22px; }
  .art-text p { display: none; }
  .panel { justify-content: flex-start; }
}
</style>
