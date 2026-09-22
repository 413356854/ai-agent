import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App as AntApp, ConfigProvider, theme } from 'antd'
import type { ThemeConfig } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import { useThemeMode } from './hooks/useThemeMode'
import './styles.css'
// 代码高亮的样式**不在这里 import**：两套主题都由 index.html 里那两个 <link> 提供，
// 由主题开关切它们的 disabled（见 useThemeMode 的 applyTheme）。
// 静态 `import 'highlight.js/styles/github.css'` 做不到这件事：它是无条件生效的，
// 而 github.css 给 `.hljs` 写死了浅色背景与文字色 —— 深色下就是一块白底代码，
// 且**没有任何 CSS 能盖过它**（同优先级下后来的赢，而它的加载顺序由打包器决定）。

/** 深色下的少数几处微调：其余底色由 `theme.darkAlgorithm` 自己算，这里只钉一件它算不出来的事 */
const DARK_TOKENS: ThemeConfig['token'] = {
  // 页面底色。算法给的是纯黑（colorBgBase 本身），而容器是 #141414 ——
  // 差 8 级在这一页上分不出"哪一块是卡片"（实测：成本看板头部那张没有边框的卡与页面同色）。
  //
  // ⚠️ 这个字面量必须与 `styles.css` 里 `[data-theme='dark']` 的 `--bg-page` **一模一样**：
  // antd 的控件与自绘容器分别读这两处，差一点就会在接缝处露出一条色带。
  colorBgLayout: '#0a0a0a',
}

function Root() {
  // 主题状态在 ConfigProvider 之上（antd 的深色算法要它才生效），
  // 而开关本身在页头里 —— 所以状态挂在这里、由 props 往下传一层
  const [themeMode, toggleTheme] = useThemeMode()

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: themeMode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
        token: themeMode === 'dark' ? DARK_TOKENS : undefined,
      }}
    >
      {/* AntApp 提供 message/modal 的 context 版本，避免用 message.success 这类静态方法
          （在 React 19 下会告警）。它也在 ConfigProvider 之内，所以浮层跟着主题走 */}
      <AntApp>
        <App themeMode={themeMode} onToggleTheme={toggleTheme} />
      </AntApp>
    </ConfigProvider>
  )
}

const container = document.getElementById('root')
if (!container) {
  throw new Error('找不到 #root 容器，检查 index.html')
}

createRoot(container).render(
  <StrictMode>
    <Root />
  </StrictMode>,
)
