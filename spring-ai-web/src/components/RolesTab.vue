<template>
  <div>
    <!-- 创建 / 编辑角色 -->
    <div class="card" style="margin-bottom: 20px;">
      <div class="card-title">{{ editing ? '编辑角色 — ' + editing.name : '创建角色' }}</div>
      <div class="form-row" style="align-items: center;">
        <input class="input" style="flex: 1; max-width: 200px;" v-model.trim="form.code" placeholder="角色编码（如 EDITOR）" :disabled="editing">
        <input class="input" style="flex: 1; max-width: 220px;" v-model.trim="form.name" placeholder="角色名称">
        <input class="input" style="flex: 2;" v-model.trim="form.remark" placeholder="备注（选填）">
        <button class="btn btn-primary" @click="saveRole" :disabled="saving">{{ saving ? '保存中…' : editing ? '保存修改' : '创建' }}</button>
        <button v-if="editing" class="btn btn-outline" @click="cancelEdit">取消编辑</button>
      </div>
      <div class="msg-info" v-if="formMsg" :class="formMsgType" style="margin-top: 12px;">{{ formMsg }}</div>
    </div>

    <!-- 角色列表 -->
    <div class="card">
      <div class="card-title">角色列表</div>
      <div class="loading-line" v-if="loading">加载中…</div>
      <div class="msg-info error" v-if="errorMsg">{{ errorMsg }}</div>

      <div class="table-wrap" v-if="roles.length">
        <table class="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>编码</th>
              <th>名称</th>
              <th>备注</th>
              <th>用户数</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in roles" :key="r.id">
              <td>{{ r.id }}</td>
              <td>
                <span class="badge" :style="{ background: r.code === 'ADMIN' ? '#dc2626' : '#2563eb' }">{{ r.code }}</span>
              </td>
              <td style="font-weight: 600;">{{ r.name }}</td>
              <td style="color: #64748b;">{{ r.remark || '-' }}</td>
              <td>{{ r.userCount ?? 0 }}</td>
              <td>{{ formatDateTime(r.createTime) }}</td>
              <td style="white-space: nowrap;">
                <button class="btn btn-outline btn-sm" style="margin-right: 6px;" @click="startEdit(r)">编辑</button>
                <button class="btn btn-danger btn-sm" @click="removeRole(r)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="empty" v-else-if="!loading && !errorMsg">暂无角色。</div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { fetchApi } from '../api/request'
import { showToast } from '../utils/toast'
import { formatDateTime } from '../utils/format'

const roles = ref([])
const loading = ref(false)
const errorMsg = ref('')
const form = reactive({ code: '', name: '', remark: '' })
const editing = ref(null)
const saving = ref(false)
const formMsg = ref('')
const formMsgType = ref('info')

async function loadRoles() {
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await fetchApi('/api/admin/roles')
    const data = await res.json()
    if (!data.success) { errorMsg.value = data.message || '查询失败'; roles.value = []; return }
    roles.value = data.data || []
  } catch (e) {
    errorMsg.value = '加载失败，请稍后重试'
    roles.value = []
  } finally {
    loading.value = false
  }
}

function startEdit(r) {
  editing.value = r
  form.code = r.code
  form.name = r.name
  form.remark = r.remark || ''
  formMsg.value = ''
}

function cancelEdit() {
  editing.value = null
  form.code = ''; form.name = ''; form.remark = ''
  formMsg.value = ''
}

async function saveRole() {
  if (!form.code || !form.name) { showToast('角色编码和名称不能为空', 'error'); return }
  saving.value = true
  formMsg.value = ''
  try {
    const payload = { code: form.code, name: form.name, remark: form.remark }
    const url = editing.value ? '/api/admin/roles/' + editing.value.id : '/api/admin/roles'
    const res = await fetchApi(url, {
      method: editing.value ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    const data = await res.json()
    if (!data.success) {
      formMsg.value = data.message || '保存失败'
      formMsgType.value = 'error'
      return
    }
    formMsg.value = editing.value ? '角色已更新' : '角色创建成功'
    formMsgType.value = 'success'
    cancelEdit()
    loadRoles()
  } catch (e) {
    formMsg.value = '网络异常，请重试'
    formMsgType.value = 'error'
  } finally {
    saving.value = false
  }
}

async function removeRole(r) {
  if (!confirm(`确定要删除角色「${r.name}」吗？\n\n角色被删除后，已分配该角色的用户将失去相应权限。`)) return
  try {
    const res = await fetchApi('/api/admin/roles/' + r.id, { method: 'DELETE' })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '删除失败', 'error'); return }
    showToast(`角色「${r.name}」已删除`, 'success')
    loadRoles()
  } catch (e) {
    showToast('删除失败，请稍后重试', 'error')
  }
}

onMounted(loadRoles)
</script>
