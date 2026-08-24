// ============================================================
// 网关 API 基础地址
//   ''                        默认：同源反代（Nginx / Vite 代理 /api → 网关 7070）
//   'http://localhost:7070'   直连网关（网关 CORS 已放行所有来源，可用于无代理的静态服务器）
// ============================================================
export const API_BASE = ''

const TOKEN_KEY = 'token'
const REFRESH_KEY = 'refreshToken'
const USERNAME_KEY = 'username'

export function getToken() { return localStorage.getItem(TOKEN_KEY) }
export function getUsername() { return localStorage.getItem(USERNAME_KEY) }
export function setAuth(token, refreshToken, username) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken)
  if (username != null) localStorage.setItem(USERNAME_KEY, username)
}
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
  localStorage.removeItem(USERNAME_KEY)
}
export function clearAuthAndRedirect() {
  clearAuth()
  window.location.href = '/#/login'
}

// 共享 Promise：并发 401 只触发一次刷新
let refreshPromise = null

export async function refreshAccessToken(refreshToken) {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const res = await fetch(API_BASE + '/api/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken })
        })
        const data = await res.json()
        // 后端 /api/refresh 返回扁平结构 {success, token, refreshToken, username}
        if (!data.success || !data.token) {
          throw new Error(data.message || '刷新失败')
        }
        setAuth(data.token, data.refreshToken, data.username)
        return data.token
      } finally {
        refreshPromise = null
      }
    })()
  }
  return refreshPromise
}

/**
 * 统一请求封装：自动携带 Authorization；401 时自动刷新 token 并重试一次
 */
export async function fetchApi(url, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = getToken()
  if (token) headers.set('Authorization', 'Bearer ' + token)

  const opts = { ...options, headers }
  let res = await fetch(API_BASE + url, opts)

  if (res.status === 401) {
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    if (refreshToken) {
      try {
        const newToken = await refreshAccessToken(refreshToken)
        headers.set('Authorization', 'Bearer ' + newToken)
        res = await fetch(API_BASE + url, { ...opts, headers })
      } catch (e) {
        clearAuthAndRedirect()
        throw new Error('登录已过期，请重新登录')
      }
    } else {
      clearAuthAndRedirect()
      throw new Error('登录已过期，请重新登录')
    }
  }
  return res
}

// 下载文件（blob），自动携带 token
export async function downloadFile(docId, docName) {
  try {
    const res = await fetchApi('/api/knowledge-document/' + docId + '/download')
    if (!res.ok) throw new Error('下载失败（' + res.status + '）')
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = docName || 'download'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(url), 2000)
    return true
  } catch (e) {
    console.error('下载失败', e)
    return false
  }
}

// 退出登录：调网关注销（写入 Redis 黑名单）并清理本地凭证
export async function logout() {
  try {
    const refreshToken = localStorage.getItem(REFRESH_KEY)
    if (refreshToken) {
      const headers = new Headers()
      const token = getToken()
      if (token) headers.set('Authorization', 'Bearer ' + token)
      headers.set('Content-Type', 'application/json')
      await fetch(API_BASE + '/api/logout', {
        method: 'POST',
        headers,
        body: JSON.stringify({ refreshToken })
      })
    }
  } catch (e) { /* 忽略注销失败 */ }
  clearAuth()
}
