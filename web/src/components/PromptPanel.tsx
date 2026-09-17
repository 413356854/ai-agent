import { Alert, Button, Card, Descriptions, Empty, Flex, Input, Segmented, Space, Switch, Table, Tag, Tooltip, Typography } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import type { PromptCheck, PromptDiffLine, PromptInfo, PromptVersionSummary } from '../api/prompt'
import type { UsePromptResult } from '../hooks/usePrompt'

const { Text } = Typography

/**
 * 第七种模式：Prompt 版本管理（阶段四 · 工程化第 3 件）。
 *
 * <h2>这一页要回答的问题</h2>
 * 阶段四的验收口径是「Prompt 改坏<b>能回滚</b>」。拆开就是三件事，
 * 页面的三块正好各答一件：
 * <ol>
 *   <li><b>现在生效的是哪一版、它长什么样</b> —— 顶部那一栏（含指纹与 token）；</li>
 *   <li><b>哪一版更好、这次改了什么</b> —— 版本表 + 差异视图；</li>
 *   <li><b>怎么回去</b> —— 每一行上的「回滚」，以及编辑器里的「存为新版本」。</li>
 * </ol>
 *
 * <h2>页面上最重要的三个设计决定（每一条都是为了防一种具体的误读）</h2>
 *
 * <h3>① 「存」与「生效」是两个按钮，而且保存之后必须说"尚未生效"</h3>
 * 后端就是这么设计的（见 {@code PromptService.save} 的注释）。界面上如果把它们合并成一个
 * 「保存并生效」，一次粘贴失误就直接作用到线上了 —— 而这个功能的全部价值在于
 * <b>"改坏了能回滚"</b>，那不是"不让你改坏"，而是"改坏了有退路"。
 * 两次决定让退路出现在生效之前。
 *
 * <h3>② 警告必须显示，而且要和"拒绝"分开</h3>
 * 后端把判据分成 REJECT / WARN 两级。如果界面只显示被拒的那些，
 * 「篇幅只有上一版的 40%」这类警告就永远没人看见 —— 而<b>提示词改坏最典型的形态恰恰是警告</b>
 * （内容非空、结构齐全、就是被截掉了一段）。级别用颜色区分，
 * 但两者都要带 `detail`：那句话里有具体数字，是使用者唯一能据以行动的信息。
 *
 * <h3>③ 409（前提过期）与 400（内容不合规）渲染成完全不同的东西</h3>
 * 400 要列出逐条判据（改内容就行）；409 要给"现在是第几版"和一个刷新按钮
 * （<b>重试同一个请求永远不会成功</b>）。两者都渲染成一句红字的后果很具体：
 * 使用者会对着 409 反复点同一个按钮。这与阶段一那条"分清 429/5xx 和 4xx"是同一类判断。
 *
 * <h2>为什么版本表里要显示指纹与 token 而不仅是版本号</h2>
 * 版本号只说"这是第几版"，而读者真正要判断的是"它和我记得的那个是不是同一份"。
 * 指纹回答后者；token 回答"换这一版会不会让预算变紧"——
 * 而预算变紧的副作用在别处完全看不出来（只表现为历史被更早裁掉）。
 */
export function PromptPanel({ prompt }: { prompt: UsePromptResult }) {
  const { list, versions } = prompt
  const [pendingActivate, setPendingActivate] = useState<number | null>(null)

  const prompts = list.status === 'ok' ? list.data : []
  const current: PromptInfo | undefined = prompts.find(p => p.kind === prompt.kind)
  const versionRows = versions.status === 'ok' ? versions.data : []

  const columns: ColumnsType<PromptVersionSummary> = [
    {
      title: '版本',
      dataIndex: 'version',
      width: 92,
      render: (version: number, row) => (
        <Space size={4}>
          <Text strong>v{version}</Text>
          {row.active && <Tag color="green">生效中</Tag>}
        </Space>
      ),
    },
    {
      title: '改动说明',
      dataIndex: 'note',
      render: (note: string | null, row) => (
        <Flex vertical gap={2}>
          {/* note 为空是**老数据**的形态，不是"这里没东西" —— 必须说出来 */}
          <Text>{note ?? '（这一版没有写说明）'}</Text>
          {row.forkedFrom != null && (
            <Text type="secondary" className="prompt-meta">
              复制自第 {row.forkedFrom} 版（回滚产生的新版本）
            </Text>
          )}
        </Flex>
      ),
    },
    {
      title: '篇幅',
      key: 'size',
      width: 150,
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Text className="prompt-num">{row.chars} 字符</Text>
          <Text type="secondary" className="prompt-meta">约 {row.tokens} tokens</Text>
        </Flex>
      ),
    },
    {
      title: '指纹',
      dataIndex: 'hash',
      width: 120,
      render: (hash: string) => (
        <Tooltip title="正文的 SHA-256 前 12 位：用来确认『页面上这一版』与『库里那一版』是同一份">
          <Text code className="prompt-hash">{hash}</Text>
        </Tooltip>
      ),
    },
    {
      title: '作者 / 时间',
      key: 'who',
      width: 160,
      render: (_, row) => (
        <Flex vertical gap={2}>
          {/* 作者为空是「没署名」，与「署名成了空」是两件事 —— 不要都渲染成空白 */}
          <Text>{row.author ?? '（未署名）'}</Text>
          <Text type="secondary" className="prompt-meta">{formatTime(row.createdAt)}</Text>
        </Flex>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 210,
      render: (_, row) => (
        <Space size={4} wrap>
          <Button size="small" onClick={() => void prompt.editVersion(row.version)}>
            查看 / 编辑
          </Button>
          <Button
            size="small"
            type="primary"
            disabled={row.active || prompt.busy}
            onClick={() => setPendingActivate(row.version)}
          >
            {row.active ? '已生效' : '生效'}
          </Button>
          <Button
            size="small"
            danger
            disabled={row.active || prompt.busy}
            onClick={() => void prompt.rollback(row.version)}
          >
            回滚到这一版
          </Button>
        </Space>
      ),
    },
  ]

  return (
    <div className="diag-panel prompt-panel">
      {/* ---------------- 顶部：现在生效的是哪一版 ---------------- */}
      <Card size="small" className="diag-card prompt-head" title="Prompt 版本管理">
        <Flex vertical gap={12}>
          <Flex align="center" gap={12} wrap>
            <Segmented
              value={prompt.kind}
              onChange={v => prompt.select(v as UsePromptResult['kind'])}
              options={prompts.length > 0
                ? prompts.map(p => ({ label: p.kind === 'system-agent-tools' ? 'Agent 循环' : '对话与结构化', value: p.kind }))
                // 清单还没回来时也要有可点的选项 —— 否则第一屏是一个空白的切换器，
                // 而"空"看起来像"这个功能没有内容"
                : [
                  { label: 'Agent 循环', value: 'system-agent-tools' },
                  { label: '对话与结构化', value: 'system-agent' },
                ]}
            />
            {/* className 不只是为了样式：antd 会给两个汉字的按钮文案插一个空格
                （"刷新" → "刷 新"），所以自检里按下标去点那个按钮很脆 ——
                给它一个稳定的选择器比在断言里写正则更可靠 */}
            <Button size="small" className="prompt-reload" onClick={prompt.reload} disabled={prompt.busy}>
              刷新
            </Button>
          </Flex>

          {current && (
            <Descriptions size="small" column={2} className="prompt-current">
              <Descriptions.Item label="生效版本">
                {current.activeVersion != null
                  ? <Space size={6}>
                    <Text strong>v{current.activeVersion}</Text>
                    <Text code className="prompt-hash">{current.activeHash}</Text>
                  </Space>
                  : <Text type="warning">库里还没有版本（当前用出厂文件兜底）</Text>}
              </Descriptions.Item>
              <Descriptions.Item label="当前篇幅">
                {current.activeChars} 字符 · 约 {current.activeTokens} tokens
              </Descriptions.Item>
              <Descriptions.Item label="这一份管什么" span={2}>
                <Text type="secondary">{current.summary}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="谁在读它" span={2}>
                {/* 让"改了哪一份会影响哪些功能"一眼可见 —— 否则要靠翻代码 */}
                <Text type="secondary">{current.readBy}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="出厂文件" span={2}>
                <Text type="secondary">
                  {current.classpathLocation}
                  　（库空时的兜底来源；回滚的最终去处是库里的第 1 版，不是这个文件）
                </Text>
              </Descriptions.Item>
            </Descriptions>
          )}

          {current?.source === 'classpath' && (
            <Alert
              type="warning"
              showIcon
              title="当前内容来自 classpath 出厂文件，而不是版本库"
              description="说明启动初始化没有把出厂版本写进库。此时仍能正常跑（有兜底），但『回滚到最初那一版』没有目标 —— 重启并查看启动日志里的初始化失败原因。"
            />
          )}

          {prompt.hotReload && (
            <Alert
              type="info"
              showIcon
              className="prompt-hotreload"
              title="热更新：改了之后什么时候生效"
              description={
                <Flex vertical gap={4}>
                  <Text>✅ 会变：{prompt.hotReload.takesEffect.join('；')}</Text>
                  {/* 这一条比上面那条更要紧：读者会默认"全都立刻变" */}
                  <Text type="warning">⚠️ 不会变：{prompt.hotReload.doesNotAffect.join('；')}</Text>
                </Flex>
              }
            />
          )}
        </Flex>
      </Card>

      {/* ---------------- 反馈：冲突（409）与拒绝（400）分开 ---------------- */}
      {prompt.conflict && (
        <Alert
          type="warning"
          showIcon
          className="prompt-conflict"
          title="你的前提已经过期，这次操作没有执行"
          description={
            <Flex vertical gap={6}>
              <Text>{prompt.conflict}</Text>
              <Text type="secondary">
                这种情况不是内容有问题（那是另一种错误），而是<b>有人在你看页面的时候改过了</b>。
                刷新看到当前版本之后再决定就行 —— 直接重试同一个请求永远不会成功。
              </Text>
              <Space>
                <Button size="small" type="primary" className="prompt-conflict-reload" onClick={prompt.reload}>
                  刷新
                </Button>
                <Button size="small" onClick={prompt.clearFeedback}>知道了</Button>
              </Space>
            </Flex>
          }
        />
      )}

      {prompt.action.status === 'error' && !prompt.conflict && (
        <Alert type="error" showIcon className="prompt-error" title="操作失败" description={prompt.action.error} />
      )}

      {prompt.action.status === 'ok' && (
        <Alert
          type="success"
          showIcon
          className="prompt-ok"
          title={actionTitle(prompt.action.data.kind)}
          description={
            <Flex vertical gap={4}>
              <Text>{prompt.action.data.message}</Text>
              {prompt.action.data.kind === 'saved' && (
                <Text type="secondary">
                  它<b>还没有生效</b>。确认篇幅与内容没问题之后，在下面的版本表里点「生效」。
                </Text>
              )}
              {prompt.action.data.kind === 'rolledback' && prompt.action.data.result?.createdVersion != null && (
                <Text type="secondary">
                  回滚产生的是一版<b>新</b>版本（v{prompt.action.data.result.createdVersion}），
                  而不是把指针挪回去 —— {prompt.action.data.result.note}
                </Text>
              )}
              {prompt.action.data.result?.noOp === true && (
                <Text type="warning">
                  这次没有新增版本：当前内容已经与目标版完全相同。
                </Text>
              )}
            </Flex>
          }
        />
      )}

      {/* 警告：放行了，但必须让人看见（提示词改坏最典型的形态就在这里） */}
      {prompt.warnings.length > 0 && (
        <Alert
          type="warning"
          showIcon
          className="prompt-warnings"
          title={`有 ${prompt.warnings.length} 条警告 —— 已写入，但值得先看一眼`}
          description={<CheckList checks={prompt.warnings} prefix="警告" />}
        />
      )}

      {/* 拒绝：逐条列出判据与具体数字（含通过项，让读者知道检查了什么） */}
      {prompt.rejections.length > 0 && (
        <Alert
          type="error"
          showIcon
          className="prompt-rejections"
          title="没有写入：内容不合规"
          description={
            <Flex vertical gap={8}>
              <CheckList checks={prompt.rejections} prefix="判据" />
              <Text type="secondary">
                改内容之后重试即可。这里列的每一条都带着具体数字 —— 因为「内容过短」这种说法没法据以行动。
              </Text>
            </Flex>
          }
        />
      )}

      <div className="diag-pair prompt-main">
        {/* ---------------- 版本历史 ---------------- */}
        <Card
          size="small"
          className="diag-card diag-card-wide"
          title={`版本历史（${versionRows.length} 版，历史只追加）`}
        >
          {versions.status === 'loading' && <Text type="secondary">正在读取…</Text>}
          {versions.status === 'error' && <Alert type="error" showIcon title={versions.error} />}
          {versions.status === 'ok' && versionRows.length === 0 && (
            <Empty description="这一份提示词还没有任何版本记录" />
          )}
          {versionRows.length > 0 && (
            <Table
              size="small"
              rowKey="version"
              pagination={false}
              dataSource={versionRows}
              columns={columns}
              rowClassName={row => (row.active ? 'prompt-row-active' : '')}
            />
          )}

          {/* 生效前的二次确认：切换是**影响线上行为**的动作，而它只有一次点击的距离 */}
          {pendingActivate != null && (
            <Alert
              type="warning"
              showIcon
              className="prompt-confirm"
              title={`确认让 v${pendingActivate} 生效？`}
              description="生效之后，下一次 Agent 运行 / 下一轮对话就会用它。已经跑过的历史不受影响。"
              action={
                // antd 6 里 `Space direction="vertical"` 是**废弃用法**（会打警告，
                // 而自检有一条"控制台必须干净"）。改用 Flex vertical —— 它本来就是这个
                // 面板其它地方用的写法，一致性也更好
                <Flex vertical gap={6}>
                  <Button
                    size="small"
                    type="primary"
                    onClick={() => {
                      void prompt.activate(pendingActivate)
                      setPendingActivate(null)
                    }}
                  >
                    确认生效
                  </Button>
                  <Button size="small" onClick={() => setPendingActivate(null)}>取消</Button>
                </Flex>
              }
            />
          )}
        </Card>

        {/* ---------------- 编辑器 ---------------- */}
        <Card
          size="small"
          className="diag-card prompt-editor"
          title={prompt.editingVersion != null ? `编辑 v${prompt.editingVersion} 的副本` : '编辑提示词'}
        >
          <Flex vertical gap={10}>
            {prompt.editingVersion == null && (
              <Text type="secondary">
                先在左边的版本表里点「查看 / 编辑」把某一版载进来 ——
                从<b>已有的某一版</b>开始改，比从空白开始安全得多（不会不小心丢掉整段结构）。
              </Text>
            )}

            <Input.TextArea
              className="prompt-textarea"
              value={prompt.draft}
              onChange={e => prompt.setDraft(e.target.value)}
              autoSize={{ minRows: 14, maxRows: 26 }}
              placeholder="提示词正文（Markdown）"
              disabled={prompt.editingVersion == null}
            />
            {/* className 加在 wrapper 上的话，自检里的 `.prompt-textarea textarea`
                是**后代选择器**、会找不到东西（实测为此刻意让那一节白跑一轮）——
                所以这个类名留在 textarea 本身，并且这里补一个能在 DOM 上直接读的钩子。
                规则一句话：**给自检用的选择器要落在真实渲染出来的那个元素上**，
                而 antd 这类组件的 className 经常会落到外层 wrapper。 */}

            <Flex align="center" gap={8} wrap>
              <Text type="secondary" className="prompt-meta">
                {prompt.draft.length} 字符
                {prompt.dirty && <Tag color="orange" className="prompt-dirty">已修改，未保存</Tag>}
              </Text>
            </Flex>

            <Input
              value={prompt.note}
              onChange={e => prompt.setNote(e.target.value)}
              placeholder="改动说明（必填）：这一版为什么这么改"
              maxLength={200}
            />

            <Flex align="center" gap={8} wrap>
              <Switch size="small" checked={prompt.force} onChange={prompt.setForce} />
              <Text type="secondary" className="prompt-meta">
                确认放行（只对警告级判据生效，例如你确实要把提示词压得很短；
                <b>压不住</b>「正文为空」「围栏不配平」这类拒绝级判据）
              </Text>
            </Flex>

            <Flex gap={8} wrap>
              <Button
                type="primary"
                onClick={() => void prompt.save()}
                disabled={prompt.busy || prompt.editingVersion == null || prompt.draft.trim().length === 0}
              >
                存为新版本（不生效）
              </Button>
              <Button
                disabled={prompt.busy || prompt.editingVersion == null || !prompt.dirty}
                onClick={() => {
                  // 丢弃修改 = 把草稿还原成它来源的那一版，而不是清空
                  if (prompt.editingVersion != null) void prompt.editVersion(prompt.editingVersion)
                }}
              >
                放弃修改
              </Button>
            </Flex>

            <Text type="secondary" className="prompt-meta">
              为什么「存」和「生效」要分两次：一次粘贴失误不该直接作用到线上。
              先存、看一眼篇幅与内容，再决定要不要让它生效 —— 这样退路出现在生效<b>之前</b>。
            </Text>
          </Flex>
        </Card>
      </div>

      {/* ---------------- 差异视图 ---------------- */}
      <Card
        size="small"
        className="diag-card prompt-diff-card"
        title="版本对比：这次改了什么"
        extra={
          <Text type="secondary" className="prompt-meta">
            差异由后端按最长公共子序列算：改了中间一行，不会让它之后的所有行都显示成「已修改」
          </Text>
        }
      >
        <DiffSection prompt={prompt} versionRows={versionRows} />
      </Card>

      {/* ---------------- 边界说明 ---------------- */}
      {prompt.notes.length > 0 && (
        <Card size="small" className="diag-card" title="这一页的纪律（随数据一起交付）">
          <ul className="cost-notes prompt-notes">
            {prompt.notes.map(note => <li key={note}>{note}</li>)}
          </ul>
        </Card>
      )}
    </div>
  )
}

/** 差异区：选择一对版本 → 展示逐行差异 + token 变化。 */
function DiffSection({ prompt, versionRows }: { prompt: UsePromptResult; versionRows: PromptVersionSummary[] }) {
  const [from, setFrom] = useState<string>('')
  const [to, setTo] = useState<string>('')

  if (versionRows.length < 2) {
    return (
      <Text type="secondary">
        至少要有两版才能对比。现在只有 {versionRows.length} 版 ——
        在编辑器里存一版新的，这里就会出现「上一版 → 这一版」的差异。
      </Text>
    )
  }

  /**
   * 默认选中的一对版本：**上一版 → 最新版**。
   *
   * <h2>为什么不能两个都默认取"最新"</h2>
   * 第一版把 from/to 都默认成了最新的那一版，结果是：刚进页面时"对比"按钮
   * 因为"两个下拉选了同一个版本"而**永远是灰的**，而它旁边那句提示写的是
   * 「选了两个相同的版本」—— 读者看到的是一个"坏了"的按钮和一句像是自己操作错了的话，
   * 而他什么都没做。默认值要让**最常见的那个问题**（我刚改了什么）开箱可用。
   */
  const newest = versionRows[0].version
  const previous = versionRows.length > 1 ? versionRows[1].version : newest
  const fromValue = from === '' ? previous : Number(from)
  const toValue = to === '' ? newest : Number(to)

  return (
    <Flex vertical gap={12}>
      <Flex align="center" gap={8} wrap>
        <Text>从</Text>
        <Segmented
          size="small"
          value={fromValue}
          onChange={v => setFrom(String(v))}
          options={versionRows.map(r => ({ label: `v${r.version}`, value: r.version }))}
        />
        <Text>到</Text>
        <Segmented
          size="small"
          value={toValue}
          onChange={v => setTo(String(v))}
          options={versionRows.map(r => ({ label: `v${r.version}`, value: r.version }))}
        />
        <Button
          size="small"
          type="primary"
          disabled={prompt.busy || fromValue === toValue}
          onClick={() => void prompt.compare(fromValue, toValue)}
        >
          对比
        </Button>
        {fromValue === toValue && (
          <Text type="secondary" className="prompt-meta">选了两个相同的版本，换成不同的再看</Text>
        )}
      </Flex>

      {prompt.diff.status === 'loading' && <Text type="secondary">正在计算差异…</Text>}
      {prompt.diff.status === 'error' && <Alert type="error" showIcon title={prompt.diff.error} />}

      {prompt.diff.status === 'ok' && (
        <>
          <Space size={12} wrap>
            <Tag color={prompt.diff.data.added > 0 ? 'green' : 'default'}>新增 {prompt.diff.data.added} 行</Tag>
            <Tag color={prompt.diff.data.removed > 0 ? 'red' : 'default'}>删除 {prompt.diff.data.removed} 行</Tag>
            {/* token 变化必须带符号一起说：涨了会让预算变紧，而那个副作用在别处看不出来 */}
            <Tag color={prompt.diff.data.tokenDelta > 0 ? 'orange' : 'blue'}>
              token {prompt.diff.data.tokenDelta >= 0 ? '+' : ''}{prompt.diff.data.tokenDelta}
              （{prompt.diff.data.fromTokenEstimate} → {prompt.diff.data.toTokenEstimate}）
            </Tag>
            {prompt.diff.data.tokenDelta > 0 && (
              <Text type="secondary" className="prompt-meta">
                变长了：它的副作用是「历史对话比以前更早被裁掉」，而那个现象在别处看不到
              </Text>
            )}
          </Space>

          {prompt.diff.data.identical ? (
            <Alert
              type="success"
              showIcon
              title="两版内容完全相同"
              description="没有任何差异 —— 这一版很可能是「存了一次但内容和上一版一样」，而不是改坏了。"
            />
          ) : (
            <div className="prompt-diff">
              {prompt.diff.data.lines.map((line, index) => <DiffLine key={index} line={line} />)}
            </div>
          )}
        </>
      )}
    </Flex>
  )
}

function DiffLine({ line }: { line: PromptDiffLine }) {
  const cls = line.op === 'ADD' ? 'is-add' : line.op === 'REMOVE' ? 'is-remove' : line.op === 'GAP' ? 'is-gap' : ''
  const marker = line.op === 'ADD' ? '+' : line.op === 'REMOVE' ? '−' : line.op === 'GAP' ? '···' : ' '
  return (
    <div className={`prompt-diff-line ${cls}`}>
      <span className="prompt-diff-no">{line.fromLine ?? ''}</span>
      <span className="prompt-diff-no">{line.toLine ?? ''}</span>
      <span className="prompt-diff-marker">{marker}</span>
      {/* 用 textContent 渲染（不解析 markdown）：这里要显示的就是**原样的字节** ——
          差异视图的全部意义是"发出去的是什么"，做过任何加工就不再是证据 */}
      <span className="prompt-diff-text">{line.text === '' ? '\u00a0' : line.text}</span>
    </div>
  )
}

function CheckList({ checks, prefix }: { checks: PromptCheck[]; prefix: string }) {
  return (
    <ul className="prompt-checks">
      {checks.map((c, i) => (
        <li key={`${c.rule}-${i}`} className={`prompt-check is-${c.level.toLowerCase()}`}>
          <Tag color={c.level === 'REJECT' ? 'red' : c.level === 'WARN' ? 'orange' : 'green'}>
            {prefix}·{c.level === 'REJECT' ? '不通过' : c.level === 'WARN' ? '警告' : '通过'}
          </Tag>
          <Text strong>{c.rule}</Text>
          <Text type="secondary" className="prompt-check-detail">{c.detail}</Text>
        </li>
      ))}
    </ul>
  )
}

function actionTitle(kind: 'saved' | 'activated' | 'rolledback'): string {
  if (kind === 'saved') return '已存为新版本（尚未生效）'
  if (kind === 'activated') return '已切换生效版本'
  return '已回滚'
}

/** ISO 时间 → 本地可读。后端给的是 `Instant.toString()`，解析不出来就原样显示。 */
function formatTime(iso: string | null): string {
  if (!iso) return '（无时间）'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleString('zh-CN', { hour12: false })
}
