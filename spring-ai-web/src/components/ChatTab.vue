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
          <div class="msg-avatar">{{ m.role === 'user' ? '我' : (m.role === 'assistant' ? 'AI' : '!') }}</div>
          <div class="msg-body">
            <template v-if="m.role === 'loading'">
              <div class="msg-loading">
                <span class="dot"></span><span class="dot"></span><span class="dot"></span>
                正在检索知识库并生成回答…
              </div>
            </template>
            <template v-else>
              <div class="msg-text" :class="{ error: m.error }">{{ m.text }}</div>
              <div class="sources" v-if="m.sources && m.sources.length">
                <div class="sources-title">引用来源（{{ m.sources.length }}）</div>
                <div class="source-item" v-for="(s, si) in m.sources" :key="si">
                  <span class="source-name">📄 {{ s.documentName || '未知文档' }} · 片段 {{ s.chunkIndex != null ? s.chunkIndex + 1 : '-' }}</span>
                  <span class="source-score">{{ s.score != null ? '相关度 ' + (s.score * 100).toFixed(1) + '%' : '' }}</span>
                  <button v-if="s.documentId" class="btn btn-outline btn-sm" @click="handleDownload(s)">下载</button>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <div class="chat-input-row" style="border-top: 1px solid #e2e8f0; padding-top: 14px;">
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
  const idx = messages.value.push({ role: 'loading' }) - 1
  question.value = ''
  asking.value = true
  scroll()

  try {
    const res = await fetchApi('/api/knowledge-document/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: q, knowledgeBaseId: Number(kbId.value) })
    })
    const data = await res.json()
    if (data.success) {
      messages.value[idx] = { role: 'assistant', text: data.answer || '', sources: data.sources || [] }
    } else {
      messages.value[idx] = { role: 'assistant', text: data.message || '回答生成失败', error: true }
    }
  } catch (e) {
    messages.value[idx] = { role: 'assistant', text: '请求失败，请检查网络后重试', error: true }
  } finally {
    asking.value = false
    scroll()
  }
}

async function handleDownload(s) {
  const ok = await downloadFile(s.documentId, s.documentName)
  if (!ok) showToast('下载失败', 'error')
}

onMounted(loadKbSelectors)
onActivated(loadKbSelectors)
</script>
