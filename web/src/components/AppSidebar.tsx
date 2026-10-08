import { useState } from 'react'
import { NAV_SECTIONS } from '../nav'
import type { Mode, NavGroup } from '../nav'

/**
 * 左侧目录（类后台的三段式布局：目录 / 页头 / 内容）。
 *
 * <h2>为什么用 `button` 手写目录，而不是 antd 的 Menu</h2>
 * 需要的是"两段可折叠的分组 + 每项一个按钮 + 选中项一条左侧色标"。
 * Menu（或 SubMenu 的折叠）能画出层级，但每一段的标题要能收起那一段、
 * 收起后标题上还要留一枚"当前视图在这一段里"的标记 —— 那套渲染仍得自己写；
 * 而它的收起态（`inlineCollapsed` + `Sider`）依赖图标，本项目没有图标依赖
 * （`@ant-design/icons` 不在 package.json 里），收起后每项都是一条空白窄条。
 *
 * <h2>整条收起由外面驱动（`hidden` prop），按下的开关在页头</h2>
 * 收起态的宽度是 0，也就是说收起时这一整块不在眼前 ——
 * 把开关留在这里的话，展开就再也没有入口了。所以状态在 `useSidebarCollapse`、
 * 按钮在页头的 `NavToggle`，这个组件只负责"收起时长什么样（不见）"。
 *
 * <h2>为什么分组名也可点（折叠一段）</h2>
 * 整条目录的收起是"横向省宽度"，分组折叠是"纵向省高度"：对话台那段有八项，
 * 在应用管理里干活时它们只是占位，往下翻还得划过去。两件事互不影响。
 * ⚠️ 折叠**只改渲染**，不改"当前在哪"的判断：收起的那一段里若正落着当前视图，
 * 标题会带上色标与着色（见 styles.css 的 `is-active`），
 * 因为"现在在哪"这一页最要紧的信息，不该因为收起而丢掉。
 * 整条收起时（`hidden`）分组一律按展开渲染 —— 那时目录整块是藏起来的，
 * 下次展开时记为"收起"的那一段会一项都不显示，看起来像那一段坏了。
 */

/** 各分组的折叠态，同样存 localStorage（`{分组键: 是否收起}`；没记过的分组按展开算） */
const GROUP_KEY = 'ai-agent.nav.group-collapsed'

function readClosedGroups(): Record<string, boolean> {
  try {
    return JSON.parse(window.localStorage.getItem(GROUP_KEY) ?? '{}')
  } catch {
    // 存坏了（手改过 / 是旧格式）就当没折过：宁可是"都展开"，
    // 也不能让某一段目录整段消失 —— 那看起来像功能坏了
    return {}
  }
}

export function AppSidebar(
  { mode, onSelect, hidden }: { mode: Mode; onSelect: (mode: Mode) => void; hidden: boolean },
) {
  const [closedGroups, setClosedGroups] = useState<Record<string, boolean>>(readClosedGroups)

  const toggleGroup = (key: string) => {
    const next = { ...closedGroups, [key]: !closedGroups[key] }
    setClosedGroups(next)
    window.localStorage.setItem(GROUP_KEY, JSON.stringify(next))
  }

  return (
    <nav
      id="app-nav"
      className={`app-sidebar${hidden ? ' is-collapsed' : ''}`}
      aria-label="功能目录"
      // 自检靠这个属性读收起态（而不是靠样式），所以它必须跟着状态走
      data-collapsed={hidden ? '1' : '0'}
      // 收起之后那一列按钮既画不出来也不该被 Tab 到 —— `inert` 一次管两件事
      inert={hidden}
    >
      <div className="app-brand" title="Agent 控制台：对话台 + 应用管理">
        <span className="app-brand-mark">AI</span>
        <span className="app-brand-name">Agent 控制台</span>
      </div>

      <div className="app-nav">
        {NAV_SECTIONS.map((section: NavGroup) => {
          const closed = !hidden && closedGroups[section.key] === true
          // 收起来的那一段里正落着当前视图 —— 这时位置只剩标题上那枚标记，必须标出来
          const activeInside = closed && section.items.some(item => item.mode === mode)
          return (
            <div className="app-nav-group" key={section.key}>
              <button
                type="button"
                className={`app-nav-group-title${activeInside ? ' is-active' : ''}`}
                title={`${section.title}（点一下${closed ? '展开' : '收起'}这一段）`}
                aria-expanded={!closed}
                aria-controls={`nav-group-${section.key}`}
                data-active-inside={activeInside ? '1' : '0'}
                onClick={() => toggleGroup(section.key)}
              >
                <span className="app-nav-caret" aria-hidden="true" />
                <span className="app-nav-group-name">{section.label}</span>
              </button>
              <div className="app-nav-group-items" id={`nav-group-${section.key}`}>
                {!closed && section.items.map(item => {
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
            </div>
          )
        })}
      </div>
    </nav>
  )
}
