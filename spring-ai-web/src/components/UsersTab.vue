<template>
  <div>
    <!-- 创建用户 -->
    <div class="card" style="margin-bottom: 20px;">
      <div class="card-title">创建用户</div>
      <div class="form-row" style="align-items: center;">
        <input class="input" style="flex: 1;" v-model.trim="form.username" placeholder="用户名（登录用）">
        <input class="input" style="flex: 1;" v-model.trim="form.nickname" placeholder="昵称（选填）">
        <input class="input" style="flex: 1;" v-model.trim="form.email" placeholder="邮箱（选填）">
        <input class="input" style="flex: 1;" v-model="form.password" type="password" placeholder="初始密码（至少 6 位）">
        <button class="btn btn-primary" @click="createUser" :disabled="creating">
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </div>
      <div class="msg-info" v-if="createMsg" :class="createMsgType" style="margin-top: 12px;">{{ createMsg }}</div>
    </div>

    <!-- 用户列表 -->
    <div class="card">
      <div class="toolbar">
        <input class="input grow" v-model.trim="keyword" placeholder="按用户名 / 昵称搜索" @keyup.enter="loadUsers">
        <button class="btn btn-primary" @click="loadUsers">查询</button>
        <button class="btn btn-outline" @click="keyword = ''; loadUsers()">重置</button>
      </div>

      <div class="loading-line" v-if="loading">加载中…</div>
      <div class="msg-info error" v-if="errorMsg">{{ errorMsg }}</div>

      <div class="table-wrap" v-if="users.length">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>昵称</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>{{ u.id }}</td>
              <td style="font-weight: 600;">{{ u.username }}</td>
              <td>{{ u.nickname || '-' }}</td>
              <td>{{ u.email || '-' }}</td>
              <td>
                <template v-if="u.roles && u.roles.length">
                  <span class="badge" style="margin-right: 4px; margin-bottom: 2px;" :style="{ background: roleColor(u.roles[0].code) }">{{ u.roles[0].name }}</span>
                  <span v-if="u.roles.length > 1" style="color: #94a3b8; font-size: 12px;">+{{ u.roles.length - 1 }}</span>
                </template>
                <span v-else style="color: #94a3b8;">—</span>
              </td>
              <td>
                <button
                  class="switch"
                  :class="{ on: u.status === 1 }"
                  :title="u.status === 1 ? '点击禁用' : '点击启用'"
                  @click="toggleStatus(u)"
                ></button>
              </td>
              <td>{{ formatDateTime(u.createTime) }}</td>
              <td style="white-space: nowrap;">
                <button class="btn btn-outline btn-sm" style="margin-right: 6px;" @click="openRoleAssign(u)">分配角色</button>
                <button class="btn btn-outline btn-sm" style="margin-right: 6px;" @click="resetPassword(u)">重置密码</button>
                <button class="btn btn-danger btn-sm" @click="removeUser(u)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="empty" v-else-if="!loading && !errorMsg">暂无用户。</div>
    </div>

    <RoleAssignModal v-if="assignUser" :user="assignUser" @close="assignUser = null" @updated="loadUsers" />
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { fetchApi } from '../api/request'
import { showToast } from '../utils/toast'
import { formatDateTime } from '../utils/format'
import RoleAssignModal from './RoleAssignModal.vue'

const form = reactive({
  username: '', nickname: '', email: '', password: ''
})
const creating = ref(false)
const createMsg = ref('')
const createMsgType = ref('info')
const users = ref([])
const keyword = ref('')
const loading = ref(false)
const errorMsg = ref('')
const assignUser = ref(null)

const adminRoleColor = '#dc2626'
function roleColor(code) { return code === 'ADMIN' ? adminRoleColor : '#2563eb' }

async function loadUsers() {
  loading.value = true
  errorMsg.value = ''
  const params = keyword.value.trim() ? '?keyword=' + encodeURIComponent(keyword.value.trim()) : ''
  try {
    const res = await fetchApi('/api/admin/users' + params)
    const data = await res.json()
    if (!data.success) { errorMsg.value = data.message || '查询失败'; users.value = []; return }
    users.value = data.data || []
  } catch (e) {
    errorMsg.value = '加载失败，请稍后重试'
    users.value = []
  } finally {
    loading.value = false
  }
}

async function createUser() {
  if (!form.username || !form.password) { showToast('用户名和密码不能为空', 'error'); return }
  if (form.password.length < 6) { showToast('密码至少 6 位', 'error'); return }
  creating.value = true
  createMsg.value = ''
  try {
    const res = await fetchApi('/api/admin/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: form.username, password: form.password, nickname: form.nickname, email: form.email })
    })
    const data = await res.json()
    if (!data.success) {
      createMsg.value = data.message || '创建失败'
      createMsgType.value = 'error'
      return
    }
    createMsg.value = `用户「${form.username}」创建成功`
    createMsgType.value = 'success'
    form.username = ''; form.nickname = ''; form.email = ''; form.password = ''
    loadUsers()
  } catch (e) {
    createMsg.value = '网络异常，请重试'
    createMsgType.value = 'error'
  } finally {
    creating.value = false
  }
}

async function toggleStatus(u) {
  const target = u.status === 1 ? 0 : 1
  const action = target === 1 ? '启用' : '禁用'
  if (target === 0 && !confirm(`确定要禁用用户「${u.username}」吗？禁用后该用户将无法登录。`)) return
  try {
    const res = await fetchApi('/api/admin/users/' + u.id + '/status', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: target })
    })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '操作失败', 'error'); return }
    showToast(`已${action}用户「${u.username}」`, 'success')
    loadUsers()
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  }
}

async function resetPassword(u) {
  const pwd = prompt(`为「${u.username}」设置新密码（至少 6 位）：`)
  if (pwd === null) return
  if (!pwd || pwd.length < 6) { showToast('密码至少 6 位', 'error'); return }
  try {
    const res = await fetchApi('/api/admin/users/' + u.id + '/password', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: pwd })
    })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '操作失败', 'error'); return }
    showToast('密码已重置', 'success')
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  }
}

async function removeUser(u) {
  if (!confirm(`确定要删除用户「${u.username}」吗？\n\n将同时清理该用户的角色关联与知识库成员授权，且不可恢复。`)) return
  try {
    const res = await fetchApi('/api/admin/users/' + u.id, { method: 'DELETE' })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '删除失败', 'error'); return }
    showToast(`用户「${u.username}」已删除`, 'success')
    loadUsers()
  } catch (e) {
    showToast('操作失败，请稍后重试', 'error')
  }
}

function openRoleAssign(u) { assignUser.value = u }

onMounted(loadUsers)
</script>
