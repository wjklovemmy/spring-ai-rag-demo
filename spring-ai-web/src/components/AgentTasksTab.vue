<template>
  <div class="card">
    <div class="card-title">Agent 任务</div>

    <div class="toolbar">
      <select class="select" v-model="kbId">
        <option value="">全部知识库</option>
        <option v-for="kb in kbFilterList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select>
      <select class="select" v-model="status">
        <option value="">全部状态</option>
        <option value="0">执行中</option>
        <option value="1">成功</option>
        <option value="2">失败</option>
      </select>
      <input class="input grow" v-model.trim="keyword" placeholder="按问题搜索" @keyup.enter="search">
      <button class="btn btn-primary" @click="search">查询</button>
      <button class="btn btn-outline" @click="resetFilter">重置</button>
    </div>

    <div class="loading-line" v-if="loading">加载中…</div>
    <div class="msg-info error" v-if="message">{{ message }}</div>

    <div class="table-wrap" v-if="tasks.length">
      <table class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>问题</th>
            <th>知识库</th>
            <th>状态</th>
            <th>工具数</th>
            <th>耗时</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.id">
            <td style="font-family: Consolas, monospace; font-size: 12px;">{{ t.id }}</td>
            <td style="max-width: 280px;">
              <div style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" :title="t.question">{{ t.question }}</div>
            </td>
            <td>{{ t.kbName || '-' }}</td>
            <td><span class="badge" :style="{ background: statusColor(t.status) }">{{ t.statusText || statusLabel(t.status) }}</span></td>
            <td>{{ t.toolCount || 0 }}</td>
            <td>{{ formatCost(t.costMs) }}</td>
            <td>{{ formatDateTime(t.createTime) }}</td>
            <td>
              <button class="btn btn-outline btn-sm" @click="openDetail(t.id)">轨迹</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div class="pager" v-if="total > size">
        <span style="font-size: 13px; color: #64748b;">共 {{ total }} 条</span>
        <button class="btn btn-outline btn-sm" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
        <span style="font-size: 13px; color: #64748b;">{{ page }} / {{ pageCount }}</span>
        <button class="btn btn-outline btn-sm" :disabled="page >= pageCount" @click="changePage(page + 1)">下一页</button>
      </div>
    </div>
    <div class="empty" v-else-if="!loading && !message">暂无 Agent 任务。</div>

    <AgentTaskDetailModal v-if="showDetail" :task-id="detailId" @close="showDetail = false" />
  </div>
</template>

<script setup>
import { ref, computed, onActivated } from 'vue'
import { fetchApi } from '../api/request'
import { formatDateTime, formatCost } from '../utils/format'
import AgentTaskDetailModal from './AgentTaskDetailModal.vue'

const kbFilterList = ref([])
const kbId = ref('')
const status = ref('')
const keyword = ref('')
const tasks = ref([])
const loading = ref(false)
const message = ref('')
const total = ref(0)
const page = ref(1)
const size = ref(10)

const showDetail = ref(false)
const detailId = ref(null)

const statusMap = { 0: '执行中', 1: '成功', 2: '失败' }
const statusColorMap = { 0: '#fbbf24', 1: '#16a34a', 2: '#dc2626' }
function statusLabel(s) { return statusMap[s] || '未知' }
function statusColor(s) { return statusColorMap[s] || '#94a3b8' }

const pageCount = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

async function loadKbFilter() {
  try {
    const res = await fetchApi('/api/knowledge-document/knowledge-bases')
    const data = await res.json()
    if (data.success && Array.isArray(data.data)) kbFilterList.value = data.data
  } catch (e) { /* 忽略 */ }
}

async function loadTasks() {
  loading.value = true
  message.value = ''
  const params = new URLSearchParams()
  if (kbId.value) params.append('kbId', kbId.value)
  if (status.value) params.append('status', status.value)
  if (keyword.value.trim()) params.append('keyword', keyword.value.trim())
  params.append('page', page.value)
  params.append('size', size.value)
  try {
    const res = await fetchApi('/api/agent-task/list?' + params.toString())
    const data = await res.json()
    if (!data.success) {
      message.value = data.message || '查询失败'
      tasks.value = []
      return
    }
    tasks.value = data.data || []
    total.value = data.total || 0
  } catch (e) {
    message.value = '加载失败，请稍后重试'
    tasks.value = []
  } finally {
    loading.value = false
  }
}

function search() { page.value = 1; loadTasks() }

function resetFilter() {
  kbId.value = ''
  status.value = ''
  keyword.value = ''
  page.value = 1
  loadTasks()
}

function changePage(p) { page.value = p; loadTasks() }

function openDetail(id) {
  detailId.value = id
  showDetail.value = true
}

onActivated(() => {
  loadKbFilter()
  loadTasks()
})
</script>
