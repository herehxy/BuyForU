// Vite 开发配置：React 插件负责 JSX，/api 代理到本地 Agent 服务以避免开发期跨域。
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
})
