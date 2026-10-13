<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi, ROLE_LABELS, type UserView } from '@/api/admin'
import { ApiError, errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import { useAsync } from '@/components/admin/composables'

const app = useAppStore()
const users = useAsync(() => adminApi.users())

const dialog = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive<{ id: number | null; username: string; displayName: string; password: string; roles: string[]; allSites: boolean; siteIds: number[]; status: 'ACTIVE' | 'DISABLED' }>(
  { id: null, username: '', displayName: '', password: '', roles: [], allSites: false, siteIds: [], status: 'ACTIVE' })

function openCreate() {
  Object.assign(form, { id: null, username: '', displayName: '', password: '', roles: ['VIEWER'], allSites: false, siteIds: [], status: 'ACTIVE' })
  formError.value = ''
  dialog.value = true
}

function openEdit(u: UserView) {
  Object.assign(form, { id: u.id, username: u.username, displayName: u.displayName, password: '', roles: [...u.roles], allSites: u.allSites, siteIds: [...u.siteIds], status: u.status })
  formError.value = ''
  dialog.value = true
}

function validate(): string {
  if (form.id == null && !/^[a-zA-Z0-9_]{3,32}$/.test(form.username)) return '用户名需为 3–32 位字母、数字或下划线'
  if (!form.displayName.trim()) return '请填写姓名'
  if (!form.roles.length) return '请至少选择一个角色'
  if (form.id == null && form.password.length < 10) return '初始密码至少 10 位'
  if (form.id != null && form.password && form.password.length < 10) return '新密码至少 10 位'
  if (form.password.length > 64) return '密码不能超过 64 位'
  if (!form.allSites && !form.siteIds.length) return '请选择可访问的景区，或开启“全部景区”'
  return ''
}

async function save() {
  formError.value = validate()
  if (formError.value) return
  saving.value = true
  try {
    const scope = { roles: form.roles, allSites: form.allSites, siteIds: form.allSites ? [] : form.siteIds }
    if (form.id == null) {
      await adminApi.createUser({ username: form.username, displayName: form.displayName.trim(), password: form.password, ...scope })
    } else {
      await adminApi.updateUser(form.id, { displayName: form.displayName.trim(), status: form.status, ...scope, password: form.password || undefined })
    }
    ElMessage.success('已保存，授权变更即时生效')
    dialog.value = false
    users.run()
    if (form.id === app.me?.id) app.loadMe()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'VALIDATION_FAILED' && e.details?.fields) {
      formError.value = Object.entries(e.details.fields as Record<string, string>).map(([k, v]) => `${k}: ${v}`).join('；')
    } else {
      formError.value = errorText(e)
    }
  } finally {
    saving.value = false
  }
}

const siteNames = (u: UserView) => (u.allSites ? '全部景区' : u.siteIds.map((id) => app.sites.find((s) => s.id === id)?.shortName ?? `#${id}`).join('、') || '未授权')
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <h2>用户与授权</h2>
      <span class="sw-muted">访问权限 = 角色功能权限 × 景区数据范围；后端对每次请求独立校验</span>
      <span class="grow" />
      <el-button type="primary" size="small" @click="openCreate">新建用户</el-button>
    </div>
    <div class="sw-card">
      <StatePanel :loading="users.loading.value && !users.data.value" :error="users.error.value" :empty="!users.data.value?.length" @retry="users.run">
        <el-table :data="users.data.value ?? []" size="small">
          <el-table-column prop="username" label="用户名" width="150" />
          <el-table-column prop="displayName" label="姓名" width="130" />
          <el-table-column label="角色" min-width="200">
            <template #default="{ row }"><el-tag v-for="r in row.roles" :key="r" size="small" effect="plain" style="margin-right: 4px">{{ ROLE_LABELS[r] ?? r }}</el-tag></template>
          </el-table-column>
          <el-table-column label="景区范围" min-width="180"><template #default="{ row }">{{ siteNames(row) }}</template></el-table-column>
          <el-table-column label="状态" width="80"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column label="" width="70" fixed="right"><template #default="{ row }"><el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button></template></el-table-column>
        </el-table>
      </StatePanel>
    </div>

    <el-dialog v-model="dialog" :title="form.id == null ? '新建用户' : `编辑用户 ${form.username}`" width="min(540px, 94vw)">
      <el-form label-width="92px">
        <el-form-item v-if="form.id == null" label="用户名" required><el-input v-model="form.username" maxlength="32" autocomplete="off" /></el-form-item>
        <el-form-item label="姓名" required><el-input v-model="form.displayName" maxlength="32" /></el-form-item>
        <el-form-item :label="form.id == null ? '初始密码' : '重置密码'" :required="form.id == null">
          <el-input v-model="form.password" type="password" show-password maxlength="64" autocomplete="new-password" :placeholder="form.id == null ? '至少 10 位' : '留空则不修改，至少 10 位'" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.roles" multiple style="width: 100%">
            <el-option v-for="(n, k) in ROLE_LABELS" :key="k" :label="n" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="全部景区"><el-switch v-model="form.allSites" /><span class="sw-muted" style="margin-left: 8px">开启后可访问全部现有及新增景区</span></el-form-item>
        <el-form-item v-if="!form.allSites" label="景区范围" required>
          <el-select v-model="form.siteIds" multiple style="width: 100%">
            <el-option v-for="s in app.sites" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.id != null" label="账号状态">
          <el-radio-group v-model="form.status" :disabled="form.id === app.me?.id">
            <el-radio value="ACTIVE">启用</el-radio><el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div v-if="formError" class="err">{{ formError }}</div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; font-size: 20px; }
.grow { flex: 1; }
.err { color: var(--sw-danger); font-size: 13px; }
</style>
