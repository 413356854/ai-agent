import { useState } from 'react'
import { Button, Flex, Input, Slider, Tooltip, Typography } from 'antd'

interface ComposerProps {
  /** 有请求在跑：禁用输入并切到「停止」按钮 */
  streaming: boolean
  onSend: (text: string, temperature: number) => void
  /**
   * 停止回调。**结构化模式不传** —— 结构化输出是"全有或全无"，
   * 中途取消拿不到半成品、还会白烧已经跑掉的 token，所以那种模式干脆不给停止按钮，
   * 而不是给一个点了没用的按钮。
   */
  onStop?: () => void
  /** 模式相关文案，默认按流式对话 */
  placeholder?: string
  hint?: string
  /**
   * 是否显示 temperature 滑块。
   *
   * 结构化模式**不显示**，因为它不是"用户可以调"的参数：后端为了输出稳定把 temperature
   * 钉在 0.0（见 StructuredChatServiceImpl）。给一个拖了不生效的滑块比不给更糟 ——
   * 用户会以为自己的调整没起作用，然后开始怀疑整个功能。
   */
  showTemperature?: boolean
  /** 进行中时按钮上的文字 */
  stopLabel?: string
  /** 没有 onStop 时，进行中显示的提示文字 */
  busyHint?: string
}

/**
 * 输入区。
 *
 * Enter 发送 / Shift+Enter 换行 —— 聊天类界面的默认约定，别用 Ctrl+Enter，
 * 中文输入法里 Enter 还承担「上屏候选词」，所以必须判断 e.nativeEvent.isComposing，
 * 否则选词时会误发送半截话。
 */
export function Composer({
  streaming,
  onSend,
  onStop,
  placeholder = '说点什么…（Enter 发送，Shift+Enter 换行）',
  hint,
  showTemperature = true,
  stopLabel = '停止生成',
  busyHint = '正在请求模型……',
}: ComposerProps) {
  const [text, setText] = useState('')
  const [temperature, setTemperature] = useState(0.3)

  const submit = () => {
    const content = text.trim()
    if (!content || streaming) return
    onSend(content, temperature)
    setText('')
  }

  return (
    <div className="composer">
      <Input.TextArea
        value={text}
        onChange={e => setText(e.target.value)}
        placeholder={placeholder}
        autoSize={{ minRows: 2, maxRows: 6 }}
        disabled={streaming}
        onPressEnter={e => {
          if (e.shiftKey || e.nativeEvent.isComposing) return
          e.preventDefault()
          submit()
        }}
      />
      {hint && (
        <Typography.Text type="secondary" className="composer-hint">
          {hint}
        </Typography.Text>
      )}
      <Flex align="center" justify="space-between" className="composer-bar">
        <Flex align="center" gap={12}>
          {showTemperature ? (
            <>
              <Typography.Text type="secondary">temperature</Typography.Text>
              <Slider
                className="temperature-slider"
                min={0}
                max={1}
                step={0.1}
                value={temperature}
                onChange={setTemperature}
                disabled={streaming}
                tooltip={{ formatter: value => value?.toFixed(1) }}
              />
              <Typography.Text code>{temperature.toFixed(1)}</Typography.Text>
            </>
          ) : (
            <Tooltip title="结构化输出要求稳定，后端把 temperature 钉在 0.0，故不提供调节">
              <Typography.Text type="secondary">temperature 固定 0.0（结构化输出要求稳定）</Typography.Text>
            </Tooltip>
          )}
        </Flex>

        {streaming ? (
          onStop ? (
            <Button danger onClick={onStop}>
              {stopLabel}
            </Button>
          ) : (
            <Typography.Text type="secondary">{busyHint}</Typography.Text>
          )
        ) : (
          <Button type="primary" onClick={submit} disabled={!text.trim()}>
            发送
          </Button>
        )}
      </Flex>
    </div>
  )
}
