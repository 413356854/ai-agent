import { useEffect, useState } from 'react'
import { NAV_SECTIONS } from '../nav'
import type { Mode, NavGroup } from '../nav'

/**
 * 左侧目录（类后台的三段式布局：目录 / 页头 / 内容）。
 *
 * <h2>为什么用 `button` 手写目录，而不是 antd 的 Menu</h2>
 * 需要的就是"两段标题 + 若干按钮 + 一个收起开关"。Menu 能画出来，但收起态要靠
 * `inlineCollapsed` + `Sider` 那套上下文，收起后只剩一列图标 —— 而本项目没有图标依赖
 * （`@ant-design/icons` 不在 package.json 里），收起后每一项都会变成一条空白的窄条。
 * 所以收起态由 `.is-collapsed` 这一个类控制：藏文字、留左边一条色标，每一项仍可点。
 *
 * <h2>为什么收起态是"必须有的"</h2>
 * 目录占 232px，而这一页的主体是数据表（成本看板、五层 trace、版本历史）。
 * 1100px 宽的窗口下，那 232px 是差一行、差一列能不能放下的差别。
 */

/** 收起态存 localStorage：那是"这台机器上我怎么看这个页面"的偏好，刷新一次就忘掉会很烦 */
const COLLAPSE_KEY = 'ai-agent.nav.collapsed'

/** 窄屏（≤ 900px）不记忆收起状态：那是"窗口放不下"的临时妥协，窗口变宽后应当自己展开 */
const NARROW_QUERY = '(max-width: 900px)'

export function AppSidebar(
  { mode, onSelect }: { mode: Mode; onSelect: (mode: Mode) => void },
) {
  const [collapsed, setCollapsed] = useState(
    () => window.localStorage.getItem(COLLAPSE_KEY) === '1',
  )
  const [narrow, setNarrow] = useState(() => window.matchMedia(NARROW_QUERY).matches)

  // 窄屏时自动收起。⚠️ 这里只**跟随窗口**，不写回 localStorage ——
  // 否则一个窄窗口会把"收起"永久记成这台机器上的偏好，宽屏下也一直是收起的
  useEffect(() => {
    const query = window.matchMedia(NARROW_QUERY)
    const onChange = (e: MediaQueryListEvent) => setNarrow(e.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  const railed = collapsed || narrow

  const toggle = () => {
    const next = !railed
    setCollapsed(next)
    window.localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0')
  }

  return (
    <nav
      className={`app-sidebar${railed ? ' is-collapsed' : ''}`}
      aria-label="功能目录"
      // 自检靠这个属性读收起态（而不是靠样式），所以它必须跟着状态走
      data-collapsed={railed ? '1' : '0'}
    >
      <div className="app-brand" title="Agent 控制台：对话台 + 应用管理">
        <span className="app-brand-mark">AI</span>
        <span className="app-brand-name">Agent 控制台</span>
      </div>

      <div className="app-nav">
        {NAV_SECTIONS.map((section: NavGroup) => (
          <div className="app-nav-group" key={section.key}>
            {/* 收起态这一行只剩一条分隔线（样式里给它固定高度），
                否则两段目录会挤在一起，看不出是两段 */}
            <div className="app-nav-group-title" title={section.title}>
              {section.label}
            </div>
            {section.items.map(item => {
              const active = item.mode === mode
              return (
                <button
                  type="button"
                  key={item.mode}
                  className={`app-nav-item${active ? ' is-active' : ''}`}
                  title={item.label}
                  aria-current={active ? 'page' : undefined}
                  onClick={() => onSelect(item.mode)}
                >
                  <span className="app-nav-item-text">{item.label}</span>
                </button>
              )
            })}
          </div>
        ))}
      </div>

      <div className="app-sidebar-foot">
        {/*
          窄屏时这个开关**禁用**。不禁用的话它点了没反应（`railed` 恒为 true），
          而"点了没反应的按钮"会被读成界面坏了。
        */}
        <button
          type="button"
          className="app-collapse"
          onClick={toggle}
          disabled={narrow}
          title={narrow ? '窗口宽度不足，目录已自动收起' : railed ? '展开目录' : '收起目录'}
        >
          {/* 三条横线用 CSS 画：项目里没有图标依赖（见文件头） */}
          <span className="app-collapse-bars" aria-hidden="true">
            <i /><i /><i />
          </span>
          <span className="app-collapse-text">{railed ? '展开目录' : '收起目录'}</span>
        </button>
      </div>
    </nav>
  )
}
