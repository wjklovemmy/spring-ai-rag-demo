<template>
  <div class="modal-mask" @click.self="close">
    <div class="modal modal-lg">
      <div class="modal-header">
        <h3>Agent 任务 #{{ taskId }}</h3>
        <button class="modal-close" @click="close">×</button>
      </div>
      <div class="modal-body">
        <div class="loading-line" v-if="loading">加载中…</div>
        <div class="msg-info error" v-if="error">{{ error }}</div>

        <template v-if="detail">
          <table class="table" style="margin-bottom: 16px;">
            <tbody>
              <tr><th style="width: 120px;">问题</th><td>{{ detail.question }}</td></tr>
              <tr><th>知识库</th><td>{{ detail.kbName || '-' }}</td></tr>
              <tr><th>状态</th><td><span class="badge" :style="{ background: statusColor(detail.status) }">{{ detail.statusText || statusLabel(detail.status) }}</span></td></tr>
              <tr><th>模型</th><td>{{ detail.model || '-' }}</td></tr>
              <tr><th>Token 用量</th><td>{{ formatTokens(detail) }}</td></tr>
              <tr><th>工具调用</th><td>{{ detail.toolCount || 0 }} 次</td></tr>
              <tr><th>耗时</th><td>{{ formatCost(detail.costMs) }}</td></tr>
              <tr><th>创建时间</th><td>{{ formatDateTime(detail.createTime) }}</td></tr>
              <tr v-if="detail.finishTime"><th>完成时间</th><td>{{ formatDateTime(detail.finishTime) }}</td></tr>
              <tr v-if="detail.errorMsg">
                <th>错误信息</th>
                <td style="color: #b91c1c; white-space: pre-wrap;">{{ detail.errorMsg }}</td>
              </tr>
            </tbody>
          </table>

          <div class="card-title" style="font-size: 14px;">回答全文</div>
          <div class="answer-box">{{ detail.answer || '（无回答）' }}</div>

          <div class="card-title" style="font-size: 14px;">引用来源（{{ sourceCount }} 条）</div>
          <div class="at-source-list" v-if="detail.sources && detail.sources.length">
            <div class="at-source-item" v-for="src in detail.sources" :key="src.refIndex">
              <div class="at-source-head">
                <span class="at-source-badge">[来源{{ src.refIndex || 0 }}]</span>
                <span v-if="src.pageNo" class="at-source-page">第 {{ src.pageNo }} 页</span>
              </div>
              <div class="at-source-name">{{ src.documentName || '未知文档' }}</div>
              <div class="at-source-snippet">{{ src.snippet || '' }}</div>
            </div>
          </div>
          <div class="empty" v-else>（无引用来源，或该次问答未检索到内容）</div>

          <div class="card-title" style="font-size: 14px;">
            LLM 实际输入 Prompt
            <button class="btn btn-outline btn-sm" style="margin-left: 8px;" @click="showPrompt = !showPrompt">
              {{ showPrompt ? '收起' : '展开' }}
            </button>
            <span style="font-size: 12px; color: #94a3b8; margin-left: 8px;">{{ detail.prompt ? detail.prompt.length + ' 字符' : '' }}</span>
          </div>
          <div class="prompt-box" v-if="showPrompt && detail.prompt">{{ detail.prompt }}</div>
          <div class="empty" v-else-if="showPrompt">（无 Prompt 记录）</div>

          <div class="card-title" style="font-size: 14px;">执行轨迹（{{ stepCount }} 步）</div>
          <div class="step-list" v-if="detail.steps && detail.steps.length">
            <div class="step-item" v-for="(s, i) in detail.steps" :key="s.id || i">
              <div class="step-head">
                <span class="step-index">{{ i + 1 }}</span>
                <span class="tool-call">
                  <span class="tool-name">{{ s.toolName || s.type }}</span>
                  <span class="tool-status" :class="'st-' + s.status">{{ stepStatusText(s.status) }}</span>
                </span>
                <span v-if="s.latencyMs != null" class="step-latency">⏱ {{ formatCost(s.latencyMs) }}</span>
                <span style="font-size: 12px; color: #94a3b8;">{{ formatDateTime(s.createTime) }}</span>
              </div>
              <div class="step-json" v-if="s.args || s.result">
                <pre v-if="s.args"><b>参数</b><br>{{ s.args }}</pre>
                <pre v-if="s.result"><b>结果</b><br>{{ s.result }}</pre>
              </div>
            </div>
          </div>
          <div class="empty" v-else>（无工具步骤）</div>
        </template>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" @click="close">关闭</button>
        <button class="btn btn-primary" @click="load" :disabled="loading">刷新</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { fetchApi } from '../api/request'
import { formatDateTime, formatCost } from '../utils/format'

const props = defineProps({
  taskId: { type: Number, required: true }
})
const emit = defineEmits(['close'])

const detail = ref(null)
const loading = ref(false)
const error = ref('')
const showPrompt = ref(false)

const stepCount = computed(() => (detail.value?.steps ? detail.value.steps.length : 0))
const sourceCount = computed(() => (detail.value?.sources ? detail.value.sources.length : 0))

const statusMap = { 0: '执行中', 1: '成功', 2: '失败' }
const statusColorMap = { 0: '#fbbf24', 1: '#16a34a', 2: '#dc2626' }
function statusLabel(s) { return statusMap[s] || '未知' }
function statusColor(s) { return statusColorMap[s] || '#94a3b8' }

function stepStatusText(s) {
  if (s === 'done') return '完成'
  if (s === 'error') return '失败'
  return '进行中…'
}

function formatTokens(d) {
  const p = d.promptTokens, c = d.completionTokens, t = d.totalTokens
  if (p == null && c == null && t == null) return '-'
  return `输入 ${p ?? '-'} · 输出 ${c ?? '-'} · 总计 ${t ?? '-'}`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetchApi('/api/agent-task/' + props.taskId)
    const data = await res.json()
    if (!data.success || !data.data) {
      error.value = data.message || '任务不存在'
    } else {
      detail.value = data.data
    }
  } catch (e) {
    error.value = '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function close() { emit('close') }

watch(() => props.taskId, load, { immediate: true })
</script>

<style scoped>
.answer-box {
  white-space: pre-wrap; word-break: break-word;
  background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;
  padding: 12px; margin-bottom: 16px;
  font-size: 13px; line-height: 1.7; color: #334155;
  max-height: 280px; overflow-y: auto;
}
.at-source-list { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
.at-source-item {
  border: 1px solid #e2e8f0; border-radius: 8px;
  padding: 10px 12px; background: #f8fafc;
}
.at-source-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}
.at-source-badge {
  display: inline-flex; align-items: center; justify-content: center;
  padding: 2px 8px; border-radius: 999px;
  background: #dbeafe; color: #1d4ed8;
  font-size: 12px; font-weight: 600; white-space: nowrap;
}
.at-source-page { white-space: nowrap; font-size: 12px; color: #94a3b8; }
.at-source-name {
  width: 100%;
  margin-bottom: 4px;
  overflow-wrap: anywhere;
  font-size: 13px; font-weight: 600; color: #334155;
  line-height: 1.5;
}
.at-source-snippet {
  font-size: 12px; color: #475569; line-height: 1.6;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  overflow: hidden;
}
.prompt-box {
  white-space: pre-wrap; word-break: break-word;
  background: #0f172a; color: #e2e8f0; border-radius: 8px;
  padding: 12px; margin-bottom: 16px;
  font-family: Consolas, Menlo, monospace; font-size: 12px; line-height: 1.6;
  max-height: 320px; overflow-y: auto;
}
.step-latency { font-size: 12px; color: #64748b; }
.step-list { display: flex; flex-direction: column; gap: 10px; }
.step-item {
  border: 1px solid #e2e8f0; border-radius: 8px;
  padding: 10px 12px; background: #f8fafc;
}
.step-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 6px; }
.step-index {
  display: inline-flex; align-items: center; justify-content: center;
  width: 20px; height: 20px; border-radius: 50%;
  background: #2563eb; color: #fff; font-size: 11px; font-weight: 700; flex-shrink: 0;
}
.step-json { display: flex; flex-direction: column; gap: 6px; }
.step-json pre {
  margin: 0; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px;
  padding: 8px 10px; font-size: 12px; color: #475569;
  white-space: pre-wrap; word-break: break-word; max-height: 160px; overflow-y: auto;
}
</style>
