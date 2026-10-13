<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { mobileApi, type TaskView } from '@/api/mobile'
import StatePanel from '@/components/common/StatePanel.vue'
import { groupTasks, PRIORITY_TEXT, TASK_STATUS_TEXT } from '@/components/mobile/mobileUtils'
import { fmtTime } from '@/utils/format'

const router = useRouter()
const tasks = ref<TaskView[] | null>(null)
const loading = ref(false)
const error = ref<unknown>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    tasks.value = (await mobileApi.myTasks()).items
  } catch (e) {
    error.value = e
  } finally {
    loading.value = false
  }
}
onMounted(load)

const groups = computed(() => groupTasks(tasks.value ?? []))
const overdueCount = computed(() => (tasks.value ?? []).filter((t) => t.overdue).length)
</script>

<template>
  <div class="tasks">
    <div class="head">
      <span v-if="tasks">共 {{ tasks.length }} 项任务<b v-if="overdueCount"> · {{ overdueCount }} 项逾期</b></span>
      <span v-else />
      <button type="button" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>

    <StatePanel :loading="loading && !tasks" :error="tasks ? null : error" :empty="!!tasks && groups.length === 0" empty-text="暂无分配给我的巡检任务" min-height="240px" @retry="load">
      <p v-if="error && tasks" class="stale">刷新失败，显示的是上次加载的内容</p>
      <section v-for="g in groups" :key="g.key" class="group">
        <h3>{{ g.title }}<i>{{ g.tasks.length }}</i></h3>
        <article v-for="t in g.tasks" :key="t.id" class="card" :class="{ overdue: t.overdue }" @click="router.push(`/m/task/${t.id}`)">
          <div class="row1">
            <b>{{ t.title }}</b>
            <span class="st" :class="t.status">{{ TASK_STATUS_TEXT[t.status] ?? t.status }}</span>
          </div>
          <div class="row2">{{ t.siteName }}<template v-if="t.zoneName"> · {{ t.zoneName }}</template> · {{ t.templateName }}</div>
          <div class="row3">
            <span>截止 {{ t.dueAt ? fmtTime(t.dueAt) : '未设置' }}</span>
            <span v-if="t.overdue" class="tag red">已逾期</span>
            <span v-if="t.priority === 'HIGH' || t.priority === 'URGENT'" class="tag amber">{{ PRIORITY_TEXT[t.priority] }}优先</span>
            <span v-if="t.openIssueCount" class="tag amber">{{ t.openIssueCount }} 项异常待处理</span>
            <span class="no">{{ t.taskNo }}</span>
          </div>
        </article>
      </section>
    </StatePanel>
  </div>
</template>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-size: 13px; color: #4a5568; }
.head b { color: #d03050; font-weight: 600; }
.head button { border: 1px solid #1f6feb; color: #1f6feb; background: #fff; border-radius: 16px; padding: 5px 16px; font-size: 13px; }
.head button:disabled { opacity: 0.6; }
.stale { margin: 0 0 8px; font-size: 12px; color: #c27c0e; }
.group { margin-bottom: 14px; }
h3 { margin: 0 0 8px; font-size: 14px; color: #1c2533; display: flex; align-items: center; gap: 6px; }
h3 i { font-style: normal; font-size: 12px; color: #fff; background: #8492a6; border-radius: 9px; padding: 0 7px; line-height: 18px; }
.card { background: #fff; border-radius: 10px; padding: 12px; margin-bottom: 8px; border: 1px solid #e3e8ef; border-left: 4px solid #1f6feb; }
.card:active { background: #f1f5fb; }
.card.overdue { border-left-color: #e5484d; }
.row1 { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
.row1 b { font-size: 15px; color: #1c2533; }
.st { flex: none; font-size: 12px; padding: 1px 8px; border-radius: 10px; background: #e8f0fe; color: #1f6feb; white-space: nowrap; }
.st.RECTIFYING { background: #fdecea; color: #d03050; }
.st.COMPLETED { background: #e6f6ee; color: #2fa86b; }
.st.SUBMITTED, .st.PENDING_REVIEW { background: #eef1f5; color: #4a5568; }
.row2 { margin-top: 4px; font-size: 13px; color: #4a5568; }
.row3 { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 12px; color: #8492a6; }
.tag { padding: 0 6px; border-radius: 3px; line-height: 18px; }
.tag.red { background: #fdecea; color: #d03050; }
.tag.amber { background: #fff4d6; color: #a86a00; }
.no { margin-left: auto; font-variant-numeric: tabular-nums; }
</style>
