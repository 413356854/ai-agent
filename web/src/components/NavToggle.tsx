import { Button, Tooltip } from 'antd'

/**
 * 目录开关（页头左上角，页头里唯一在标题左边的东西）。
 *
 * <h2>为什么它不在目录里，而在页头</h2>
 * 收起之后目录整条宽度为 0，摆在目录里的开关会跟着一起消失 ——
 * 那时它正是唯一的展开入口。所以它必须在目录之外、而且始终可见的那一层上。
 *
 * <h2>为什么按钮上没有文字</h2>
 * 页头这一行要装标题 / 分区标签 / 会话 id / 新会话 / 模拟登录 / 主题开关，
 * 而它本来就吃紧（图标-only 省下的那 40px 是真能决定这一行折不折成两行的）。
 * 文字去掉之后名字由 `aria-label` / `title` 给（读屏与悬停都读得到），
 * 画的是三条横线：项目里没有图标依赖（`@ant-design/icons` 不在 package.json 里，
 * 见 AppSidebar 的文件头），所以它由 CSS 画。
 *
 * <h2>为什么带 `aria-expanded`</h2>
 * 图标自己说得出"这是目录开关"，说不出"目录现在是开着还是关着"。
 * 自检也读它（不读样式），所以它必须跟着状态走；`aria-controls` 指向目录本身（`#app-nav`）。
 */
export function NavToggle({ hidden, onToggle }: { hidden: boolean; onToggle: () => void }) {
  const label = hidden ? '展开目录' : '收起目录'

  return (
    <Tooltip title={label}>
      <Button
        className="app-nav-toggle"
        onClick={onToggle}
        aria-label={label}
        aria-expanded={!hidden}
        aria-controls="app-nav"
      >
        <span className="app-nav-toggle-bars" aria-hidden="true">
          <i /><i /><i />
        </span>
      </Button>
    </Tooltip>
  )
}
