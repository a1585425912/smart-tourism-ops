<script setup lang="ts">
import { computed, watch } from 'vue'
import { screenApi } from '@/api/screen'
import ScreenPanel from './ScreenPanel.vue'
import CountUp from './CountUp.vue'
import { useScreen } from './screenContext'
import { usePanel } from './usePanel'
import { money, pct, perSite } from './screenData'
import { mergeMode, sumNullable } from './screenUtils'

/** 底部三个小面板: 业态经营(今日) / 旅行社团队(今日) / 巡检状态。 */
const ctx = useScreen()

const biz = usePanel(() => perSite(ctx.siteIds.value, (id) => screenApi.business(id, ctx.range.value.date, ctx.range.value.tomorrow)), [ctx.siteIds])
const grp = usePanel(() => perSite(ctx.siteIds.value, (id) => screenApi.groupStats(id, ctx.range.value.date, ctx.range.value.tomorrow)), [ctx.siteIds])
watch(ctx.tick, () => { biz.reload(); grp.reload() }, { immediate: true })

const CATS: Record<string, string> = { HOTEL: '酒店', CATERING: '餐饮', RETAIL: '门店零售', TICKET: '票务', ENTERTAINMENT: '娱乐项目' }

const bizView = computed(() => {
  const all = (biz.data.value ?? []).flatMap((r) => r.data.byCategory)
  const of = (c: string) => all.filter((x) => x.category === c)
  const hotels = of('HOTEL').filter((h) => h.avgOccupancy !== null && h.units > 0)
  const units = hotels.reduce((a, h) => a + h.units, 0)
  return {
    // 多景区酒店入住率按酒店数量加权平均
    occupancy: units > 0 ? hotels.reduce((a, h) => a + h.avgOccupancy! * h.units, 0) / units : null,
    rows: ['CATERING', 'RETAIL', 'HOTEL'].map((c) => ({ name: CATS[c], revenue: sumNullable(of(c).map((x) => x.revenue)) })),
    basis: biz.data.value?.[0]?.data.amountBasis ?? '',
    hasAny: all.length > 0,
  }
})
const bizMode = computed(() => mergeMode((biz.data.value ?? []).map((r) => r.data.meta)))

const grpView = computed(() => {
  const list = (grp.data.value ?? []).map((r) => r.data)
  const agencies = new Map<string, number>()
  list.flatMap((g) => g.byAgency).forEach((a) => agencies.set(a.name, (agencies.get(a.name) ?? 0) + a.visitors))
  return {
    groups: list.length ? list.reduce((a, g) => a + g.totalGroups, 0) : null,
    visitors: list.length ? list.reduce((a, g) => a + g.totalVisitors, 0) : null,
    top: [...agencies.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3),
  }
})
const grpMode = computed(() => mergeMode((grp.data.value ?? []).map((r) => r.data.meta)))

const insp = computed(() => {
  const sites = (ctx.overview.value?.sites ?? []).filter((s) => ctx.siteIds.value.includes(s.siteId))
  if (!ctx.overview.value) return null
  return {
    open: sites.reduce((a, s) => a + s.inspections.open, 0),
    overdue: sites.reduce((a, s) => a + s.inspections.overdue, 0),
    issues: sites.reduce((a, s) => a + s.inspections.openIssues, 0),
    online: sites.reduce((a, s) => a + s.devices.online, 0),
    devices: sites.reduce((a, s) => a + s.devices.total, 0),
  }
})
</script>

<template>
  <ScreenPanel title="业态经营" sub="今日" :mode="bizMode" :loading="biz.loading.value" :error="biz.data.value ? null : biz.error.value"
    :empty="!bizView.hasAny" empty-text="暂无业态数据" @retry="biz.reload">
    <div class="mini">
      <div class="big"><label>酒店入住率</label><b>{{ pct(bizView.occupancy, 1) }}</b></div>
      <div v-for="r in bizView.rows" :key="r.name" class="row"><span>{{ r.name }}营收</span><em>{{ money(r.revenue) }}</em></div>
      <div v-if="bizView.basis" class="foot">口径：{{ bizView.basis }}</div>
    </div>
  </ScreenPanel>

  <ScreenPanel title="旅行社团队" sub="今日" :mode="grpMode" :loading="grp.loading.value" :error="grp.data.value ? null : grp.error.value"
    :empty="grpView.groups === null" empty-text="暂无团队数据" @retry="grp.reload">
    <div class="mini">
      <div class="duo">
        <div class="big"><label>入园团队</label><b><CountUp :value="grpView.groups" /></b></div>
        <div class="big"><label>团队人数</label><b><CountUp :value="grpView.visitors" /></b></div>
      </div>
      <div v-for="[name, v] in grpView.top" :key="name" class="row"><span class="ell">{{ name }}</span><em>{{ v }} 人</em></div>
      <div v-if="!grpView.top.length" class="foot">今日暂无团队入园</div>
    </div>
  </ScreenPanel>

  <ScreenPanel title="巡检与设备" sub="当前" :mode="ctx.overview.value?.meta.dataMode" :loading="!ctx.overview.value && !ctx.overviewError.value"
    :error="ctx.overview.value ? null : ctx.overviewError.value" :empty="!insp">
    <div v-if="insp" class="mini">
      <div class="duo">
        <div class="big"><label>未完成任务</label><b><CountUp :value="insp.open" /></b></div>
        <div class="big"><label>已逾期</label><b :class="{ bad: insp.overdue > 0 }"><CountUp :value="insp.overdue" /></b></div>
      </div>
      <div class="row"><span>待整改异常</span><em :class="{ warn: insp.issues > 0 }">{{ insp.issues }} 项</em></div>
      <div class="row"><span>设备在线</span><em>{{ insp.online }} / {{ insp.devices }}</em></div>
    </div>
  </ScreenPanel>
</template>

<style scoped>
.mini { height: 100%; display: flex; flex-direction: column; gap: 5px; }
.duo { display: flex; gap: 8px; }
.duo .big { flex: 1; }
.big label { display: block; font-size: 12px; color: #7fa6c9; }
.big b { font-size: 26px; line-height: 32px; color: #5ef0ff; font-variant-numeric: tabular-nums; text-shadow: 0 0 10px rgba(94, 240, 255, 0.4); }
.big b.bad { color: #ff4d6d; text-shadow: 0 0 10px rgba(255, 77, 109, 0.5); }
.row { display: flex; justify-content: space-between; gap: 6px; font-size: 12px; color: #9fc3e0; padding: 2px 6px; background: rgba(46, 197, 255, 0.06); }
.row em { font-style: normal; color: #e6f6ff; font-variant-numeric: tabular-nums; white-space: nowrap; }
.row em.warn { color: #ffb547; }
.ell { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.foot { font-size: 11px; color: #6f8fb0; }
</style>
