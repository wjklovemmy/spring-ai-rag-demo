<template>
  <div>
    <!-- 顶部概览与操作 -->
    <div class="card" style="margin-bottom: 20px;">
      <div class="memory-head">
        <div style="min-width: 0;">
          <div class="card-title" style="margin-bottom: 4px;">我的长期记忆</div>
          <div class="memory-sub">
            AI 在问答中自动记住的个人事实 / 偏好 / 经历，仅自己可见，跨会话、跨知识库持续生效。
            共 {{ memories.length }} 条。点击重要度圆标可修改（需 1-10），来源会话可筛选。
          </div>
        </div>
        <div style="display: flex; gap: 10px; flex-shrink: 0; align-items: center;">
          <button class="btn btn-outline" @click="extractNow" :disabled="extracting">
            {{ extracting ? '沉淀中…' : '从近期会话沉淀' }}
          </button>
          <button class="btn btn-danger" @click="clearAllMemories" :disabled="!memories.length">
            清除全部
          </button>
          <button class="btn btn-primary" @click="openAdd">手动新增</button>
        </div>
      </div>
      <div class="msg-info info" style="margin-top: 4px;">
        沉淀：把近期会话中透露的个人信息批量提取为长期记忆（同一用户约 30 分钟内自动执行一次，这里可立即触发）。
      </div>
    </div>

    <!-- 记忆列表 -->
    <div class="card">
      <div class="toolbar">
        <select class="select" v-model="category" style="min-width: 120px;">
          <option value="">全部类别</option>
          <option v-for="c in CATEGORY_LIST" :key="c" :value="c">{{ CATEGORY_META[c].label }}</option>
        </select>
        <select class="select" v-model="session" style="min-width: 160px;">
          <option value="">全部来源会话</option>
          <option v-for="opt in sessionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <input class="input grow" v-model.trim="keyword" placeholder="按内容关键词筛选">
        <button class="btn btn-outline" @click="loadMemories">刷新</button>
      </div>

      <div class="loading-line" v-if="loading">加载中…</div>
      <div class="msg-info error" v-if="errorMsg">{{ errorMsg }}</div>

      <div class="table-wrap" v-if="filtered.length">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 60px;">ID</th>
              <th>内容</th>
              <th style="width: 80px;">类别</th>
              <th style="width: 90px;">重要度</th>
              <th style="width: 130px;">来源</th>
              <th style="width: 76px;">向量</th>
              <th style="width: 140px;">创建时间</th>
              <th style="width: 84px;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in filtered" :key="m.id">
              <td style="color: #94a3b8;">{{ m.id }}</td>
              <td>
                <div class="cell-content" :title="m.content">{{ m.content }}</div>
              </td>
              <td>
                <span class="cat-badge" :style="{ background: categoryMeta(m.category).color }">
                  {{ categoryMeta(m.category).label }}
                </span>
              </td>
              <td>
                <span class="imp-dot clickable"
                      :style="{ background: importanceColor(m.importance) }"
                      :title="'重要度 ' + m.importance + ' / 10，点击修改'"
                      @click="openImportanceEdit(m)">{{ m.importance }}</span>
              </td>
              <td>
                <span class="source-tag" :class="isManual(m.sourceSession) ? 'manual' : 'session'"
                      :title="isManual(m.sourceSession) ? '手动添加' : m.sourceSession">
                  {{ sourceShort(m.sourceSession) }}
                </span>
              </td>
              <td>
                <span class="vec" :class="m.vectorStatus === 1 ? 'ok' : 'pending'">
                  {{ m.vectorStatus === 1 ? '已同步' : '待同步' }}
                </span>
              </td>
              <td>
                <div class="cell-time" :title="'更新于 ' + formatDateTime(m.updateTime)">{{ formatMinute(m.createTime) }}</div>
              </td>
              <td style="white-space: nowrap;">
                <button class="btn btn-danger btn-sm" @click="removeMemory(m)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="empty" v-else-if="!loading && !errorMsg && memories.length">
        没有符合筛选条件的记忆。
      </div>
      <div class="empty" v-else-if="!loading && !errorMsg && !memories.length">
        <p>还没有长期记忆。</p>
        <p style="margin-top: 8px; color: #64748b; line-height: 1.8;">
          去「知识问答」和 AI 聊天（例如告诉它“我是产品经理，平时爱喝拿铁”），
          AI 会自动记住你的个人事实与偏好；<br>
          也可以点上方「从近期会话沉淀」立即提取，或「手动新增」直接添加。
        </p>
      </div>
    </div>

    <!-- 手动新增模态框 -->
    <div class="modal-mask" v-if="showAdd" @click.self="showAdd = false">
      <div class="modal">
        <div class="modal-header">
          <h3>新增长期记忆</h3>
          <button class="modal-close" @click="showAdd = false">&times;</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label>记忆内容 <span style="color: #b91c1c;">*</span></label>
            <textarea
              class="textarea"
              rows="3"
              v-model.trim="addForm.content"
              maxlength="500"
              placeholder="一条记忆 = 一个简短具体的个人事实/偏好，如：我的岗位是产品经理，平时喜欢喝拿铁"
            ></textarea>
            <div style="text-align: right; font-size: 12px; color: #94a3b8;">{{ addForm.content.length }} / 500</div>
          </div>
          <div class="form-row">
            <div class="form-group" style="flex: 1;">
              <label>类别</label>
              <select class="select" v-model="addForm.category">
                <option v-for="c in CATEGORY_LIST" :key="c" :value="c">{{ CATEGORY_META[c].label }}</option>
              </select>
            </div>
            <div class="form-group" style="flex: 1;">
              <label>重要度（1-10）</label>
              <select class="select" v-model.number="addForm.importance">
                <option v-for="n in 10" :key="n" :value="n">{{ n }}（{{ importanceHint(n) }}）</option>
              </select>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-outline" @click="showAdd = false">取消</button>
          <button class="btn btn-primary" @click="saveAdd" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </div>
    </div>

    <!-- 修改重要度模态框 -->
    <div class="modal-mask" v-if="showImportanceEdit" @click.self="closeImportanceEdit">
      <div class="modal" style="width: 400px;">
        <div class="modal-header">
          <h3>修改重要度</h3>
          <button class="modal-close" @click="closeImportanceEdit">&times;</button>
        </div>
        <div class="modal-body">
          <div class="msg-info info" style="margin-bottom: 12px; word-break: break-word;">
            {{ editTarget ? editTarget.content : '' }}
          </div>
          <div class="form-group">
            <label>重要度（1-10）</label>
            <select class="select" v-model.number="editImportance">
              <option v-for="n in 10" :key="n" :value="n">{{ n }}（{{ importanceHint(n) }}）</option>
            </select>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-outline" @click="closeImportanceEdit">取消</button>
          <button class="btn btn-primary" @click="saveImportanceEdit" :disabled="savingImportance">
            {{ savingImportance ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onActivated, onDeactivated, onUnmounted } from 'vue'
import { fetchApi } from '../api/request'
import { showToast } from '../utils/toast'
import { formatDateTime } from '../utils/format'

const CATEGORY_META = {
  fact: { label: '事实', color: '#2563eb' },
  preference: { label: '偏好', color: '#7c3aed' },
  interest: { label: '兴趣', color: '#0ea5e9' },
  goal: { label: '目标', color: '#059669' },
  event: { label: '经历', color: '#d97706' }
}
const CATEGORY_LIST = Object.keys(CATEGORY_META)

const memories = ref([])
const loading = ref(false)
const errorMsg = ref('')

// 静默轮询：页面激活期间每 15s 拉一次列表，自动反映后台向量补偿等状态变化（不打扰用户）
const POLL_INTERVAL = 15000
let pollTimer = null
let pollInFlight = false
const category = ref('')
const session = ref('')
const keyword = ref('')

const showAdd = ref(false)
const saving = ref(false)
const extracting = ref(false)
const addForm = reactive({ content: '', category: 'fact', importance: 5 })

const showImportanceEdit = ref(false)
const editTarget = ref(null)
const editImportance = ref(5)
const savingImportance = ref(false)

function categoryMeta(v) {
  return CATEGORY_META[v] || { label: v || '其他', color: '#64748b' }
}

function importanceColor(n) {
  if (n >= 8) return '#dc2626'
  if (n >= 5) return '#d97706'
  if (n >= 3) return '#2563eb'
  return '#94a3b8'
}

function importanceHint(n) {
  if (n >= 8) return '很重要'
  if (n >= 5) return '一般'
  return '次要'
}

function isManual(s) {
  return !s || s === 'manual'
}

function sessionIdOf(s) {
  if (isManual(s)) return ''
  const i = s.indexOf(':')
  return i >= 0 ? s.substring(i + 1) : s
}

function sourceShort(s) {
  if (isManual(s)) return '手动'
  const id = sessionIdOf(s)
  return '会话 ' + (id.length > 12 ? id.substring(0, 12) + '…' : id)
}

function formatMinute(v) {
  if (!v) return '-'
  const d = new Date(v)
  if (isNaN(d.getTime())) return String(v)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

// 来源会话下拉：手动添加 + 各来源会话（按列表中出现顺序）
const sessionOptions = computed(() => {
  const seen = new Set()
  const options = []
  for (const m of memories.value) {
    const key = isManual(m.sourceSession) ? 'manual' : m.sourceSession
    if (seen.has(key)) continue
    seen.add(key)
    options.push({ value: key, label: isManual(m.sourceSession) ? '手动添加' : sourceShort(m.sourceSession) })
  }
  return options
})

const filtered = computed(() => {
  const kw = keyword.value.toLowerCase()
  return memories.value.filter((m) => {
    if (category.value && m.category !== category.value) return false
    if (session.value && !isManual(m.sourceSession) && m.sourceSession !== session.value) return false
    if (session.value === 'manual' && !isManual(m.sourceSession)) return false
    if (kw && !(m.content || '').toLowerCase().includes(kw)) return false
    return true
  })
})

async function loadMemories(silent = false) {
  // silent=true（轮询）：不闪 loading、不覆盖用户可见的错误提示，失败静默忽略等下一轮
  if (!silent) {
    loading.value = true
    errorMsg.value = ''
  }
  try {
    const res = await fetchApi('/api/memory/list')
    const data = await res.json()
    if (!data.success) {
      if (!silent) { errorMsg.value = data.message || '查询失败'; memories.value = [] }
      return
    }
    memories.value = data.data || []
  } catch (e) {
    if (!silent) {
      errorMsg.value = '加载失败，请稍后重试'
      memories.value = []
    }
  } finally {
    if (!silent) loading.value = false
  }
}

function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (pollInFlight || loading.value) return
    pollInFlight = true
    try {
      await loadMemories(true)
    } finally {
      pollInFlight = false
    }
  }, POLL_INTERVAL)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function openAdd() {
  addForm.content = ''
  addForm.category = 'fact'
  addForm.importance = 5
  showAdd.value = true
}

async function saveAdd() {
  if (!addForm.content) { showToast('记忆内容不能为空', 'error'); return }
  saving.value = true
  try {
    const res = await fetchApi('/api/memory', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        content: addForm.content,
        category: addForm.category,
        importance: addForm.importance
      })
    })
    const data = await res.json()
    if (data.duplicate) {
      showToast(data.message || '已有相似记忆，未重复保存', 'info')
      showAdd.value = false
      return
    }
    if (!data.success) { showToast(data.message || '保存失败', 'error'); return }
    showToast('已保存长期记忆', 'success')
    showAdd.value = false
    loadMemories()
  } catch (e) {
    showToast('网络异常，请稍后重试', 'error')
  } finally {
    saving.value = false
  }
}

async function removeMemory(m) {
  if (!confirm(`确定要删除这条长期记忆吗？\n\n${m.content}`)) return
  try {
    const res = await fetchApi('/api/memory/' + m.id, { method: 'DELETE' })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '删除失败', 'error'); return }
    showToast('已删除该条记忆', 'success')
    loadMemories()
  } catch (e) {
    showToast('删除失败，请稍后重试', 'error')
  }
}

function openImportanceEdit(m) {
  editTarget.value = m
  editImportance.value = m.importance
  showImportanceEdit.value = true
}

function closeImportanceEdit() {
  showImportanceEdit.value = false
  editTarget.value = null
}

async function saveImportanceEdit() {
  const m = editTarget.value
  if (!m) return
  savingImportance.value = true
  try {
    const res = await fetchApi('/api/memory/' + m.id, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ importance: editImportance.value })
    })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '更新失败', 'error'); return }
    m.importance = editImportance.value
    showToast(data.message || '重要度已更新', 'success')
    closeImportanceEdit()
  } catch (e) {
    showToast('网络异常，请稍后重试', 'error')
  } finally {
    savingImportance.value = false
  }
}

async function clearAllMemories() {
  const n = memories.value.length
  if (!n) { showToast('当前没有可清除的记忆', 'info'); return }
  if (!confirm(`确定要清除全部 ${n} 条长期记忆吗？\n此操作不可恢复，向量数据将一并删除。`)) return
  try {
    const res = await fetchApi('/api/memory', { method: 'DELETE' })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '清除失败', 'error'); return }
    showToast(data.message || '已清除', 'success')
    loadMemories()
  } catch (e) {
    showToast('网络异常，请稍后重试', 'error')
  }
}

async function extractNow() {
  extracting.value = true
  try {
    const res = await fetchApi('/api/memory/extract', { method: 'POST' })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '沉淀失败', 'error'); return }
    showToast(data.message || '沉淀完成', 'success')
    loadMemories()
  } catch (e) {
    showToast('网络异常，请稍后重试', 'error')
  } finally {
    extracting.value = false
  }
}

// KeepAlive：首次挂载与每次切回该 Tab 都立即刷新并启动静默轮询
//（会话结束后的自动抽取结果、后台向量补偿完成的"待同步→已同步"无需手动刷新即可看到）
onActivated(() => {
  loadMemories()
  startPolling()
})
onDeactivated(stopPolling)
onUnmounted(stopPolling)
</script>

<style scoped>
.memory-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.memory-sub {
  color: #64748b;
  font-size: 13px;
  line-height: 1.6;
  margin-bottom: 10px;
}
.cell-content {
  max-width: 380px;
  word-break: break-word;
  line-height: 1.55;
}
.cat-badge {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  white-space: nowrap;
}
.imp-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}
.imp-dot.clickable {
  cursor: pointer;
  transition: transform 0.15s ease, filter 0.15s ease;
}
.imp-dot.clickable:hover {
  transform: scale(1.15);
  filter: brightness(1.1);
}
.source-tag {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: bottom;
}
.source-tag.manual {
  background: #f1f5f9;
  color: #64748b;
}
.source-tag.session {
  background: #eff6ff;
  color: #2563eb;
}
.cell-time {
  color: #64748b;
  white-space: nowrap;
}
.vec { font-size: 12px; }
.vec.ok { color: #16a34a; }
.vec.pending { color: #d97706; }
</style>
