import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 开发期 /api 一律代理到 Spring Boot（8080）。
 *
 * 这样前端代码里只写相对路径 `/api/chat/stream`：
 *   - 开发期：Vite dev server 转发，浏览器看到的是同源 → 后端不需要配 CORS；
 *   - 生产期：`pnpm build` 产物丢进 `ai-agent/src/main/resources/static/`，本来就同源。
 * 一旦把 http://localhost:8080 硬编码进前端代码，上面两条就全废了。
 *
 * 目标地址可在不改代码的情况下切换：
 *   - `pnpm dev:mock` → 代理到本地 SSE 假后端（mock/sse-server.mjs，8081），不烧 token；
 *   - 传环境变量 `VITE_API_TARGET` → 指向别的后端（例如同事机器上的服务）。
 */
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    // 端口被占用时直接报错，而不是悄悄换成 5174（否则联调时你会怀疑为什么代理没生效）
    strictPort: true,
    proxy: {
      '/api': {
        target: mode === 'mock' ? 'http://127.0.0.1:8081' : (process.env.VITE_API_TARGET ?? 'http://localhost:8080'),
        changeOrigin: true,
      },
    },
  },
}))
