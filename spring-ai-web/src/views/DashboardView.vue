<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar">
      <div class="sidebar-logo">
        <div class="logo-icon">R</div>
        <div>
          <div class="logo-text">智能知识库平台</div>
          <div class="logo-sub">Spring AI · RAG</div>
        </div>
      </div>

      <nav class="sidebar-nav">
        <button
          v-for="item in navItems"
          :key="item.key"
          class="nav-item"
          :class="{ active: activeTab === item.key }"
          @click="switchTab(item.key)"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          {{ item.title }}
        </button>
      </nav>

      <div class="sidebar-foot">© 2026 spring-ai-web</div>
    </aside>

    <!-- 主区域 -->
    <main class="main">
      <header class="topbar">
        <div class="topbar-title">{{ currentTitle }}</div>
        <div class="topbar-right">
          <div class="user-chip">
            <div class="user-avatar">{{ avatarText }}</div>
            <div>
              <div class="user-name">{{ username }}</div>
              <div style="font-size: 11px; color: #94a3b8;">{{ isAdmin ? '系统管理员' : '普通用户' }}</div>
            </div>
          </div>
          <button class="btn btn-outline btn-sm" @click="logout">退出登录</button>
        </div>
      </header>

      <div class="content" v-if="!pageLoading">
        <KeepAlive>
          <component :is="currentComponent" />
        </KeepAlive>
      </div>
      <div class="content" v-else>
        <div class="loading-line">加载中…</div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, shallowRef, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchApi, logout as apiLogout } from '../api/request'
import { showToast } from '../utils/toast'

import HomeTab from '../components/HomeTab.vue'
import ChatTab from '../components/ChatTab.vue'
import UploadTab from '../components/UploadTab.vue'
import DocsTab from '../components/DocsTab.vue'
import TasksTab from '../components/TasksTab.vue'
import KbTab from '../components/KbTab.vue'
import UsersTab from '../components/UsersTab.vue'
import RolesTab from '../components/RolesTab.vue'

const router = useRouter()

const username = ref('')
const avatarText = ref('U')
const isAdmin = ref(false)
const pageLoading = ref(true)
const activeTab = ref('home')

const navItems = computed(() => {
  const items = [
    { key: 'home', icon: '🏠', title: '首页' },
    { key: 'chat', icon: '💬', title: '知识问答' },
    { key: 'upload', icon: '📤', title: '上传文档' },
    { key: 'docs', icon: '📄', title: '文档列表' },
    { key: 'tasks', icon: '⏳', title: '任务列表' }
  ]
  if (isAdmin.value) {
    items.push(
      { key: 'kb', icon: '🗂️', title: '知识库管理' },
      { key: 'users', icon: '👥', title: '用户管理' },
      { key: 'roles', icon: '🛡️', title: '角色管理' }
    )
  }
  return items
})

const tabComponents = {
  home: HomeTab,
  chat: ChatTab,
  upload: UploadTab,
  docs: DocsTab,
  tasks: TasksTab,
  kb: KbTab,
  users: UsersTab,
  roles: RolesTab
}

const currentComponent = shallowRef(tabComponents[activeTab.value])

const tabTitles = {
  home: '首页', chat: '知识问答', upload: '上传文档', docs: '文档列表',
  tasks: '任务列表', kb: '知识库管理', users: '用户管理', roles: '角色管理'
}
const currentTitle = computed(() => tabTitles[activeTab.value])

function switchTab(key) {
  activeTab.value = key
  currentComponent.value = tabComponents[key]
}

onMounted(async () => {
  try {
    const res = await fetchApi('/api/user')
    if (!res.ok) { router.replace('/login'); return }
    const data = await res.json()
    username.value = data.username || ''
    avatarText.value = (data.username || 'U').charAt(0).toUpperCase()
    isAdmin.value = !!data.isAdmin
  } catch (e) {
    router.replace('/login')
    return
  } finally {
    pageLoading.value = false
  }
})

async function logout() {
  await apiLogout()
  router.replace('/login')
  showToast('已退出登录', 'info')
}
</script>
