import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发服务器：端口 9000，/api/** 代理到网关 7070（与生产 nginx.conf 同源反代一致）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 9000,
    proxy: {
      '/api': {
        target: 'http://localhost:7070',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500
  }
})
