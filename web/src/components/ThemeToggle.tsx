import { Button, Tooltip } from 'antd'
import type { ThemeMode } from '../hooks/useThemeMode'

/**
 * 主题开关（页头最右）。
 *
 * <h2>图标为什么是内联 SVG</h2>
 * 项目里没有图标依赖（`@ant-design/icons` 不在 package.json 里，见 AppSidebar 的文件头），
 * 所以这两个图形是用 `<path>` 画的 —— 不用 CSS 拼（试过：一条渐变画 4 条光线的写法在
 * 16px 里糊成一个菱形，而"太阳还是月亮"是这一个按钮**唯一**的信息）。
 * `stroke` 取 `currentColor`，所以颜色跟着按钮的文字色走，深浅两套主题各自动作。
 *
 * <h2>为什么图标画的是"切过去会变成什么"，而不是"现在是什么"</h2>
 * 一个安静的图标（现在是什么）要靠读的人自己去推"点它会发生什么"，
 * 而这里画的 `☀` 意思直接就是"点一下变浅色"。所以文案与图标都由**目标态**决定，
 * `aria-label` 也写成动作（"切换到深色模式"）—— 读屏用户拿到的与看到的是同一件事。
 *
 * <h2>为什么不用 antd 的 Switch</h2>
 * Switch 是一个"开/关"的形状，会让人以为其中一态是"关掉的"；而这两个状态地位相同。
 * 按钮上带 `aria-pressed`，所以"现在按下去没有"仍然可读（自检也读它，不读样式）。
 */
export function ThemeToggle({ mode, onToggle }: { mode: ThemeMode; onToggle: () => void }) {
  const dark = mode === 'dark'
  const label = dark ? '切换到浅色模式' : '切换到深色模式'

  return (
    <Tooltip title={label}>
      <Button
        className="theme-toggle"
        onClick={onToggle}
        aria-pressed={dark}
        aria-label={label}
        // 自检直接读这个属性判主题，不去解析 class 或颜色
        data-theme-mode={mode}
      >
        <svg
          className="theme-icon"
          viewBox="0 0 24 24"
          width="16"
          height="16"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          aria-hidden="true"
          focusable="false"
        >
          {dark ? (
            /* 切过去是浅色 → 画太阳 */
            <>
              <circle cx="12" cy="12" r="4" />
              <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
            </>
          ) : (
            /* 切过去是深色 → 画月亮（一条"缺了一口的圆"的路径） */
            <path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a6.8 6.8 0 0 0 10.5 10.5z" />
          )}
        </svg>
      </Button>
    </Tooltip>
  )
}
