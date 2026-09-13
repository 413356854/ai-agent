import { useState } from 'react'
import { Button, Flex, Input, Slider, Typography } from 'antd'

interface ComposerProps {
  streaming: boolean
  onSend: (text: string, temperature: number) => void
  onStop: () => void
}

/**
 * 输入区。
 *
 * Enter 发送 / Shift+Enter 换行 —— 聊天类界面的默认约定，别用 Ctrl+Enter，
 * 中文输入法里 Enter 还承担「上屏候选词」，所以必须判断 e.nativeEvent.isComposing，
 * 否则选词时会误发送半截话。
 */
export function Composer({ streaming, onSend, onStop }: ComposerProps) {
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
        placeholder="说点什么…（Enter 发送，Shift+Enter 换行）"
        autoSize={{ minRows: 2, maxRows: 6 }}
        disabled={streaming}
        onPressEnter={e => {
          if (e.shiftKey || e.nativeEvent.isComposing) return
          e.preventDefault()
          submit()
        }}
      />
      <Flex align="center" justify="space-between" className="composer-bar">
        <Flex align="center" gap={12}>
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
        </Flex>

        {streaming ? (
          <Button danger onClick={onStop}>
            停止生成
          </Button>
        ) : (
          <Button type="primary" onClick={submit} disabled={!text.trim()}>
            发送
          </Button>
        )}
      </Flex>
    </div>
  )
}
