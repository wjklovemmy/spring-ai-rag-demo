<template>
  <div class="modal-mask" @click.self="close">
    <div class="modal modal-lg">
      <div class="modal-header">
        <h3>任务详情</h3>
        <button class="modal-close" @click="close">×</button>
      </div>
      <div class="modal-body">
        <div class="loading-line" v-if="loading">加载中…</div>
        <div class="msg-info error" v-if="error">{{ error }}</div>

        <template v-if="detail">
          <table class="table" style="margin-bottom: 16px;">
            <tbody>
              <tr><th style="width: 130px;">任务号</th><td>{{ detail.taskNo }}</td></tr>
              <tr><th>文件名</th><td>{{ detail.fileName }}</td></tr>
              <tr><th>知识库</th><td>{{ detail.kbName || detail.knowledgeBaseName || '-' }}</td></tr>
              <tr><th>状态</th><td><span class="badge" :style="{ background: statusColor }">{{ detail.statusText || statusLabel }}</span></td></tr>
              <tr><th>片段数</th><td>{{ detail.successChunk || 0 }} / {{ detail.totalChunk || 0 }}</td></tr>
              <tr><th>耗时</th><td>{{ formatCost(detail.costTime) }}</td></tr>
              <tr><th>创建时间</th><td>{{ formatDateTime(detail.createTime) }}</td></tr>
              <tr><th>更新时间</th><td>{{ formatDateTime(detail.updateTime) }}</td></tr>
              <tr v-if="detail.errorMessage">
                <th>错误信息</th>
                <td style="color: #b91c1c; white-space: pre-wrap;">{{ detail.errorMessage }}</td>
              </tr>
            </tbody>
          </table>

          <div class="card-title" style="font-size: 14px;">处理进度</div>
          <div class="stage-bar" v-for="s in stages" :key="s.label">
            <div class="sb-top">
              <span>{{ s.label }}</span>
              <span>{{ s.pct }}%</span>
            </div>
            <div class="stage-track">
              <div class="stage-fill" :class="{ done: s.pct >= 100 }" :style="{ width: s.pct + '%' }"></div>
            </div>
          </div>
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
  taskNo: { type: String, required: true }
})
const emit = defineEmits(['close'])

const detail = ref(null)
const loading = ref(false)
const error = ref('')

const statusMap = {
  0: ['待处理', '#93c5fd'],
  1: ['处理中', '#fbbf24'],
  2: ['成功', '#16a34a'],
  3: ['失败', '#dc2626']
}
const statusLabel = computed(() => (statusMap[detail.value?.status] || ['未知', '#94a3b8'])[0])
const statusColor = computed(() => (statusMap[detail.value?.status] || ['未知', '#94a3b8'])[1])

const stages = computed(() => {
  const t = detail.value
  if (!t) return []
  return [
    ['PDF 解析', t.parseProgress],
    ['文本切片', t.splitProgress],
    ['Chunk 入库', t.chunkProgress],
    ['向量化 Embedding', t.embedProgress],
    ['Milvus 写入', t.milvusProgress]
  ].map(([label, pct]) => ({ label, pct: Math.max(0, Math.min(100, Math.round(pct || 0))) }))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await fetchApi('/api/knowledge-document/task/' + encodeURIComponent(props.taskNo))
    const data = await res.json()
    if (!data.success || !data.data) {
      error.value = data.message || '任务不存在或已过期'
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

watch(() => props.taskNo, load, { immediate: true })
</script>
