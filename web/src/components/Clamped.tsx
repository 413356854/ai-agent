import { useEffect, useRef } from 'react'

/**
 * 一段"默认夹住、可展开"的文字。
 *
 * <h2>它为什么不是自己带一个「展开」按钮</h2>
 * 卡片整张是一个按钮（点开详情），按钮里不能再放按钮 —— 而同一个展开开关要管
 * 标题与执行结果两处。所以这里只负责"渲染 + 说出这段有没有被夹掉"，
 * 开关画在哪、管几段，由调用方决定（见 AgentTaskPanel 的 TaskCard）。
 *
 * <h2>★ 判据与渲染必须分开：量的是"内容比上限高不高"，渲染走的是另一条路</h2>
 * 判据：`scrollHeight`（内容高度，被 `max-height` 截过之后它仍然是完整内容的高度）
 * 比 `maxHeight`（调用方给的常数）大出 1px 以上 = 夹掉了。这个数只取决于内容与块宽，
 * **与当前展开到多高无关**。
 *
 * 渲染：夹住时由 CSS 给上限，展开时加一个 `is-open` 类把上限放开
 * （`.task-card .is-clamped.is-open`，`!important` 压过这里的行内值 —— 理由写在那条规则上）。
 *
 * 为什么非要分成两条路：`useEffect` 在**样式重算之前**跑（React 的 passive effect），
 * 所以挂载时若按"展开"渲染再靠量出来的结果去收，量到的会是展开时的高度 ——
 * 结论永远是"没夹掉"，开关不出现，卡片就停在展开态。自检实测过这一版：
 * 内容 162px / 露出 162px，而开关与「展开全文」全都测不到。
 *
 * <h2>高度从哪来</h2>
 * `maxHeight` 由调用方按内容给（行高 × 行数 + 上下 padding）—— 这里收的是像素而不是行数，
 * 是因为同一个开关要管的两段字号不同（标题 14px、结果 12px）。
 *
 * <h2>什么时候重新量</h2>
 * 文字变了（换成另一条任务）、或这一段自己的尺寸变了（窗口缩放 / 看板列的 flex 宽度变化，
 * 由 `ResizeObserver` 盯着）。展开**不在**其中：它不改内容、也不改块宽。
 * 而量出来的值是从"没夹掉"翻到"夹掉了"时要重画一次 —— 所以重画的起手式是展开态，
 * 等这一次量完再收起来（这就是上面两条路的分工）。
 */
export function Clamped({ text, className, maxHeight, open, onOverflow }: {
  text: string
  className?: string
  /** 收起时的高度上限（px），按字号与行高算好由调用方给。 */
  maxHeight: number
  /** 展开到全部内容。调用方只在"确实夹掉了"时才让它是真。 */
  open: boolean
  /** 这一段有没有被夹掉。每次重新量过之后才回调，值没变时不重复回调。 */
  onOverflow: (region: HTMLElement, overflow: boolean) => void
}) {
  const ref = useRef<HTMLSpanElement>(null)
  /** 回调存进 ref：它每次渲染都是新函数，写进 effect 依赖会让观察器反复重建。 */
  const report = useRef(onOverflow)
  report.current = onOverflow

  /** 上一次量出来的结果。存下来只为"值没变就不回调"，判断本身每次都当场量。 */
  const lastOverflow = useRef<boolean | null>(null)

  // ⚠️ 依赖里没有 `open`：见上面第二条。加进去会让"展开"把判据重算一遍
  useEffect(() => {
    const region = ref.current
    if (!region) return
    const measure = () => {
      const overflow = region.scrollHeight > maxHeight + 1
      if (lastOverflow.current === overflow) return
      lastOverflow.current = overflow
      report.current(region, overflow)
    }
    // 挂载当场量一次：`ResizeObserver` 的首次回调不保证在这一帧之前到
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(region)
    return () => observer.disconnect()
  }, [text, maxHeight])

  return (
    <span
      ref={ref}
      className={`${className ? `${className} ` : ''}is-clamped${open ? ' is-open' : ''}`}
      data-clamped={open ? 'no' : 'yes'}
      style={{ maxHeight }}
    >
      {text}
    </span>
  )
}
