<template>
  <div>
    <!-- 概览统计 -->
    <div class="card" style="margin-bottom: 20px;">
      <div class="memory-head">
        <div style="min-width: 0;">
          <div class="card-title" style="margin-bottom: 4px;">长期记忆全局概览</div>
          <div class="memory-sub">
            全站用户的长期记忆统计与明细（仅管理员可见）：总数、近期新增、类别分布与待向量同步记录，
            便于观察自动沉淀链路的运行健康度。
          </div>
        </div>
        <div style="display: flex; gap: 10px; flex-shrink: 0; align-items: center;">
          <button class="btn btn-outline" @click="loadAll">刷新</button>
        </div>
      </div>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid" style="margin-bottom: 20px;">
      <div class="stat-card">
        <div class="stat-num">{{ stats.total ?? 0 }}</div>
        <div class="stat-label">记忆总数</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.usersWithMemory ?? 0 }}</div>
        <div class="stat-label">有记忆的用户</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.todayNew ?? 0 }}</div>
        <div class="stat-label">今日新增</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ stats.weekNew ?? 0 }}</div>
        <div class="stat-label">近 7 天新增</div>
      </div>
      <div class="stat-card">
        <div class="stat-num" :style="{ color: (stats.pendingVector ?? 0) > 0 ? '#d97706' : '#16a34a' }">
          {{ stats.pendingVector ?? 0 }}
        </div>
        <div class="stat-label">待向量同步</div>
      </div>
    </div>

    <!-- 类别分布 + 明细列表 -->
    <div class="card">
      <div class="card-title" style="font-size: 14px; margin-bottom: 10px;">类别分布</div>
      <div v-if="distRows.length" style="display: flex; flex-direction: column; gap: 8px; margin-bottom: 18px;">
        <div v-for="d in distRows" :key="d.key" style="display: flex; align-items: center; gap: 10px;">
          <span style="width: 56px; font-size: 13px; color: #475569; flex-shrink: 0;">{{ d.label }}</span>
          <div class="dist-track" style="flex: 1; max-width: 420px;">
            <div class="dist-fill" :style="{ width: pct(d.count) + '%', background: d.color }"></div>
          </div>
          <span style="width: 48px; text-align: right; font-size: 13px; color: #64748b;">{{ d.count }}</span>
        </div>
      </div>
      <div class="empty" v-else style="margin-bottom: 12px;">暂无数据。</div>

      <div class="toolbar">
        <input class="input" v-model.trim="userId" type="number" placeholder="用户 ID" style="min-width: 110px;"
               @keyup.enter="applyFilter">
        <select class="select" v-model="category" style="min-width: 110px;">
          <option value="">全部类别</option>
          <option v-for="c in CATEGORIES" :key="c.key" :value="c.key">{{ c.label }}</option>
        </select>
        <input class="input grow" v-model.trim="keyword" placeholder="按内容关键词筛选" @keyup.enter="applyFilter">
        <button class="btn btn-outline" @click="applyFilter">查询</button>
      </div>

      <div class="loading-line" v-if="loading">加载中…</div>
      <div class="msg-info error" v-if="errorMsg">{{ errorMsg }}</div>

      <div class="table-wrap" v-if="rows.length">
        <table class="table">
          <thead>
            <tr>
              <th style="width: 70px;">ID</th>
              <th style="width: 170px;">用户</th>
              <th>内容</th>
              <th style="width: 70px;">类别</th>
              <th style="width: 70px;">重要度</th>
              <th style="width: 76px;">向量</th>
              <th style="width: 120px;">来源</th>
              <th style="width: 140px;">创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.id">
              <td style="color: #94a3b8;">{{ r.id }}</td>
              <td>
                <div class="cell-user" :title="userLabel(r)">
                  <span v-if="r.username" class="user-name-tag">{{ userLabel(r) }}</span>
                  <span v-else style="color: #94a3b8;">用户 {{ r.userId }}</span>
                </div>
              </td>
              <td>
                <div class="cell-content" :title="r.content">{{ r.content }}</div>
              </td>
              <td>
                <span class="cat-badge" :style="{ background: catMeta(r.category).color }">
                  {{ catMeta(r.category).label }}
                </span>
              </td>
              <td>
                <span class="imp-dot" :style="{ background: importanceColor(r.importance) }" :title="'重要度 ' + (r.importance ?? '-') + ' / 10'">
                  {{ r.importance ?? '-' }}
                </span>
              </td>
              <td>
                <span class="vec" :class="r.vectorStatus === 1 ? 'ok' : 'pending'">
                  {{ r.vectorStatus === 1 ? '已同步' : '待同步' }}
                </span>
              </td>
              <td>
                <span class="source-tag" :class="r.sourceSession === 'manual' ? 'manual' : 'session'"
                      :title="r.sourceSession === 'manual' ? '手动添加' : r.sourceSession">
                  {{ sourceShort(r.sourceSession) }}
                </span>
              </td>
              <td>
                <div class="cell-time" :title="'更新于 ' + fmtDate(r.updateTime)">{{ fmtDate(r.createTime) }}</div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="empty" v-else-if="!loading && !errorMsg">没有符合条件的记忆记录。</div>

      <div class="pager" v-if="total > 0">
        <button class="btn btn-outline btn-sm" :disabled="page <= 1" @click="goPage(page - 1)">上一页</button>
        <span style="font-size: 13px; color: #64748b;">第 {{ page }} / {{ totalPages }} 页（共 {{ total }} 条）</span>
        <button class="btn btn-outline btn-sm" :disabled="page >= totalPages" @click="goPage(page + 1)">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onActivated } from 'vue'
import { fetchApi } from '../api/request'

const CATEGORIES = [
  { key: 'fact', label: '事实', color: '#2563eb' },
  { key: 'preference', label: '偏好', color: '#7c3aed' },
  { key: 'interest', label: '兴趣', color: '#16a34a' },
  { key: 'goal', label: '目标', color: '#d97706' },
  { key: 'event', label: '经历', color: '#dc2626' }
]
const OTHER_COLOR = '#64748b'

const stats = ref({ total: 0, todayNew: 0, weekNew: 0, pendingVector: 0, usersWithMemory: 0, categoryDist: {} })
const rows = ref([])
const loading = ref(false)
const errorMsg = ref('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
const userId = ref('')
const category = ref('')
const keyword = ref('')

const catMeta = key => CATEGORIES.find(c => c.key === key) || { label: key || '其他', color: OTHER_COLOR }
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

const distRows = computed(() => {
  const dist = stats.value.categoryDist || {}
  const list = CATEGORIES.map(c => ({ key: c.key, label: c.label, count: Number(dist[c.key]) || 0 }))
  Object.keys(dist)
    .filter(k => !CATEGORIES.some(c => c.key === k))
    .forEach(k => list.push({ key: k, label: k === 'other' ? '其他' : k, count: Number(dist[k]) || 0 }))
  return list
})
const distMax = computed(() => Math.max(1, ...distRows.value.map(d => d.count)))
const pct = n => Math.round((n / distMax.value) * 100)

function importanceColor(n) {
  n = Number(n) || 0
  if (n >= 8) return '#dc2626'
  if (n >= 5) return '#d97706'
  return '#94a3b8'
}

function fmtDate(v) {
  if (v == null || v === '') return '-'
  const d = new Date(v)
  if (isNaN(d.getTime())) return String(v)
  const p = x => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function userLabel(r) {
  if (r.username) {
    return r.nickname && r.nickname !== r.username ? `${r.nickname}（${r.username}）` : r.username
  }
  return `用户 ${r.userId}`
}

function sourceShort(s) {
  if (!s || s === 'manual') return '手动'
  return s.length > 9 ? s.slice(0, 9) + '…' : s
}

async function loadStats() {
  try {
    const res = await fetchApi('/api/memory/admin/stats')
    const data = await res.json()
    if (data && data.success && data.data) Object.assign(stats.value, data.data)
  } catch (e) {
    // 统计失败静默：列表错误信息已足够定位
  }
}

async function loadList() {
  loading.value = true
  errorMsg.value = ''
  try {
    const params = new URLSearchParams({ page: String(page.value), size: String(size.value) })
    if (userId.value) params.set('userId', userId.value.trim())
    if (category.value) params.set('category', category.value)
    if (keyword.value) params.set('keyword', keyword.value.trim())
    const res = await fetchApi(`/api/memory/admin/list?${params.toString()}`)
    const data = await res.json()
    if (!data.success) {
      errorMsg.value = data.message || '查询失败'
      rows.value = []
      return
    }
    rows.value = data.data || []
    total.value = data.total || 0
  } catch (e) {
    errorMsg.value = '加载失败，请稍后重试'
    rows.value = []
  } finally {
    loading.value = false
  }
}

function loadAll() {
  loadStats()
  loadList()
}

function applyFilter() {
  page.value = 1
  loadList()
}

function goPage(p) {
  if (p < 1 || p > totalPages.value) return
  page.value = p
  loadList()
}

// 首次挂载与每次切回该 Tab 都刷新（后台自动沉淀/向量补偿后数据即时可见）
onActivated(loadAll)
</script>

<style scoped>
.memory-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.memory-sub { font-size: 13px; color: #64748b; line-height: 1.7; }

.dist-track { height: 8px; background: #f1f5f9; border-radius: 999px; overflow: hidden; }
.dist-fill { height: 100%; border-radius: 999px; transition: width .3s; }

.cat-badge { display: inline-block; padding: 2px 10px; border-radius: 999px; color: #fff; font-size: 12px; white-space: nowrap; }
.imp-dot {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 24px; height: 20px; padding: 0 5px; border-radius: 999px;
  color: #fff; font-size: 12px; font-weight: 700;
}
.vec { font-size: 12px; white-space: nowrap; }
.vec.ok { color: #16a34a; }
.vec.pending { color: #d97706; }

.cell-content { max-width: 440px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cell-user { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.user-name-tag { color: #334155; }
.cell-time { font-size: 13px; color: #64748b; white-space: nowrap; }
.source-tag {
  display: inline-block; max-width: 110px; padding: 2px 8px; border-radius: 4px;
  font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.source-tag.manual { background: #f1f5f9; color: #64748b; }
.source-tag.session { background: #eff6ff; color: #1d4ed8; }
</style>
