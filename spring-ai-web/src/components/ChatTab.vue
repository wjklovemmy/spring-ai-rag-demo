<template>
  <div class="chat-layout">
    <!-- 对话面板 -->
    <div class="card chat-panel">
      <div class="chat-messages" ref="chatBox">
        <div v-if="messages.length === 0" class="empty">
          选择一个知识库，输入问题开始对话。
          <br>回答将基于知识库文档内容生成，并附引用来源。
        </div>

        <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
          <div class="msg-avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="msg-body">
            <div class="msg-text" :class="{ error: m.error }">
              <template v-for="(p, pi) in parseText(m.text)" :key="pi">
                <span v-if="p.type === 'ref'" class="source-ref" @click="scrollToSource(p.num, i)">[来源{{ p.num }}]</span>
                <template v-else>{{ p.value }}</template>
              </template>
              <span v-if="i === messages.length - 1 && asking && m.role === 'assistant' && !m.error" class="msg-cursor">▍</span>
            </div>
            <div class="sources" v-if="m.sources && m.sources.length">
              <div class="sources-title">引用来源（{{ m.sources.length }}）</div>
              <div class="source-item" v-for="(s, si) in m.sources" :key="si" :class="{ 'source-highlight': hlMsgIdx === i && hlSourceIdx === si }">
                <span class="source-name">
                  <span class="source-badge">来源{{ s.refIndex != null ? s.refIndex : '?' }}</span>
                  📄 {{ s.documentName || '未知文档' }} · 片段 {{ s.chunkIndex != null ? s.chunkIndex + 1 : '-' }}
                </span>
                <span class="source-score">{{ s.score != null ? '相关度 ' + (s.score * 100).toFixed(1) + '%' : '' }}</span>
                <button v-if="s.documentId" class="btn btn-outline btn-sm" @click="handleDownload(s)">下载</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input-row" style="border-top: 1px solid #e2e8f0; padding-top: 14px;">
        <label class="stream-toggle" title="关闭后一次性返回完整回答">
          <input type="checkbox" :checked="streamMode" @change="e => toggleStreamMode(e.target.checked)">
          流式回答
        </label>
        <input
          class="input"
          v-model.trim="question"
          placeholder="请输入你的问题，回车发送…"
          @keyup.enter="ask"
          :disabled="asking"
        >
        <button class="btn btn-primary" @click="ask" :disabled="asking">
          {{ asking ? '生成中…' : '发送' }}
        </button>
        <button class="btn btn-outline btn-sm" @click="resetSession" :disabled="asking || messages.length === 0" title="清空当前会话的对话历史">
          清空对话
        </button>
      </div>
    </div>

    <!-- 会话列表 + 知识库选择侧栏 -->
    <div class="chat-aside">
      <div class="card sessions-card">
        <div class="card-title">会话列表</div>
        <button class="btn btn-primary btn-sm new-session-btn" @click="createNewSession" :disabled="asking">
          ＋ 新建对话
        </button>
        <div class="session-list" v-if="sessions.length">
          <div
            v-for="s in sessions"
            :key="s.sessionId"
            class="session-item"
            :class="{ active: s.sessionId === sessionId }"
            @click="selectSession(s)"
          >
            <span class="session-title" :title="s.title">{{ s.title }}</span>
            <span class="session-del" title="删除会话" @click.stop="deleteSession(s)">✕</span>
          </div>
        </div>
        <div v-else class="msg-info info" style="margin-top: 10px;">
          暂无会话，点击「新建对话」开始。
        </div>
      </div>
      <div class="card kb-card">
        <div class="card-title">选择知识库</div>
        <select class="select" v-model="kbId" :disabled="kbList.length === 0">
          <option value="">-- 请选择 --</option>
          <option v-for="kb in kbList" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
        <div v-if="kbList.length === 0" class="msg-info info" style="margin-top: 10px;">
          暂无可用知识库，请先联系管理员创建。
        </div>
        <div class="msg-info info" style="margin-top: 14px; line-height: 1.8;">
          💡 提示：回答仅基于所选知识库中的文档内容。可通过「文档列表」查看已入库文档。
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated, nextTick } from 'vue'
import { fetchApi, downloadFile } from '../api/request'
import { showToast } from '../utils/toast'

const kbList = ref([])
const kbId = ref('')
const question = ref('')
const messages = ref([])
const chatBox = ref(null)
const asking = ref(false)
// 回答方式：默认流式（SSE），关闭后走一次性 JSON；localStorage 记忆用户偏好
const streamMode = ref(localStorage.getItem('chatStreamMode') !== '0')
// 会话 ID：由后端生成（POST /api/chat-session/create），前端仅保存当前会话 ID
// 用于刷新页面恢复；多轮记忆按 userId 隔离（key = rag:chat:memory:{userId}:{sessionId}）
const sessionId = ref(localStorage.getItem('chatSessionId') || '')
// 当前用户会话列表（后端 chat_session 元数据，消息历史存 Redis）
const sessions = ref([])
// 来源高亮定位：点击回答中 [来源N] 后，对应来源条目临时高亮并滚动到可视区
const hlMsgIdx = ref(-1)
const hlSourceIdx = ref(-1)

function toggleStreamMode(v) {
  streamMode.value = v
  localStorage.setItem('chatStreamMode', v ? '1' : '0')
}

/** 创建会话的并发锁：onMounted/onActivated 可能并发触发，避免重复建会话 */
let sessionCreateInFlight = false

/**
 * 加载当前用户会话列表；校验当前会话 ID 是否仍有效（不在列表中则重置为空，
 * 会话改为「新建对话」或首次提问时惰性创建，避免进页面就写库、并发重复建）。
 * 未登录（接口 401）时静默跳过，保持旧的无会话模式可用。
 */
async function loadSessions() {
  try {
    const res = await fetchApi('/api/chat-session/list')
    if (!res.ok) return
    const data = await res.json()
    if (!Array.isArray(data)) return
    sessions.value = data
    if (sessionId.value && !data.some(s => s.sessionId === sessionId.value)) {
      sessionId.value = ''
      localStorage.removeItem('chatSessionId')
      messages.value = []
    }
  } catch (e) {
    console.warn('加载会话列表失败，保持当前会话', e)
  }
}

/** 新建会话：后端生成 sessionId，清空本地消息；并发调用等待在途创建完成后返回 */
async function createNewSession() {
  while (sessionCreateInFlight) {
    await new Promise(r => setTimeout(r, 50))
  }
  sessionCreateInFlight = true
  try {
    const res = await fetchApi('/api/chat-session/create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ knowledgeBaseId: kbId.value ? Number(kbId.value) : null })
    })
    if (!res.ok) return
    const data = await res.json()
    if (!data || !data.sessionId) return
    sessionId.value = data.sessionId
    localStorage.setItem('chatSessionId', data.sessionId)
    messages.value = []
    loadSessions()
    nextTick(scroll)
  } catch (e) {
    console.warn('创建会话失败', e)
  } finally {
    sessionCreateInFlight = false
  }
}

/**
 * 拉取当前会话的历史消息（Redis 记忆）回显。
 * 404 = 会话已被删除（归属校验失败）→ 重置为无会话状态。
 */
async function loadMessages() {
  if (!sessionId.value) {
    messages.value = []
    return
  }
  try {
    const res = await fetchApi(`/api/chat-session/${sessionId.value}/messages`)
    if (res.ok) {
      const data = await res.json()
      if (Array.isArray(data)) {
        messages.value = data.map(m => ({
          role: m.role,
          text: m.content || '',
          sources: [],
          error: false
        }))
      }
    } else if (res.status === 404) {
      sessionId.value = ''
      localStorage.removeItem('chatSessionId')
      messages.value = []
    }
  } catch (e) {
    console.warn('拉取会话历史失败', e)
  }
}

/** 切换会话：恢复 sessionId 与关联知识库，并拉取该会话的历史消息（点击当前会话也会刷新回显） */
async function selectSession(s) {
  sessionId.value = s.sessionId
  localStorage.setItem('chatSessionId', s.sessionId)
  if (s.knowledgeBaseId) kbId.value = String(s.knowledgeBaseId)
  messages.value = []
  await loadMessages()
  nextTick(scroll)
}

/** 删除会话（后端删 MySQL 元数据 + Redis 记忆）；删除的是当前会话则新建一个 */
async function deleteSession(s) {
  if (!window.confirm(`确定删除会话「${s.title}」？删除后历史不可恢复。`)) return
  try {
    await fetchApi(`/api/chat-session/${s.sessionId}`, { method: 'DELETE' })
  } catch (e) {
    console.warn('删除会话失败', e)
    return
  }
  sessions.value = sessions.value.filter(x => x.sessionId !== s.sessionId)
  if (s.sessionId === sessionId.value) {
    sessionId.value = ''
    localStorage.removeItem('chatSessionId')
    messages.value = []
    createNewSession()
  }
}

/** 清空当前对话：等价于「删除当前会话并新建」 */
async function resetSession() {
  const cur = sessions.value.find(s => s.sessionId === sessionId.value)
  if (cur) {
    await deleteSession(cur)
  } else {
    messages.value = []
    createNewSession()
  }
}

/** 将回答文本按 [来源N] 拆分为 文本/引用 片段，供模板高亮渲染 */
function parseText(text) {
  const parts = []
  if (!text) return parts
  const re = /\[来源(\d+)\]/g
  let last = 0
  let m
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push({ type: 'text', value: text.slice(last, m.index) })
    parts.push({ type: 'ref', num: Number(m[1]) })
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push({ type: 'text', value: text.slice(last) })
  return parts
}

/** 点击回答中的 [来源N]：定位到该消息对应来源条目并短暂高亮 */
function scrollToSource(num, msgIdx) {
  const m = messages.value[msgIdx]
  if (!m || !Array.isArray(m.sources)) return
  const si = m.sources.findIndex(s => s.refIndex === num)
  if (si < 0) return
  hlMsgIdx.value = msgIdx
  hlSourceIdx.value = si
  nextTick(() => {
    const el = chatBox.value && chatBox.value.querySelector('.source-highlight')
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  })
  clearTimeout(scrollToSource._timer)
  scrollToSource._timer = setTimeout(() => {
    hlMsgIdx.value = -1
    hlSourceIdx.value = -1
  }, 2500)
}

async function loadKbSelectors() {
  try {
    const res = await fetchApi('/api/knowledge-base')
    const data = await res.json()
    if (data.success && Array.isArray(data.data)) kbList.value = data.data
  } catch (e) {
    console.error('加载知识库列表失败', e)
  }
}

function scroll() {
  nextTick(() => {
    if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
  })
}

async function ask() {
  const q = question.value
  if (!q || asking.value) return
  if (!kbId.value) {
    showToast('请先选择知识库', 'error')
    return
  }
  // 尚无会话 ID（首次提问/原会话已失效）时先由后端创建，保证会话列表与记忆落库
  if (!sessionId.value) {
    await createNewSession()
    if (!sessionId.value) {
      showToast('创建会话失败，请重试', 'error')
      return
    }
  }
  messages.value.push({ role: 'user', text: q })
  const idx = messages.value.push({ role: 'assistant', text: '', sources: [], error: false }) - 1
  question.value = ''
  asking.value = true
  scroll()

  try {
    const res = await fetchApi('/api/knowledge-document/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: q, knowledgeBaseId: Number(kbId.value), stream: streamMode.value, sessionId: sessionId.value })
    })
    const ct = (res.headers.get('content-type') || '').toLowerCase()
    if (!ct.includes('text/event-stream')) {
      // 同步回答 / 错误响应（400/403/500 等）：一次性 JSON
      const data = await res.json().catch(() => null)
      const m = messages.value[idx]
      if (data && data.success) {
        m.text = data.answer || ''
        m.sources = Array.isArray(data.sources) ? data.sources : []
        m.error = false
      } else {
        m.error = true
        m.text = (data && data.message) ? data.message : ('请求失败（HTTP ' + res.status + '）')
      }
      return
    }
    await readSseStream(res.body, idx)
  } catch (e) {
    console.error('问答流式请求失败', e)
    const m = messages.value[idx]
    m.error = true
    m.text = m.text || '请求失败，请检查网络后重试'
  } finally {
    asking.value = false
    scroll()
    // 首问后会话标题由后端生成，刷新列表保持最新
    loadSessions()
  }
}

/** 读取 SSE 响应流，按空行分隔事件并交给 handleSseEvent 处理 */
async function readSseStream(body, idx) {
  const reader = body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let sep
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      handleSseEvent(raw, idx)
    }
  }
  // 处理结尾残留（无空行结尾的最后一个事件）
  if (buffer.trim()) handleSseEvent(buffer, idx)
}

/** 解析单个 SSE 事件块（data 为 JSON），按 type 更新消息：delta 追加文本 / sources 设引用 / error 报错 */
function handleSseEvent(raw, idx) {
  let dataStr = ''
  for (const line of raw.split('\n')) {
    if (line.startsWith('data:')) {
      const val = line.slice(5).trimStart()
      dataStr += (dataStr ? '\n' : '') + val
    }
  }
  if (!dataStr) return
  let evt
  try {
    evt = JSON.parse(dataStr)
  } catch (e) {
    return
  }
  const m = messages.value[idx]
  if (!m) return
  if (evt.type === 'delta') {
    m.text += evt.content || ''
    scroll()
  } else if (evt.type === 'final') {
    // 生成完毕后后端下发的引用对齐校验后的完整回答，整体覆盖增量拼接结果（强制纠正编号）
    if (evt.content) {
      m.text = evt.content
      scroll()
    }
  } else if (evt.type === 'sources') {
    const all = Array.isArray(evt.sources) ? evt.sources : []
    // 只保留回答中实际引用的来源：流式模式下后端返回全部候选（生成前无法预知引用），
    // 此处从完整回答文本提取 [来源N] 过滤；同步模式后端已精准返回，过滤后结果不变
    const cited = new Set()
    const re = /\[来源(\d+)\]/g
    let mm
    while ((mm = re.exec(m.text))) cited.add(Number(mm[1]))
    m.sources = cited.size ? all.filter(s => cited.has(s.refIndex)) : []
    scroll()
  } else if (evt.type === 'error') {
    m.error = true
    m.text = evt.message || '回答生成失败'
    scroll()
  }
  // type === 'done' 无需处理
}

async function handleDownload(s) {
  const ok = await downloadFile(s.documentId, s.documentName)
  if (!ok) showToast('下载失败', 'error')
}

async function initChatTab() {
  loadKbSelectors()
  await loadSessions()
  // 刷新页面后自动回显当前会话历史（sessionId 已存 localStorage）
  loadMessages()
}
onMounted(initChatTab)
onActivated(initChatTab)
</script>

<style scoped>
.msg-cursor {
  display: inline-block;
  margin-left: 2px;
  color: #2563eb;
  animation: msg-cursor-blink 1s step-start infinite;
}
@keyframes msg-cursor-blink {
  50% { opacity: 0; }
}
.stream-toggle {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-right: 12px;
  white-space: nowrap;
  font-size: 13px;
  color: #64748b;
  cursor: pointer;
  user-select: none;
}
.stream-toggle input {
  cursor: pointer;
}
.source-ref {
  display: inline-block;
  margin: 0 2px;
  padding: 0 5px;
  border-radius: 4px;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 600;
  font-size: 0.92em;
  line-height: 1.5;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}
.source-ref:hover {
  background: #bfdbfe;
}
.source-badge {
  display: inline-block;
  margin-right: 5px;
  padding: 0 5px;
  border-radius: 4px;
  background: #dbeafe;
  color: #1d4ed8;
  font-weight: 600;
  font-size: 0.85em;
  line-height: 1.6;
  white-space: nowrap;
}
.source-highlight {
  outline: 2px solid #2563eb;
  background: #eff6ff;
  border-radius: 6px;
}

/* ===== 会话列表 + 知识库侧栏 ===== */
.chat-aside {
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 260px;
  flex-shrink: 0;
}
.sessions-card,
.kb-card {
  padding: 16px;
}
.session-list {
  margin-top: 10px;
  max-height: 300px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #334155;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  transition: background 0.15s;
}
.session-item:hover {
  background: #f1f5f9;
}
.session-item.active {
  background: #dbeafe;
  border-color: #93c5fd;
  color: #1d4ed8;
  font-weight: 600;
}
.session-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-del {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  line-height: 16px;
  text-align: center;
  border-radius: 50%;
  color: #94a3b8;
  font-size: 12px;
  visibility: hidden;
}
.session-item:hover .session-del,
.session-item.active .session-del {
  visibility: visible;
}
.session-del:hover {
  background: #fecaca;
  color: #dc2626;
}
.new-session-btn {
  margin-top: 10px;
  width: 100%;
}
</style>
