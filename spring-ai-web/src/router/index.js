import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'

// 使用 hash 路由：任意静态服务器（Nginx / python -m http.server）均可直接部署，无需 SPA fallback 配置
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    { path: '/', name: 'dashboard', component: DashboardView },
    { path: '/:pathMatch(.*)*', redirect: '/' }
  ]
})

export default router
