<template>
  <div class="card">
    <div class="card-title">任务列表</div>

    <div class="toolbar">
      <select class="select" v-model="kbId">
        <option value="">全部知识库</option>
        <option v-for="kb in kbFilterList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select>
      <select class="select" v-model="status">
        <option value="">全部状态</option>
        <option value="0">待处理</option>
        <option value="1">处理中</option>
        <option value="2">成功</option>
        <option value="3">失败</option>
      </select>
      <input class="input grow" v-model.trim="keyword" placeholder="按文件名 / 任务号搜索" @keyup.enter="loadTaskList">
      <button class="btn btn-primary" @click="loadTaskList">查询</button>
      <button class="btn btn-outline" @click="resetFilter">重置</button>
      <label class="switch-row">
        <input type="checkbox" v-model="autoRefresh"> 自动刷新
      </label>
    </div>

    <div class="loading-line" v-if="loading">加载中…</div>
    <div class="msg-info error" v-if="message">{{ message }}</div>
    <div class="msg-info success" v-if="successMsg">{{ successMsg }}</div>

    <div class="table-wrap" v-if="tasks.length">
      <table class="table">
        <thead>
          <tr>
            <th>任务号</th>
            <th>文件名</th>
            <th>知识库</th>
            <th>状态</th>
            <th>进度</th>
            <th>耗时</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.taskNo">
            <td style="font-family: Consolas, monospace; font-size: 12px;">{{ t.taskNo }}</td>
            <td style="max-width: 240px;">
              <div style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" :title="t.fileName">{{ t.fileName }}</div>
            </td>
            <td>{{ t.kbName || t.knowledgeBaseName || '-' }}</td>
            <td><span class="badge" :style="{ background: taskStatus(t)[1] }">{{ taskStatus(t)[0] }}</span></td>
            <td>
              <div class="task-progress">
                <span class="mini-bar"><span class="mini-fill" :class="{ full: t.successChunk > 0 && t.totalChunk > 0 && t.successChunk >= t.totalChunk }" :style="{ width: taskPct(t) + '%' }"></span></span>
                <span style="font-size: 12px; color: #64748b;">{{ t.successChunk || 0 }}/{{ t.totalChunk || 0 }}</span>
              </div>
            </td>
            <td>{{ formatCost(t.costTime) }}</td>
            <td>{{ formatDateTime(t.createTime) }}</td>
            <td>
              <button class="btn btn-outline btn-sm" @click="openDetail(t.taskNo)">详情</button>
              <button v-if="t.status === 3" class="btn btn-outline btn-sm" style="margin-left: 6px; color: #1d4ed8;" @click="retryTask(t)">重试</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="empty" v-else-if="!loading && !message">
      暂无任务。
    </div>

    <TaskDetailModal v-if="showDetail" :task-no="detailTaskNo" @close="showDetail = false" />
  </div>
</template>

<script setup>
import { ref, onActivated, onDeactivated, onUnmounted } from 'vue'
import { fetchApi } from '../api/request'
import { formatDateTime, formatCost } from '../utils/format'
import TaskDetailModal from './TaskDetailModal.vue'

const kbFilterList = ref([])
const kbId = ref('')
const status = ref('')
const keyword = ref('')
const autoRefresh = ref(true)
const tasks = ref([])
const loading = ref(false)
const message = ref('')
const successMsg = ref('')
let pollTimer = null
const detailTaskNo = ref('')
const showDetail = ref(false)

const statusMap = {
  0: ['待处理', '#93c5fd'],
  1: ['处理中', '#fbbf24'],
  2: ['成功', '#16a34a'],
  3: ['失败', '#dc2626']
}
function taskStatus(t) {
  const s = statusMap[t.status]
  return s ? [t.statusText || s[0], s[1]] : [t.statusText || '未知', '#94a3b8']
}
function taskPct(t) {
  if (t.totalChunk > 0) return Math.min(100, Math.round(((t.successChunk || 0) / t.totalChunk) * 100))
  if (t.status === 2) return 100
  if (t.status === 3) return 100
  return 0
}

async function loadKbFilter() {
  try {
    const res = await fetchApi('/api/knowledge-document/knowledge-bases')
    const data = await res.json()
    if (data.success && Array.isArray(data.data)) kbFilterList.value = data.data
  } catch (e) { /* 忽略 */ }
}

async function loadTaskList() {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
  loading.value = true
  message.value = ''
  successMsg.value = ''
  const params = new URLSearchParams()
  if (kbId.value) params.append('knowledgeBaseId', kbId.value)
  if (status.value) params.append('status', status.value)
  if (keyword.value.trim()) params.append('keyword', keyword.value.trim())
  try {
    const res = await fetchApi('/api/knowledge-document/tasks?' + params.toString())
    const data = await res.json()
    if (!data.success) {
      message.value = data.message || '查询失败'
      tasks.value = []
      return
    }
    tasks.value = data.data || []
    const hasRunning = tasks.value.some((t) => t.status === 0 || t.status === 1)
    if (autoRefresh.value && hasRunning) pollTimer = setTimeout(loadTaskList, 2000)
  } catch (e) {
    message.value = '加载失败，请稍后重试'
    tasks.value = []
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  kbId.value = ''
  status.value = ''
  keyword.value = ''
  loadTaskList()
}

function openDetail(taskNo) {
  detailTaskNo.value = taskNo
  showDetail.value = true
}

async function retryTask(t) {
  if (!window.confirm(`确定重试任务 ${t.taskNo}（${t.fileName}）吗？将基于已上传的原始文件增量重建索引，已处理的内容会自动跳过。`)) return
  try {
    const res = await fetchApi('/api/knowledge-document/task/' + encodeURIComponent(t.taskNo) + '/retry', { method: 'POST' })
    const data = await res.json()
    if (!data.success) {
      message.value = data.message || '重试失败'
      return
    }
    successMsg.value = data.message || '任务已重新提交'
    loadTaskList()
  } catch (e) {
    message.value = '重试失败，请稍后重试'
  }
}

onActivated(() => {
  loadKbFilter()
  loadTaskList()
})
onDeactivated(() => { if (pollTimer) { clearTimeout(pollTimer); pollTimer = null } })
onUnmounted(() => { if (pollTimer) { clearTimeout(pollTimer); pollTimer = null } })
</script>
