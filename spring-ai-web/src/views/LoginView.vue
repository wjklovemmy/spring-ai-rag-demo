<template>
  <div class="login-page">
    <canvas id="particles-canvas"></canvas>
    <div class="login-card">
      <div class="login-title">
        <h1>智能知识库平台</h1>
        <p>基于 Spring AI · RAG 的智能问答系统</p>
      </div>

      <div class="login-tabs">
        <button class="login-tab" :class="{ active: tab === 'login' }" @click="switchTab('login')">登 录</button>
        <button class="login-tab" :class="{ active: tab === 'register' }" @click="switchTab('register')">注 册</button>
      </div>

      <div class="msg-info" v-if="alertMsg" :class="alertType">{{ alertMsg }}</div>

      <!-- 登录 -->
      <form v-if="tab === 'login'" @submit.prevent="handleLogin">
        <div class="form-group">
          <input class="input" v-model.trim="loginForm.username" placeholder="用户名" autocomplete="username" required>
        </div>
        <div class="form-group">
          <input class="input" v-model="loginForm.password" type="password" placeholder="密码" autocomplete="current-password" required>
        </div>
        <button class="btn btn-primary btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '登录中…' : '登 录' }}
        </button>
      </form>

      <!-- 注册 -->
      <form v-else @submit.prevent="handleRegister">
        <div class="form-group">
          <input class="input" v-model.trim="regForm.username" placeholder="用户名（登录用）" required>
        </div>
        <div class="form-group">
          <input class="input" v-model.trim="regForm.nickname" placeholder="昵称（选填）">
        </div>
        <div class="form-group">
          <input class="input" v-model.trim="regForm.email" type="email" placeholder="邮箱">
        </div>
        <div class="form-group">
          <input class="input" v-model="regForm.password" type="password" placeholder="密码" minlength="6" required>
        </div>
        <div class="form-group">
          <input class="input" v-model="regForm.password2" type="password" placeholder="确认密码" required>
        </div>
        <button class="btn btn-success btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '注册中…' : '注 册' }}
        </button>
      </form>

      <div class="login-sub">© 2026 spring-ai-web · Spring Boot + Spring AI</div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { API_BASE, setAuth, clearAuth } from '../api/request'

const router = useRouter()

const tab = ref('login')
const submitting = ref(false)
const alertMsg = ref('')
const alertType = ref('info')
const loginForm = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', nickname: '', email: '', password: '', password2: '' })

function showAlert(msg, type = 'info') {
  alertMsg.value = msg
  alertType.value = type
}

function switchTab(t) {
  tab.value = t
  alertMsg.value = ''
}

// 已登录直接进入主页面
function checkExistingToken() {
  if (localStorage.getItem('token')) {
    router.replace('/')
  }
}

// 尝试用 refreshToken 恢复会话
async function tryRefresh(refreshToken) {
  try {
    const res = await fetch(API_BASE + '/api/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken })
    })
    const data = await res.json()
    // 后端 /api/refresh 返回扁平结构 {success, token, refreshToken, username}
    if (data.success && data.token) {
      setAuth(data.token, data.refreshToken, data.username)
      router.replace('/')
    } else {
      clearAuth()
    }
  } catch (e) {
    clearAuth()
  }
}

async function handleLogin() {
  if (!loginForm.username || !loginForm.password) { showAlert('请输入用户名和密码', 'error'); return }
  submitting.value = true
  try {
    const res = await fetch(API_BASE + '/api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: loginForm.username, password: loginForm.password })
    })
    const data = await res.json()
    // 后端 /api/login 返回扁平结构 {success, token, refreshToken, username}
    if (!data.success || !data.token) { showAlert(data.message || '登录失败', 'error'); return }
    setAuth(data.token, data.refreshToken, data.username)
    router.replace('/')
  } catch (e) {
    showAlert('网络异常，请稍后重试', 'error')
  } finally {
    submitting.value = false
  }
}

async function handleRegister() {
  const { username, nickname, email, password, password2 } = regForm
  if (!username || !password) { showAlert('用户名和密码不能为空', 'error'); return }
  if (password.length < 6) { showAlert('密码至少 6 位', 'error'); return }
  if (password !== password2) { showAlert('两次输入的密码不一致', 'error'); return }
  submitting.value = true
  try {
    const res = await fetch(API_BASE + '/api/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, nickname, email, password })
    })
    const data = await res.json()
    if (!data.success) { showAlert(data.message || '注册失败', 'error'); return }
    showAlert('注册成功，请登录', 'info')
    switchTab('login')
    loginForm.username = username
    loginForm.password = ''
  } catch (e) {
    showAlert('网络异常，请稍后重试', 'error')
  } finally {
    submitting.value = false
  }
}

// ---------- 粒子背景 ----------
let particles = []
let rafId = 0
let resizeHandler = null

function initParticles() {
  const canvas = document.getElementById('particles-canvas')
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  let w, h

  function resize() {
    w = canvas.width = window.innerWidth
    h = canvas.height = window.innerHeight
  }
  resizeHandler = resize
  window.addEventListener('resize', resize)

  const N = Math.min(70, Math.floor((w * h) / 22000))
  particles = []
  for (let i = 0; i < N; i++) {
    particles.push({
      x: Math.random() * w,
      y: Math.random() * h,
      vx: (Math.random() - 0.5) * 0.6,
      vy: (Math.random() - 0.5) * 0.6,
      r: Math.random() * 2 + 1
    })
  }

  function draw() {
    ctx.clearRect(0, 0, w, h)
    for (const p of particles) {
      p.x += p.vx
      p.y += p.vy
      if (p.x < 0 || p.x > w) p.vx *= -1
      if (p.y < 0 || p.y > h) p.vy *= -1
      ctx.beginPath()
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(125, 211, 252, 0.55)'
      ctx.fill()
    }
    for (let i = 0; i < particles.length; i++) {
      for (let j = i + 1; j < particles.length; j++) {
        const a = particles[i], b = particles[j]
        const dx = a.x - b.x, dy = a.y - b.y
        const d = Math.sqrt(dx * dx + dy * dy)
        if (d < 150) {
          ctx.strokeStyle = `rgba(125, 211, 252, ${0.25 * (1 - d / 150)})`
          ctx.lineWidth = 1
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.stroke()
        }
      }
    }
    rafId = requestAnimationFrame(draw)
  }
  draw()
}

onMounted(() => {
  initParticles()
  const refreshToken = localStorage.getItem('refreshToken')
  if (refreshToken) { tryRefresh(refreshToken) } else { checkExistingToken() }
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  if (resizeHandler) window.removeEventListener('resize', resizeHandler)
})
</script>
