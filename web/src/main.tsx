import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import './styles.css'
// 代码高亮主题（与页面浅色主题匹配）
import 'highlight.js/styles/github.css'

const container = document.getElementById('root')
if (!container) {
  throw new Error('找不到 #root 容器，检查 index.html')
}

createRoot(container).render(
  <StrictMode>
    {/* ConfigProvider 负责中文文案；AntApp 提供 message/modal 的 context 版本，
        避免用 message.success 这类静态方法（在 React 19 下会告警） */}
    <ConfigProvider locale={zhCN}>
      <AntApp>
        <App />
      </AntApp>
    </ConfigProvider>
  </StrictMode>,
)
