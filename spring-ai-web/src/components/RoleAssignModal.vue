<template>
  <div class="modal-mask" @click.self="close">
    <div class="modal">
      <div class="modal-header">
        <h3>分配角色 — {{ props.user.nickname || props.user.username }}</h3>
        <button class="modal-close" @click="close">×</button>
      </div>
      <div class="modal-body">
        <div class="loading-line" v-if="loading">加载中…</div>
        <div class="msg-info error" v-if="errorMsg">{{ errorMsg }}</div>

        <template v-if="!loading">
          <div v-if="roles.length === 0" class="empty" style="padding: 20px 0;">暂无可用角色，请先在「角色管理」中创建。</div>
          <label
            v-for="r in roles"
            :key="r.id"
            style="display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid #e2e8f0; border-radius: 8px; margin-bottom: 8px; cursor: pointer;"
          >
            <input type="checkbox" :value="r.id" v-model="selectedIds" style="width: 16px; height: 16px;">
            <div>
              <div style="font-weight: 600;">
                {{ r.name }}
                <span class="badge" style="margin-left: 6px;" :style="{ background: r.code === 'ADMIN' ? '#dc2626' : '#2563eb' }">{{ r.code }}</span>
              </div>
              <div v-if="r.remark" style="font-size: 12px; color: #94a3b8;">{{ r.remark }}</div>
            </div>
          </label>
          <div class="msg-info info" style="margin-top: 10px;">提示：分配为覆盖式保存。不能移除系统中最后一个管理员的 ADMIN 角色。</div>
        </template>
      </div>
      <div class="modal-footer">
        <button class="btn btn-outline" @click="close">取消</button>
        <button class="btn btn-primary" @click="save" :disabled="loading || saving">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { fetchApi } from '../api/request'
import { showToast } from '../utils/toast'

const props = defineProps({
  user: { type: Object, required: true }
})
const emit = defineEmits(['close', 'updated'])

const roles = ref([])
const selectedIds = ref([])
const loading = ref(true)
const saving = ref(false)
const errorMsg = ref('')

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    // 并行加载全部角色 + 该用户已分配角色
    const [rolesRes, assignedRes] = await Promise.all([
      fetchApi('/api/admin/roles'),
      fetchApi('/api/admin/users/' + props.user.id + '/roles')
    ])
    const rolesData = await rolesRes.json()
    const assignedData = await assignedRes.json()
    if (rolesData.success) roles.value = rolesData.data || []
    if (assignedData.success) selectedIds.value = (assignedData.data || []).map((r) => r.id)
  } catch (e) {
    errorMsg.value = '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const res = await fetchApi('/api/admin/users/' + props.user.id + '/roles', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ roleIds: selectedIds.value })
    })
    const data = await res.json()
    if (!data.success) { showToast(data.message || '保存失败', 'error'); return }
    showToast('角色分配已保存', 'success')
    emit('updated')
    close()
  } catch (e) {
    showToast('保存失败，请稍后重试', 'error')
  } finally {
    saving.value = false
  }
}

function close() { emit('close') }

onMounted(load)
</script>
