<template>
  <div class="card">
    <div class="card-title">文档列表</div>

    <div class="toolbar">
      <select class="select" v-model="kbId">
        <option value="">全部知识库</option>
        <option v-for="kb in kbFilterList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
      </select>
      <input class="input grow" v-model.trim="keyword" placeholder="按文件名 / 关键字搜索" @keyup.enter="loadDocList">
      <button class="btn btn-primary" @click="loadDocList">查询</button>
      <button class="btn btn-outline" @click="resetFilter">重置</button>
    </div>

    <div class="msg-info info" v-if="!loaded">点击「查询」加载文档列表。</div>
    <div class="loading-line" v-if="loading">加载中…</div>
    <div class="msg-info error" v-if="message">{{ message }}</div>

    <div class="table-wrap" v-if="docs.length">
      <table class="table">
        <thead>
          <tr>
            <th>文件名</th>
            <th>知识库</th>
            <th>上传人</th>
            <th>大小</th>
            <th>状态</th>
            <th>版本</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in docs" :key="d.id">
            <td style="max-width: 260px;">
              <div style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" :title="d.fileName">{{ d.fileName }}</div>
            </td>
            <td>{{ d.kbName || d.knowledgeBaseName || '-' }}</td>
            <td>{{ d.uploaderName || d.uploadUser || '-' }}</td>
            <td>{{ formatSize(d.fileSize) }}</td>
            <td>
              <span class="badge" :style="{ background: docStatus(d)[1] }">{{ docStatus(d)[0] }}</span>
            </td>
            <td>v{{ d.version ?? '-' }}</td>
            <td>{{ formatDateTime(d.updateTime) }}</td>
            <td style="white-space: nowrap;">
              <button class="btn btn-outline btn-sm" style="margin-right: 6px;" @click="onDownload(d)">下载</button>
              <button class="btn btn-danger btn-sm" @click="removeDoc(d)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="empty" v-else-if="loaded && !loading && !message">
      没有找到匹配的文档。
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { fetchApi, downloadFile } from '../api/request'
import { showToast } from '../utils/toast'
import { formatDateTime, formatSize } from '../utils/format'

const kbFilterList = ref([])
const kbId = ref('')
const keyword = ref('')
const docs = ref([])
const loading = ref(false)
const loaded = ref(false)
const message = ref('')

const statusMap = {
  0: ['上传中', '#93c5fd'],
  1: ['解析中', '#a5b4fc'],
  2: ['向量化中', '#fbbf24'],
  3: ['成功', '#16a34a'],
  4: ['失败', '#dc2626'],
  5: ['已废弃', '#94a3b8'],
  6: ['已过期', '#64748b']
}
function docStatus(d) {
  const s = statusMap[d.status]
  if (!s) return [d.statusText || '未知', '#94a3b8']
  return [d.statusText || s[0], s[1]]
}

async function loadKbFilter() {
  try {
    const res = await fetchApi('/api/knowledge-document/knowledge-bases')
    const data = await res.json()
    if (data.success && Array.isArray(data.data)) kbFilterList.value = data.data
  } catch (e) { /* 忽略 */ }
}

async function loadDocList() {
  loading.value = true
  loaded.value = true
  message.value = ''
  const params = new URLSearchParams()
  if (kbId.value) params.append('knowledgeBaseId', kbId.value)
  if (keyword.value.trim()) params.append('keyword', keyword.value.trim())
  try {
    const res = await fetchApi('/api/knowledge-document/list?' + params.toString())
    const data = await res.json()
    if (!data.success) {
      message.value = data.message || '查询失败'
      docs.value = []
      return
    }
    docs.value = data.data || []
  } catch (e) {
    message.value = '加载失败，请稍后重试'
    docs.value = []
  } finally {
    loading.value = false
  }
}

function resetFilter() {
  kbId.value = ''
  keyword.value = ''
  loadDocList()
}

async function onDownload(d) {
  const ok = await downloadFile(d.id, d.fileName)
  if (!ok) showToast('下载失败，请稍后重试', 'error')
}

async function removeDoc(d) {
  if (!confirm(`确定要删除「${d.fileName}」吗？\n\n此操作将同时删除文档记录、Chunk 数据、MinIO 文件与 Milvus 向量，且不可恢复。`)) return
  try {
    const res = await fetchApi('/api/knowledge-document/' + d.id, { method: 'DELETE' })
    const data = await res.json()
    if (data.success) {
      showToast(`「${d.fileName}」已删除`, 'success')
      loadDocList()
    } else {
      showToast('删除失败：' + (data.message || '未知错误'), 'error')
    }
  } catch (e) {
    showToast('删除失败，请稍后重试', 'error')
  }
}

onMounted(loadKbFilter)
</script>
