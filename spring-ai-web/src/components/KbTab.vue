<template>
  <div>
    <!-- 创建知识库 -->
    <div class="card" style="margin-bottom: 20px;">
      <div class="card-title">创建知识库</div>
      <div class="form-row" style="align-items: center;">
        <div style="flex: 1; min-width: 200px;">
          <input class="input" v-model.trim="kbName" placeholder="知识库名称（唯一）">
        </div>
        <div style="flex: 2; min-width: 260px;">
          <input class="input" v-model.trim="kbDesc" placeholder="描述（选填）">
        </div>
        <button class="btn btn-primary" @click="createKnowledgeBase" :disabled="creating">
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </div>
      <div class="msg-info" v-if="createMsg" :class="createMsgType" style="margin-top: 12px;">{{ createMsg }}</div>
    </div>

    <!-- 知识库列表 -->
    <div class="card">
      <div class="card-title">知识库列表</div>
      <div class="table-wrap" v-if="kbs.length">
        <table class="table">
          <thead>
            <tr>
              <th>名称</th>
              <th>描述</th>
              <th>创建人</th>
              <th>我的角色</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="kb in kbs" :key="kb.id">
              <td style="font-weight: 600;">{{ kb.name }}</td>
              <td style="max-width: 260px; color: #64748b;">{{ kb.description || '-' }}</td>
              <td>{{ kb.createUserName || '-' }}</td>
              <td>
                <span class="badge" :style="{ background: roleColor(kb.myRole) }">{{ roleName(kb.myRole) }}</span>
              </td>
              <td>{{ formatDateTime(kb.createTime) }}</td>
              <td style="white-space: nowrap;">
                <template v-if="canManage(kb)">
                  <button class="btn btn-outline btn-sm" style="margin-right: 6px;" @click="openMember(kb)">成员授权</button>
                  <button class="btn btn-danger btn-sm" @click="removeKb(kb)">删除</button>
                </template>
                <span v-else style="color: #94a3b8; font-size: 13px;">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="empty" v-else>暂无知识库，请先创建。</div>
    </div>

    <MemberModal v-if="memberKb" :kb="memberKb" @close="memberKb = null" @updated="loadKnowledgeBases" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { fetchApi } from '../api/request'
import { showToast } from '../utils/toast'
import { formatDateTime } from '../utils/format'
import MemberModal from './MemberModal.vue'

const kbName = ref('')
const kbDesc = ref('')
const creating = ref(false)
const createMsg = ref('')
const createMsgType = ref('info')
const kbs = ref([])
const memberKb = ref(null)

const roleMap = {
  OWNER: ['所有者', '#f59e0b'],
  EDITOR: ['编辑者', '#3b82f6'],
  VIEWER: ['查看者', '#10b981']
}
function roleName(r) { return (roleMap[r] || ['未知', '#94a3b8'])[0] }
function roleColor(r) { return (roleMap[r] || ['未知', '#94a3b8'])[1] }
function canManage(kb) { return kb.myRole === 'OWNER' }

async function loadKnowledgeBases() {
  try {
    const res = await fetchApi('/api/knowledge-base')
    const data = await res.json()
    kbs.value = data.success && Array.isArray(data.data) ? data.data : []
  } catch (e) {
    showToast('加载知识库失败', 'error')
  }
}

async function createKnowledgeBase() {
  if (!kbName.value) { showToast('请输入知识库名称', 'error'); return }
  creating.value = true
  createMsg.value = ''
  try {
    const res = await fetchApi('/api/knowledge-base', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: kbName.value, description: kbDesc.value })
    })
    const data = await res.json()
    if (!data.success) {
      createMsg.value = data.message || '创建失败'
      createMsgType.value = 'error'
      return
    }
    createMsg.value = data.message || '创建成功'
    createMsgType.value = 'success'
    kbName.value = ''
    kbDesc.value = ''
    loadKnowledgeBases()
  } catch (e) {
    createMsg.value = '网络异常，请重试'
    createMsgType.value = 'error'
  } finally {
    creating.value = false
  }
}

async function removeKb(kb) {
  if (!confirm(`确定要删除知识库「${kb.name}」吗？\n\n此操作将删除：\n- 知识库记录与成员授权\n- Milvus 向量 Collection\n\n知识库下的文档数据将不可再被检索，且删除不可恢复。`)) return
  try {
    const res = await fetchApi('/api/knowledge-base/' + kb.id, { method: 'DELETE' })
    const data = await res.json()
    if (data.success) {
      showToast(`知识库「${kb.name}」已删除`, 'success')
      loadKnowledgeBases()
    } else {
      showToast('删除失败：' + (data.message || '未知错误'), 'error')
    }
  } catch (e) {
    showToast('删除失败，请稍后重试', 'error')
  }
}

function openMember(kb) { memberKb.value = kb }

onMounted(loadKnowledgeBases)
</script>
