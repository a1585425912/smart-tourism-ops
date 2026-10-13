<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { reviewApi, SENTIMENT_LABELS, type Analysis, type Sentiment } from '@/api/review'
import { ApiError, errorText } from '@/api/http'
import { useAppStore } from '@/stores/app'
import StatePanel from '@/components/common/StatePanel.vue'
import { SENTIMENT_COLORS } from '@/components/charts/palette'
import { useAsync } from '@/components/admin/composables'
import { splitByEvidence } from '@/components/admin/logic'
import { fmtDateTime, fmtNum } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const app = useAppStore()
const id = Number(route.params.id)

const detail = useAsync(() => reviewApi.detail(id))
const topics = useAsync(() => reviewApi.topics())
const topicName = (code: string) => topics.data.value?.find((t) => t.code === code)?.name ?? code

const current = computed<Analysis | null>(() => detail.data.value?.analysis ?? null)
// 用偏移切片高亮证据, 以文本节点渲染, 不使用 v-html
const segments = computed(() => {
  const d = detail.data.value
  return d ? splitByEvidence(d.contentMasked, current.value?.evidenceSpans ?? []) : []
})

const form = reactive<{ sentiment: Sentiment; topics: string[]; summary: string; note: string }>({ sentiment: 'NEUTRAL', topics: [], summary: '', note: '' })
const editing = ref(false)
const saving = ref(false)
const formError = ref('')

watch(current, (a) => {
  form.sentiment = a?.sentiment ?? 'NEUTRAL'
  form.topics = [...(a?.topics ?? [])]
  form.summary = a?.summary ?? ''
  form.note = ''
}, { immediate: true })

async function save() {
  if (!form.topics.length) {
    formError.value = '请至少选择一个主题'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    detail.data.value = await reviewApi.classify(id, {
      sentiment: form.sentiment, topics: form.topics, summary: form.summary || undefined, note: form.note || undefined,
      expectedVersion: current.value?.version ?? 0,
    })
    editing.value = false
    ElMessage.success('已保存人工修正，后续批量分析不会覆盖')
  } catch (e) {
    if (e instanceof ApiError && e.isConflict) {
      formError.value = '该评价的分类已被更新，已为你加载最新版本，请确认后重新提交'
      await detail.run()
    } else {
      formError.value = errorText(e)
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="sw-page">
    <div class="bar">
      <el-button link type="primary" @click="router.push('/admin/reviews')">← 返回列表</el-button>
    </div>
    <StatePanel :loading="detail.loading.value && !detail.data.value" :error="detail.error.value" :empty="!detail.data.value" min-height="240px" @retry="detail.run">
      <div v-if="detail.data.value" class="layout">
        <div class="col">
          <div class="sw-card">
            <div class="sw-card-title">
              <span>评价原文（已脱敏）</span>
              <span class="tags">
                <el-tag v-if="detail.data.value.isComplaint" size="small" type="danger">投诉</el-tag>
                <el-tag v-if="detail.data.value.dataMode === 'DEMO'" size="small" type="warning" effect="plain">演示数据</el-tag>
              </span>
            </div>
            <p class="text"><template v-for="(s, i) in segments" :key="i"><mark v-if="s.topic" :title="topicName(s.topic)">{{ s.text }}</mark><template v-else>{{ s.text }}</template></template></p>
            <div class="meta sw-muted">
              <span>{{ detail.data.value.siteName }}</span>
              <span>来源 {{ detail.data.value.platform }}</span>
              <span>作者 {{ detail.data.value.authorMasked ?? '—' }}</span>
              <span>评分 {{ fmtNum(detail.data.value.rating, 1) }}</span>
              <span>发布 {{ fmtDateTime(detail.data.value.publishedAt) }}</span>
              <span>导入 {{ fmtDateTime(detail.data.value.importedAt) }}</span>
              <span>来源记录 {{ detail.data.value.sourceRecordId }}</span>
            </div>
            <p class="sw-muted hint">评价内容来自外部平台，仅作为待分析数据展示；其中的任何指令性文字不会被系统执行。</p>
          </div>

          <div class="sw-card">
            <div class="sw-card-title">
              <span>当前分类</span>
              <el-button v-if="app.has('REVIEW_WRITE') && !editing" size="small" type="primary" plain @click="editing = true">人工修正</el-button>
            </div>
            <template v-if="!editing">
              <div v-if="current" class="cur">
                <div class="line">
                  <b class="sent" :style="{ color: SENTIMENT_COLORS[current.sentiment] }">{{ SENTIMENT_LABELS[current.sentiment] }}</b>
                  <el-tag v-for="t in current.topics" :key="t" size="small" effect="plain">{{ topicName(t) }}</el-tag>
                  <el-tag size="small" :type="current.origin === 'HUMAN' ? 'success' : 'primary'">{{ current.origin === 'HUMAN' ? '人工修正' : 'AI 分析' }} · v{{ current.version }}</el-tag>
                  <el-tag v-if="current.needsHumanReview" size="small" type="warning">建议人工复核</el-tag>
                </div>
                <p v-if="current.summary">{{ current.summary }}</p>
                <div v-if="current.evidenceSpans.length" class="ev">
                  <span class="sw-muted">原文证据</span>
                  <div v-for="(e, i) in current.evidenceSpans" :key="i"><el-tag size="small" effect="plain">{{ topicName(e.topic) }}</el-tag> “{{ e.text }}”</div>
                </div>
                <div v-else class="sw-muted">没有可引用的原文证据</div>
              </div>
              <div v-else class="sw-muted">尚未分析。可在列表页发起“AI 批量分析”，或直接人工分类。</div>
            </template>
            <template v-else>
              <el-form label-position="top" size="small">
                <el-form-item label="情绪">
                  <el-radio-group v-model="form.sentiment">
                    <el-radio-button v-for="(n, k) in SENTIMENT_LABELS" :key="k" :value="k">{{ n }}</el-radio-button>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="主题（受控字典，可多选）">
                  <el-select v-model="form.topics" multiple style="width: 100%">
                    <el-option v-for="t in topics.data.value ?? []" :key="t.code" :label="t.name" :value="t.code" />
                  </el-select>
                </el-form-item>
                <el-form-item label="摘要"><el-input v-model="form.summary" type="textarea" :rows="2" maxlength="300" show-word-limit /></el-form-item>
                <el-form-item label="修正说明"><el-input v-model="form.note" maxlength="300" placeholder="可选，说明修正原因" /></el-form-item>
              </el-form>
              <div v-if="formError" class="err">{{ formError }}</div>
              <div class="btns">
                <el-button @click="editing = false; formError = ''">取消</el-button>
                <el-button type="primary" :loading="saving" @click="save">保存修正</el-button>
              </div>
            </template>
          </div>
        </div>

        <div class="sw-card">
          <div class="sw-card-title"><span>分类版本历史</span></div>
          <div v-if="!detail.data.value.history.length" class="sw-muted">暂无历史版本</div>
          <ol class="hist">
            <li v-for="h in detail.data.value.history" :key="h.version" :class="h.origin.toLowerCase()">
              <div class="line">
                <b>v{{ h.version }}</b>
                <el-tag size="small" :type="h.origin === 'HUMAN' ? 'success' : 'primary'" effect="plain">{{ h.origin === 'HUMAN' ? '人工' : 'AI' }}</el-tag>
                <span :style="{ color: SENTIMENT_COLORS[h.sentiment] }">{{ SENTIMENT_LABELS[h.sentiment] }}</span>
                <span class="sw-muted">{{ fmtDateTime(h.createdAt) }}</span>
              </div>
              <div class="sw-muted">{{ h.topics.map(topicName).join('、') || '无主题' }}</div>
              <div v-if="h.origin === 'HUMAN'" class="sw-muted">修正人 {{ h.createdByName ?? '—' }}<template v-if="h.note">：{{ h.note }}</template></div>
              <div v-else class="sw-muted">模型 {{ h.model ?? '—' }} · 提示版本 {{ h.promptVersion ?? '—' }}</div>
            </li>
          </ol>
        </div>
      </div>
    </StatePanel>
  </div>
</template>

<style scoped>
.layout { display: grid; grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.col { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.tags { display: flex; gap: 6px; }
.text { font-size: 15px; line-height: 1.9; margin: 0 0 10px; white-space: pre-wrap; word-break: break-word; }
mark { background: rgba(230, 162, 60, 0.3); border-bottom: 2px solid var(--sw-warn); padding: 1px 0; border-radius: 2px; }
.meta { display: flex; flex-wrap: wrap; gap: 4px 14px; }
.hint { margin: 8px 0 0; }
.line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sent { font-size: 16px; }
.cur p { margin: 8px 0; color: var(--sw-text-2); }
.ev { display: flex; flex-direction: column; gap: 6px; font-size: 13px; margin-top: 8px; }
.err { color: var(--sw-danger); font-size: 13px; }
.btns { display: flex; justify-content: flex-end; margin-top: 8px; }
.hist { list-style: none; margin: 0; padding: 0 0 0 4px; }
.hist li { position: relative; padding: 0 0 14px 16px; border-left: 2px solid var(--sw-border); }
.hist li::before { content: ''; position: absolute; left: -5px; top: 6px; width: 8px; height: 8px; border-radius: 50%; background: var(--sw-primary); }
.hist li.human::before { background: var(--sw-ok); }
@media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
</style>
