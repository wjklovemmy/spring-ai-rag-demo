<template>
  <div class="modal-mask" @click.self="close">
    <div class="modal">
      <div class="modal-header">
        <h3>成员授权 — {{ props.kb.name }}</h3>
        <button class="modal-close" @click="close">×</button>
      </div>
      <div class="modal-body">
        <div class="loading-line" v-if="loading">加载中…</div>
        <div class="msg-info error" v-if="errorMsg">{{ errorMsg }}</div>

        <template v-if="!loading">
          <!-- 当前成员 -->
          <div class="card-title" style="font-size: 14px;">当前成员（{{ members.length }}）</div>
          <div v-if="members.length === 0" class="empty" style="padding: 16px 0;">暂无成员</div>
          <div class="member-row" v-for="m in members" :key="m.userId">
            <div class="member-info">
              <div class="member-avatar">{{ (m.nickname || m.username || '?').charAt(0) }}</div>
              <div>
                <div style="font-weight: 600;">{{ m.nickname || m.username }}</div>
                <div v-if="m.nickname && m.username !== m.nickname" style="font-size: 12px; color: #94a3b8;">@{{ m.username }}</div>
              </div>
            </div>
            <div style="display: flex; align-items: center; gap: 10px;">
              <span class="badge" :style="{ background: roleColor(m.role) }">{{ roleName(m.role) }}</span>
              <button class="btn btn-danger btn-sm" @click="removeMember(m)">移除</button>
            </div>
          </div>

          <!-- 添加成员 -->
          <div style="margin-top: 20px; border-top: 1px solid #e2e8f0; padding-top: 16px;">
            <div class="card-title" style="font-size: 14px;">添加成员</div>
            <div class="form-row" style="align-items: flex-end;">
              <div style="flex: 1; min-width: 180px;">
                <input class="input" v-model.trim="searchInput" placeholder="输入用户名 / 昵称搜索" @keyup.enter="searchCandidates">
              </div>
              <select class="select" v-model="roleSelect" style="width: 130px;">
                <option value="VIEWER">查看者</option>
                <option value="EDITOR">编辑者</option>
                <option value="OWNER">所有者</option>
              </select>
              <button class="btn btn-outline" @click="searchCandidates" :disabled="!searchInput">搜索</button>
            </div>

            <div class="search-result" v-if="candidates.length">
              <div class="member-row" v-for="c in candidates" :key="c.id">
                <div class="member-info">
                  <div class="member-avatar">{{ (c.nickname || c.username || '?').charAt(0) }}</div>
                  <div>
                    <div style="font-weight: 600;">{{ c.nickname || c.username }}</div>
                    <div v-if="c.nickname && c.username !== c.nickname" style="font-size: 12px; color: #94a3b8;">@{{ c.username }}</div>
                  </div>
                </div>
                <button class="btn btn-primary btn-sm" @click="addMember(c)">添加</button>
              </div>
            </div>
            <div v-else-if="searched && !searching" class="empty" style="padding: 12px 0;">未找到匹配用户</div>
          </div>
        </template>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" @click="close">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { fetchApi } from '../api/request'
import { showToast } from '../utils/toast'

const props = defineProps({
  kb: { type: Object, required: true }
})
const emit = defineEmits(['close', 'updated'])

const members = ref([])
const loading = ref(true)
const errorMsg = ref('')
const searchInput = ref('')
const roleSelect = ref('VIEWER')
const candidates = ref([])
const searched = ref(false)
const searching = ref(false)

const roleMap = {
  OWNER: ['所有者', '#f59e0b'],
  EDITOR: ['编辑者', '#3b82f6'],
  VIEWER: ['查看者', '#10b981']
}
function roleName(r) { return (roleMap[r] || ['未知', '#94a3b8'])[0] }
function roleColor(r) { return (roleMap[r] || ['未知', '#94a3b8'])[1] }

async function loadMembers() {
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await fetchApi('/api/knowledge-base/' + props.kb.id + '/members')
    const data = await res.json()
    if (!data.success) { errorMsg.value = data.message || '加载失败' }
    else members.value = data.data || []
  } catch (e) {
    errorMsg.value = '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function searchCandidates() {
  const kw = searchInput.value.trim()
  if (!kw) return
  searching.value = true
  searched.value = true
  candidates.value = []
  try {
    const res = await fetchApi('/api/users/search?keyword=' + encodeURIComponent(kw))
    const data = await res.json()
    const list = data.success && Array.isArray(data.data) ? data.data : []
    // 过滤已在知识库中的成员
    const memberIds = new Set(members.value.map((m) => String(m.userId)))
    candidates.value = list.filter((u) => !memberIds.has(String(u.id)))
  } catch (e) {
    showToast('搜索失败，请稍后重试', 'error')
  } finally {
    searching.value = false
  }
}

async function addMember(c) {
  try {
    const res = await fetchApi('/api/knowledge-base/' + props.kb.id + '/members', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: c.id, role: roleSelect.value })
    })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '添加失败', 'error'); return }
    showToast(`已将 ${c.nickname || c.username} 添加为${roleName(roleSelect.value)}`, 'success')
    candidates.value = candidates.value.filter((x) => x.id !== c.id)
    await loadMembers()
    emit('updated')
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  }
}

async function removeMember(m) {
  if (!confirm(`确定将成员「${m.nickname || m.username}」移出知识库吗？`)) return
  try {
    const res = await fetchApi('/api/knowledge-base/' + props.kb.id + '/members/' + m.userId, { method: 'DELETE' })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '移除失败', 'error'); return }
    showToast('已移除成员', 'success')
    await loadMembers()
    emit('updated')
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  }
}

function close() { emit('close') }

onMounted(loadMembers)
</script>
