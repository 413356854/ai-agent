import { useCallback, useState } from 'react'

/** 主题只有两个：浅色与深色。缺省跟随系统（见 index.html 里那段首帧脚本） */
export type ThemeMode = 'light' | 'dark'

/** 存主题偏好的键。**index.html 里有一份同样的字符串**（首帧脚本拿不到这个模块，见那个文件的注释） */
export const THEME_KEY = 'ai-agent.theme'

/**
 * 把主题落到 DOM 上：`<html>` 的属性 + 代码高亮该用哪一套表。
 *
 * <h2>为什么还要管代码高亮那两张表</h2>
 * 它们不认 `data-theme`（是第三方 CSS，给 `.hljs` 写死了背景与文字色），
 * 只能靠 `disabled` 选一张。只做属性那一件事的症状是：整页都换过来了，**只有代码块还是白底**。
 *
 * <h2>为什么属性挂在 `<html>` 上，而不是 `#root` 里某个 div</h2>
 * 这样 antd 渲染到 body 下的浮层（Modal / message / Dropdown）也跟着一起变，
 * 不需要各自一份上下文。浏览器自己画的那几块（滚动条、原生下拉、输入法候选框）走的是
 * 另一条路：`styles.css` 里按同一个属性给了 `color-scheme`，antd 的算法管不到它们。
 */
export function applyTheme(mode: ThemeMode) {
  const dark = mode === 'dark'
  document.documentElement.dataset.theme = mode
  // 这两张表在 index.html 里（`public/hljs-github*.css`）—— 为什么不在这个模块里 import，见那个文件
  const light = document.getElementById('hljs-light') as HTMLLinkElement | null
  const darkSheet = document.getElementById('hljs-dark') as HTMLLinkElement | null
  if (light) light.disabled = dark
  if (darkSheet) darkSheet.disabled = !dark
}

/**
 * 主题状态：读 / 切。
 *
 * <h2>为什么读的是 `<html>` 上的属性，而不是自己再算一遍偏好</h2>
 * 首帧要按主题上色，就不能等 React 起来 —— 所以解析偏好的那三行写在 `index.html` 的
 * 内联脚本里，它才是主题的**第一现场**。这个 hook 只读那个脚本留下的结果。
 * 在这里重新解析一遍 localStorage 与 `matchMedia` 会更"独立"，但那是**第二份判据**：
 * 两份在"没存过偏好、系统是深色"这类分支上会漂移，症状是首帧深色、React 一起来又变回浅色。
 *
 * <h2>为什么存 localStorage</h2>
 * 与目录收起态同一条理由（见 AppSidebar）：它是"这台机器上我怎么看这个页面"的偏好，
 * 刷新一次就忘掉会很烦。存过之后**系统偏好不再参与** —— 一个明确点过开关的人，
 * 不该被操作系统的夜间切换改掉他自己选的。
 */
export function useThemeMode(): [ThemeMode, () => void] {
  const [mode, setMode] = useState<ThemeMode>(() =>
    document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light',
  )

  const toggle = useCallback(() => {
    setMode(prev => {
      const next: ThemeMode = prev === 'dark' ? 'light' : 'dark'
      applyTheme(next)
      window.localStorage.setItem(THEME_KEY, next)
      return next
    })
  }, [])

  return [mode, toggle]
}
