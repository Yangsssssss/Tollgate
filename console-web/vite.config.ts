import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发代理：前端 5173，后端 18080。/v1、/admin、/api 三类端点全部转发
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:18080',
      '/admin': 'http://localhost:18080',
      '/v1': 'http://localhost:18080'
    }
  }
})
