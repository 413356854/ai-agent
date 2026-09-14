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
    // 关掉文件监听对「原子替换临时目录」的遍历。
    // 为什么必须写这个：编辑工具/编辑器落盘时是"写临时文件 → 原子重命名"，
    // 而在 Windows 上那个瞬间创建又删除的 `.xxx.tmpdir` 会让 chokidar 抛
    //   Error: EBUSY: resource busy or locked, watch '...tmpdir\xxx.tmp'
    // 而 Vite **没有捕获它** —— 直接进程退出，dev server 整个挂掉。
    // 表现是"改一个文件，前端服务就没了"，很容易被误判成代码写崩了。
    watch: {
      ignored: ['**/.*.tmpdir/**', '**/*.tmp'],
    },
    proxy: {
      '/api': {
        target: mode === 'mock' ? 'http://127.0.0.1:8081' : (process.env.VITE_API_TARGET ?? 'http://localhost:8080'),
        changeOrigin: true,
        // 下面两个 0 = 关闭代理层的超时，别删。
        // 开发和联调时 Vite 的 dev server 就是这条链路上的"反向代理"，
        // 它的默认超时远短于一次长回答（SSE 是长连接，几十秒不结束很正常），
        // 到点会直接掐掉流，现象是"回答说到一半就断了"——而且只在开发环境复现，
        // 上线后又好了，极难排查。（这和 Nginx 的 proxy_buffering / proxy_read_timeout
        // 是同一类坑，属于"本地正常、线上不正常"的镜像版本：
        // 这里是"开发不正常、直连后端却正常"。）
        proxyTimeout: 0,
        timeout: 0,
      },
    },
  },
}))
