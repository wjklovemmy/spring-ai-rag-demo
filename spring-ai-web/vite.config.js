import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发服务器：端口 5173，/api/** 代理到网关 7070（与生产 nginx.conf 同源反代一致）
// 注：9000 已被 docker-compose 的 milvus minio 占用，dev 端口刻意避开
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
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
