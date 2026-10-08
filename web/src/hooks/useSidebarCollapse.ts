import { useCallback, useState } from 'react'

/** 收起态存 localStorage：那是"这台机器上我怎么看这个页面"的偏好，刷新一次就忘掉会很烦 */
const COLLAPSE_KEY = 'ai-agent.nav.collapsed'

/** 窄窗口的界线。**它只用来定首帧的初值**（见下面那个 hook 的注释），没有对应的 CSS */
const NARROW_QUERY = '(max-width: 900px)'

/**
 * 左边栏的收起态（开关在页头左上角，见 NavToggle）。
 *
 * <h2>为什么收起是"整条不见"，而不是留一条 64px 的窄轨</h2>
 * 目录占 232px，而这一页的主体是数据表（成本看板、五层 trace、版本历史）。
 * 收起的目的就是把宽度还给内容，所以收起后宽度是 0：窄轨只省下 168px，
 * 而它留下的那一列（色标 + 分隔线）本身就要占位置。
 * 代价是目录项连同它左侧那条色标一起不在眼前了 —— 补回来的是页头上那枚
 * "当前视图"标签（见 App.tsx），"现在在哪"始终有一处能读。
 *
 * <h2>为什么窄窗口只定首帧的初值，不做成"窄了就强制收起"</h2>
 * 窄窗口下默认收起来（否则内容区被 232px 挤到没法用）。
 * 但那个默认只在**没存过偏好**时生效一次，之后完全由开关说了算：
 * 写成"窄了就强制收起"的话，开关在窄窗口下点了没反应（状态恒为收起）——
 * 而"点了没反应的按钮"会被读成界面坏了。
 */
export function useSidebarCollapse(): { hidden: boolean; toggle: () => void } {
  const [hidden, setHidden] = useState(() => {
    const stored = window.localStorage.getItem(COLLAPSE_KEY)
    if (stored === '1') return true
    if (stored === '0') return false
    return window.matchMedia(NARROW_QUERY).matches
  })

  const toggle = useCallback(() => {
    setHidden(prev => {
      window.localStorage.setItem(COLLAPSE_KEY, prev ? '0' : '1')
      return !prev
    })
  }, [])

  return { hidden, toggle }
}
