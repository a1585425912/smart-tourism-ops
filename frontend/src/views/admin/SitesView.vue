<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { scenicApi } from '@/api/core'
import { adminApi, ZONE_TYPE_LABELS, type ZoneBody } from '@/api/admin'
import { ApiError, errorText } from '@/api/http'
import type { GeoPolygon, Site, Zone, ZoneType } from '@/api/types'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import SiteMap from '@/components/map/SiteMap.vue'
import { useAsync } from '@/components/admin/composables'
import { fmtDateTime, fmtNum, label } from '@/utils/format'

const app = useAppStore()
const siteId = computed(() => app.currentSite?.id ?? null)
const canWrite = computed(() => app.has('SITE_WRITE'))

const site = useAsync(async () => (siteId.value ? scenicApi.site(siteId.value) : null), [siteId])
const zones = useAsync(async () => (siteId.value ? scenicApi.zones(siteId.value) : []), [siteId])

const mapRef = ref<InstanceType<typeof SiteMap>>()
const mode = ref<'view' | 'draw' | 'modify'>('view')
const selectedId = ref<number | null>(null)
const saving = ref(false)
const formError = ref('')

interface ZoneForm {
  id: number | null
  version: number | null
  code: string
  name: string
  zoneType: ZoneType
  capacity: number | undefined
  warnRatio: number
  countSource: string
  remark: string
  boundary: GeoPolygon | null
}
const form = reactive<ZoneForm>({ id: null, version: null, code: '', name: '', zoneType: 'AREA', capacity: undefined, warnRatio: 0.8, countSource: '', remark: '', boundary: null })
const editing = ref(false)

watch(siteId, cancel)

function fill(z: Zone) {
  Object.assign(form, {
    id: z.id, version: z.version, code: z.code, name: z.name, zoneType: z.zoneType, capacity: z.capacity ?? undefined,
    warnRatio: Number(z.warnRatio), countSource: z.countSource ?? '', remark: z.remark ?? '', boundary: z.boundary,
  })
}

function startCreate() {
  Object.assign(form, { id: null, version: null, code: '', name: '', zoneType: 'AREA', capacity: undefined, warnRatio: 0.8, countSource: '', remark: '', boundary: null })
  selectedId.value = null
  formError.value = ''
  editing.value = true
  mode.value = 'draw'
}

function startEdit(z: Zone) {
  fill(z)
  selectedId.value = z.id
  formError.value = ''
  editing.value = true
  mode.value = canWrite.value ? 'modify' : 'view'
}

function cancel() {
  editing.value = false
  mode.value = 'view'
  formError.value = ''
  mapRef.value?.clearDraft()
  // 丢弃未保存的顶点修改: 重新加载区域
  if (form.id != null) zones.run()
}

function onSelect(id: number | null) {
  if (editing.value) return
  selectedId.value = id
}

async function save() {
  if (!siteId.value) return
  if (!form.name.trim()) {
    formError.value = '请填写区域名称'
    return
  }
  if (!form.boundary) {
    formError.value = '请先在地图上绘制围栏多边形'
    return
  }
  const body: ZoneBody = {
    code: form.code || undefined, name: form.name.trim(), zoneType: form.zoneType, boundary: form.boundary,
    capacity: form.capacity ?? null, warnRatio: form.warnRatio, countSource: form.countSource || null, remark: form.remark || null,
  }
  saving.value = true
  formError.value = ''
  try {
    if (form.id == null) {
      const z = await adminApi.createZone(siteId.value, body)
      ElMessage.success('围栏已保存')
      selectedId.value = z.id
    } else {
      await adminApi.updateZone(form.id, { ...body, version: form.version ?? 0 })
      ElMessage.success('围栏已更新')
    }
    editing.value = false
    mode.value = 'view'
    form.id = null
    await zones.run()
  } catch (e) {
    if (e instanceof ApiError && e.isConflict) {
      formError.value = '该区域已被其他人修改，请重新加载后再编辑'
      ElMessageBox.confirm('该区域已被其他人修改。是否重新加载最新数据？（当前未保存的修改将丢失）', '数据冲突', { type: 'warning', confirmButtonText: '重新加载' })
        .then(async () => {
          await zones.run()
          const fresh = zones.data.value?.find((z) => z.id === form.id)
          if (fresh) startEdit(fresh)
          else cancel()
        })
        .catch(() => undefined)
    } else if (e instanceof ApiError && e.code === 'INVALID_GEOMETRY') {
      formError.value = e.message
    } else {
      formError.value = errorText(e)
    }
  } finally {
    saving.value = false
  }
}

async function remove(z: Zone) {
  try {
    await ElMessageBox.confirm(`确定停用区域“${z.name}”？停用后不再参与区域人数统计。`, '停用区域', { type: 'warning' })
  } catch {
    return
  }
  try {
    await adminApi.deleteZone(z.id)
    ElMessage.success('已停用')
    if (selectedId.value === z.id) selectedId.value = null
    zones.run()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

// 景区资料
const siteDialog = ref(false)
const siteForm = reactive({ name: '', shortName: '', capacity: 1, warnRatio: 0.8, seasonNote: '', description: '', status: 'OPEN' as Site['status'] })
const siteSaving = ref(false)

function editSite() {
  const s = site.data.value
  if (!s) return
  Object.assign(siteForm, { name: s.name, shortName: s.shortName, capacity: s.capacity, warnRatio: Number(s.warnRatio), seasonNote: s.seasonNote ?? '', description: s.description ?? '', status: s.status })
  siteDialog.value = true
}

async function saveSite() {
  if (!siteId.value) return
  siteSaving.value = true
  try {
    await adminApi.updateSite(siteId.value, { ...siteForm })
    ElMessage.success('景区资料已更新')
    siteDialog.value = false
    site.run()
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    siteSaving.value = false
  }
}

const mapSites = computed(() => (site.data.value ? [site.data.value] : []))
</script>

<template>
  <div class="sw-page">
    <div class="sw-card">
      <div class="sw-card-title">
        <span>景区资料</span>
        <el-button v-if="canWrite && site.data.value" size="small" @click="editSite">编辑</el-button>
      </div>
      <StatePanel :loading="site.loading.value" :error="site.error.value" :empty="!site.data.value" empty-text="请选择景区" min-height="60px" @retry="site.run">
        <div v-if="site.data.value" class="info">
          <div><span>名称</span>{{ site.data.value.name }}（{{ site.data.value.code }}）</div>
          <div><span>状态</span>{{ label(site.data.value.status) }}</div>
          <div><span>最大承载量</span><b class="num">{{ fmtNum(site.data.value.capacity) }}</b> 人</div>
          <div><span>预警比例</span><b class="num">{{ Math.round(Number(site.data.value.warnRatio) * 100) }}%</b></div>
          <div><span>开放时间</span>{{ site.data.value.openTime?.slice(0, 5) ?? '—' }} – {{ site.data.value.closeTime?.slice(0, 5) ?? '—' }}</div>
          <div><span>占地面积</span><b class="num">{{ site.data.value.areaSqm == null ? '—' : fmtNum(site.data.value.areaSqm / 10000, 1) }}</b> 公顷</div>
          <div class="wide"><span>季节说明</span>{{ site.data.value.seasonNote || '—' }}</div>
          <div class="wide"><span>边界来源</span>{{ site.data.value.boundarySrc || '—' }}</div>
        </div>
      </StatePanel>
    </div>

    <div class="layout">
      <div class="sw-card map-card">
        <div class="sw-card-title">
          <span>电子围栏</span>
          <span class="ops">
            <span class="sw-muted">围栏仅划定统计范围，区域人数来自绑定的计数设备</span>
            <el-button v-if="canWrite && !editing" type="primary" size="small" :disabled="!siteId" @click="startCreate">绘制新围栏</el-button>
          </span>
        </div>
        <SiteMap
          ref="mapRef"
          :sites="mapSites"
          :zones="zones.data.value ?? []"
          :focus-site-id="siteId"
          :selected-zone-id="selectedId"
          :mode="mode"
          height="520px"
          @drawn="(g) => (form.boundary = g)"
          @modified="(_id, g) => (form.boundary = g)"
          @select-zone="onSelect"
        />
      </div>

      <div class="sw-card side">
        <template v-if="editing">
          <div class="sw-card-title"><span>{{ form.id == null ? '新建围栏' : '编辑围栏' }}</span></div>
          <el-form label-position="top" size="small" :disabled="!canWrite">
            <el-form-item label="名称" required><el-input v-model="form.name" maxlength="64" /></el-form-item>
            <el-form-item label="类型">
              <el-select v-model="form.zoneType" style="width: 100%">
                <el-option v-for="(n, k) in ZONE_TYPE_LABELS" :key="k" :label="n" :value="k" />
              </el-select>
            </el-form-item>
            <div class="pair">
              <el-form-item label="承载量（人）"><el-input-number v-model="form.capacity" :min="1" :controls="false" placeholder="未设置" style="width: 100%" /></el-form-item>
              <el-form-item label="预警比例"><el-input-number v-model="form.warnRatio" :min="0.1" :max="1" :step="0.05" :precision="2" style="width: 100%" /></el-form-item>
            </div>
            <el-form-item label="人数数据来源说明"><el-input v-model="form.countSource" maxlength="200" placeholder="如：入口双向客流计数器" /></el-form-item>
            <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" /></el-form-item>
          </el-form>
          <div class="geo" :class="{ ok: form.boundary }">
            {{ form.boundary ? `多边形已就绪（${form.boundary.coordinates[0].length - 1} 个顶点）` : '尚未绘制多边形：在地图上单击添加顶点，双击结束' }}
          </div>
          <div v-if="formError" class="err">{{ formError }}</div>
          <div class="btns">
            <el-button @click="cancel">取消</el-button>
            <el-button v-if="canWrite" type="primary" :loading="saving" @click="save">保存</el-button>
          </div>
        </template>
        <template v-else>
          <div class="sw-card-title"><span>区域列表</span><span class="sw-muted">{{ zones.data.value?.length ?? 0 }} 个</span></div>
          <StatePanel :loading="zones.loading.value" :error="zones.error.value" :empty="!zones.data.value?.length" empty-text="尚未划定区域围栏" @retry="zones.run">
            <ul class="zones">
              <li v-for="z in zones.data.value" :key="z.id" :class="{ on: z.id === selectedId }" @click="selectedId = z.id">
                <div class="zt">
                  <b>{{ z.name }}</b>
                  <span class="type">{{ ZONE_TYPE_LABELS[z.zoneType] }}</span>
                  <span v-if="z.outsideSite" class="out" title="围栏超出景区边界 30 米以上">越界</span>
                </div>
                <div class="zs sw-muted num">
                  承载 {{ fmtNum(z.capacity) }} · 面积 {{ fmtNum(z.areaSqm) }} ㎡ · 更新 {{ fmtDateTime(z.updatedAt) }}
                </div>
                <div v-if="z.countSource" class="zs sw-muted">来源：{{ z.countSource }}</div>
                <div class="zo">
                  <el-button link type="primary" size="small" @click.stop="startEdit(z)">{{ canWrite ? '编辑' : '查看' }}</el-button>
                  <el-button v-if="canWrite" link type="danger" size="small" @click.stop="remove(z)">停用</el-button>
                </div>
              </li>
            </ul>
          </StatePanel>
        </template>
      </div>
    </div>

    <el-dialog v-model="siteDialog" title="编辑景区资料" width="min(520px, 94vw)">
      <el-form label-width="96px">
        <el-form-item label="名称"><el-input v-model="siteForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="简称"><el-input v-model="siteForm.shortName" maxlength="32" /></el-form-item>
        <el-form-item label="最大承载量"><el-input-number v-model="siteForm.capacity" :min="1" /></el-form-item>
        <el-form-item label="预警比例"><el-input-number v-model="siteForm.warnRatio" :min="0.1" :max="1" :step="0.05" :precision="2" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="siteForm.status">
            <el-option label="开放" value="OPEN" /><el-option label="关闭" value="CLOSED" /><el-option label="季节性开放" value="SEASONAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="季节说明"><el-input v-model="siteForm.seasonNote" maxlength="200" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="siteForm.description" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="siteDialog = false">取消</el-button>
        <el-button type="primary" :loading="siteSaving" @click="saveSite">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.info { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 8px 20px; font-size: 14px; }
.info span { color: var(--sw-text-3); margin-right: 8px; font-size: 13px; }
.info .wide { grid-column: 1 / -1; }
.layout { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 16px; align-items: start; }
.ops { display: flex; align-items: center; gap: 10px; font-weight: 400; flex-wrap: wrap; justify-content: flex-end; }
.pair { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.geo { font-size: 12px; padding: 6px 10px; border-radius: 6px; background: rgba(230, 162, 60, 0.12); color: #b8740a; }
.geo.ok { background: rgba(47, 168, 107, 0.12); color: var(--sw-ok); }
.err { color: var(--sw-danger); font-size: 13px; margin-top: 8px; }
.btns { display: flex; justify-content: flex-end; margin-top: 12px; }
.zones { list-style: none; margin: 0; padding: 0; max-height: 470px; overflow: auto; }
.zones li { padding: 10px; border: 1px solid var(--sw-border); border-radius: 6px; margin-bottom: 8px; cursor: pointer; }
.zones li.on { border-color: var(--sw-primary); background: rgba(31, 111, 235, 0.05); }
.zt { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.type { font-size: 12px; color: var(--sw-primary); background: rgba(31, 111, 235, 0.1); padding: 0 6px; border-radius: 3px; }
.out { font-size: 12px; color: var(--sw-danger); background: rgba(229, 72, 77, 0.1); padding: 0 6px; border-radius: 3px; }
.zs { margin-top: 4px; }
.zo { margin-top: 4px; }
@media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
</style>
