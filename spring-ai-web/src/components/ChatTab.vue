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

    <!-- 知识库选择侧栏 -->
    <div class="card chat-aside">
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
// 会话 ID：多轮对话记忆的 key，刷新页面保持同一会话；清空对话时更换新 ID
// 后端按 userId 隔离记忆（key = rag:chat:memory:{userId}:{sessionId}），
// 前端仅保存随机的会话 ID，换账号登录由后端 userId 维度自动隔离
const sessionId = ref(localStorage.getItem('chatSessionId') || genSessionId())
// 来源高亮定位：点击回答中 [来源N] 后，对应来源条目临时高亮并滚动到可视区
const hlMsgIdx = ref(-1)
const hlSourceIdx = ref(-1)

function genSessionId() {
  if (window.crypto && crypto.randomUUID) return crypto.randomUUID()
  return 's-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

function toggleStreamMode(v) {
  streamMode.value = v
  localStorage.setItem('chatStreamMode', v ? '1' : '0')
}

/**
 * 清空对话：先通知后端删除该会话的多轮记忆（Redis key），
 * 再更换会话 ID（后端按 ID 隔离历史）+ 清空本地消息。
 * 删除失败不阻塞（TTL 7 天会自动过期兜底）。
 */
async function resetSession() {
  try {
    await fetchApi('/api/knowledge-document/chat/clear-memory', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: sessionId.value })
    })
  } catch (e) {
    console.warn('清除会话记忆失败（TTL 会自动兜底过期）', e)
  }
  const id = genSessionId()
  sessionId.value = id
  localStorage.setItem('chatSessionId', id)
  messages.value = []
  nextTick(scroll)
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

onMounted(loadKbSelectors)
onActivated(loadKbSelectors)
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
</style>
