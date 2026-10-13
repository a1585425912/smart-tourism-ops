import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// 开发时通过代理保持与后端同源, 会话 Cookie 与 CSRF Cookie 才能正常工作
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.SNOW_API ?? 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    chunkSizeWarningLimit: 2000,
  },
  test: {
    environment: 'jsdom',
  },
})
