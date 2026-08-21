<template>
  <div class="card">
    <div class="card-title">上传 PDF 文档</div>

    <div class="form-row" style="margin-bottom: 16px;">
      <div style="flex: 1; min-width: 240px;">
        <label style="display: block; margin-bottom: 6px; font-weight: 600; color: #475569;">目标知识库</label>
        <select class="select" v-model="kbId" :disabled="kbList.length === 0">
          <option value="">-- 请选择知识库 --</option>
          <option v-for="kb in kbList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
      </div>
      <div style="display: flex; align-items: flex-end;">
        <input type="file" ref="fileInput" accept=".pdf" style="display: none" @change="onFileChange">
        <button class="btn btn-outline" @click="pickFile">选择文件</button>
      </div>
    </div>

    <div
      class="drop-zone"
      :class="{ dragover: dragOver }"
      @click="pickFile"
      @dragover.prevent="dragOver = true"
      @dragleave.prevent="dragOver = false"
      @drop.prevent="handleDrop"
    >
      <div class="dz-icon">📄</div>
      <div class="dz-text">点击选择或将 PDF 拖拽到此处</div>
      <div class="dz-sub">支持 .pdf 格式，单文件最大 50MB</div>
    </div>

    <div class="msg-info" v-if="uploadMsg" :class="uploadType">{{ uploadMsg }}</div>

    <!-- 分阶段进度条 -->
    <div class="stage-wrap" v-if="stageBars.length">
      <div class="stage-bar" v-for="s in stageBars" :key="s.label">
        <div class="sb-top">
          <span>{{ s.label }}</span>
          <span>{{ s.pct }}%</span>
        </div>
        <div class="stage-track">
          <div class="stage-fill" :class="{ done: s.pct >= 100 }" :style="{ width: s.pct + '%' }"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { fetchApi } from '../api/request'
import { showToast } from '../utils/toast'

const kbList = ref([])
const kbId = ref('')
const fileInput = ref(null)
const dragOver = ref(false)
const uploadMsg = ref('')
const uploadType = ref('info')
const stageBars = ref([])
let pollTimer = null

async function loadKbSelectors() {
  try {
    const res = await fetchApi('/api/knowledge-base')
    const data = await res.json()
    if (data.success && Array.isArray(data.data)) kbList.value = data.data
  } catch (e) { /* 忽略 */ }
}
onMounted(loadKbSelectors)

function setMsg(msg, type = 'info') { uploadMsg.value = msg; uploadType.value = type }
function clearTimer() { if (pollTimer) { clearTimeout(pollTimer); pollTimer = null } }

function pickFile() { fileInput.value && fileInput.value.click() }

function onFileChange() { uploadFile() }

function handleDrop(e) {
  dragOver.value = false
  const f = e.dataTransfer && e.dataTransfer.files
  if (f && f.length) {
    if (fileInput.value) {
      const dt = new DataTransfer()
      dt.items.add(f[0])
      fileInput.value.files = dt.files
    }
    uploadFile()
  }
}

async function uploadFile() {
  const file = fileInput.value && fileInput.value.files && fileInput.value.files[0]
  if (!file) return
  if (!kbId.value) { setMsg('请先选择知识库', 'error'); return }

  setMsg(`正在上传 ${file.name} …`, 'info')
  stageBars.value = []

  const fd = new FormData()
  fd.append('file', file)
  fd.append('knowledgeBaseId', kbId.value)

  try {
    const res = await fetchApi('/api/knowledge-document/upload', { method: 'POST', body: fd })
    const data = await res.json()
    if (!data.success) { setMsg(data.message || '上传失败', 'error'); return }
    const verInfo = data.isUpdate ? `（已更新为 v${data.version}）` : `（v${data.version}）`
    setMsg(`「${file.name}」${verInfo} 已提交，正在解析并生成向量索引，任务号：${data.taskNo}`, 'info')
    if (fileInput.value) fileInput.value.value = ''
    pollTask(data.taskNo, file.name, verInfo)
  } catch (e) {
    setMsg('上传失败，请稍后重试', 'error')
  }
}

function buildStageBars(t) {
  return [
    ['PDF 解析', t.parseProgress],
    ['文本切片', t.splitProgress],
    ['Chunk 入库', t.chunkProgress],
    ['向量化 Embedding', t.embedProgress],
    ['Milvus 写入', t.milvusProgress]
  ].map(([label, pct]) => ({ label, pct: Math.max(0, Math.min(100, Math.round(pct || 0))) }))
}

function pollTask(taskNo, fileName, verInfo) {
  clearTimer()
  pollTimer = setTimeout(async () => {
    try {
      const res = await fetchApi('/api/knowledge-document/task/' + encodeURIComponent(taskNo))
      const data = await res.json()
      if (!data.success || !data.data) {
        setMsg('查询任务状态失败：' + (data.message || '未知错误'), 'error')
        return
      }
      const t = data.data
      stageBars.value = buildStageBars(t)

      if (t.status === 2) {
        const cost = t.costTime != null ? `，耗时 ${(t.costTime / 1000).toFixed(1)}s` : ''
        setMsg(`「${fileName}」${verInfo} 处理成功，共 ${t.successChunk || 0} 个片段已入库${cost}`, 'success')
        showToast('文档处理完成', 'success')
      } else if (t.status === 3) {
        setMsg(`「${fileName}」处理失败：${t.errorMessage || '未知错误'}`, 'error')
        showToast('文档处理失败', 'error')
      } else {
        const label = t.status === 0 ? '等待处理' : '处理中'
        const detail = t.status === 1 && t.totalChunk > 0 ? `（${t.successChunk || 0}/${t.totalChunk}）` : ''
        setMsg(`「${fileName}」${verInfo} ${label}${detail} …`)
        pollTask(taskNo, fileName, verInfo)
      }
    } catch (e) {
      setMsg('查询任务状态失败，请到「任务列表」查看', 'error')
    }
  }, 2000)
}
</script>
