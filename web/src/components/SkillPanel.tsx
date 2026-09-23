import {
  Alert, Button, Card, Empty, Flex, Input, Modal, Select, Space, Spin, Table, Tag, Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useEffect, useState } from 'react'
import type { AgentSkill } from '../api/skills'
import type { SkillDraft, UseSkillsResult } from '../hooks/useSkills'

const { Text, Paragraph } = Typography

/**
 * 应用管理区 · skill管理（对接 `agent_skill` 表：新建 / 修改 / 删除）。
 *
 * <h2>这一页答三个问题</h2>
 * <ol>
 *   <li><b>有哪些技能</b> —— 表格（文件夹、名字、说明、拥有者、最后修改人、最后修改时间）；
 *   <li><b>我能建在哪个文件夹下</b> —— 那个下拉里的选项集是**我全部角色的目录前缀**（后端算的）；
 *   <li><b>这条是不是我的</b> —— 不是我的行上那两个按钮是禁用的。
 * </ol>
 *
 * <h2>★ 五条不能省的说法（每条对应一种具体误读）</h2>
 * <ol>
 *   <li><b>"现在只看我拥有的那些"必须写出来</b>：没登录时列表是**全部**记录，
 *       登录之后只剩自己的 —— 屏幕上少了的那几行没有任何别的痕迹，
 *       不写这句话，用户会以为别人的技能被删了；</li>
 *   <li><b>"文件夹目录只能是这几个"必须写出来</b>：它是授权范围（角色的目录前缀），
 *       不是一个可以随便打的路径。不写清楚，用户会以为"我打一个自己的目录就能建"——
 *       而后端会 400，那一刻他已经填完了整张表单；</li>
 *   <li><b>「拥有者」不是输入框，而是一句回显</b>：新增时它由服务端按当前登录人填
 *       （请求体里根本没有这个字段）。画成输入框的话，用户会以为能替别人建 —— 而那个值不会被采纳；</li>
 *   <li><b>未登录时按钮禁用，而不是点下去才报错</b>：写端点未登录一律 400，
 *       而"点了才被拒"会把一次可以避免的失败变成一次白填表单。禁用的同时要说清去哪儿登录；</li>
 *   <li><b>删除是一次真删，所以弹窗里要写清删的是哪一条</b>：与账号那条"只停用不删除"不同，
 *       这一页的删除没有回头路（列表上那一行会真的消失）——所以确认语里带上文件夹与名字。</li>
 * </ol>
 *
 * <h2>为什么"编辑"那一格能改文件夹，而"拥有者"不能改</h2>
 * 文件夹是这条技能的内容（放在哪儿），它同样受"可选范围"约束；
 * 而拥有者是归属，它只在新增那一刻由服务端写下 —— 这一页没有任何入口能转移归属
 * （见 `agent_skill` 的注释：归属不随编辑变化）。
 *
 * <h2>为什么说明那一列是夹住的</h2>
 * 与任务卡片同一条理由：「说明」可能是一整段。夹到两行（CSS 的 `-webkit-line-clamp`）、
 * 完整的那一份留在 `title` 上 —— 一张写着长说明的表格会把其它行挤到看不见，
 * 而这一页要看的是"有哪些技能"。
 */
export function SkillPanel({ skills, userId, loginName, onLoginRequest }: {
  skills: UseSkillsResult
  /** 当前登录那个人的用户 id（`useMockLogin` 记下的那一份）；没登录是 `null`。 */
  userId: number | null
  /** 当前登录那个人的显示名（`mockLoginLabel` 算出来的那一份）；没登录是 `null`。 */
  loginName: string | null
  /** 「去登录」那一个按钮：登录控件在页头上，这一层只负责告诉它"该开门了"。 */
  onLoginRequest: () => void
}) {
  const { snapshot } = skills
  const data = snapshot.status === 'ok' ? snapshot.data : null
  const [editing, setEditing] = useState<AgentSkill | null>(null)
  const [creating, setCreating] = useState(false)
  const [confirming, setConfirming] = useState<AgentSkill | null>(null)

  const rows = data?.skills ?? []
  const folders = data?.folders ?? []
  const loggedIn = userId != null

  /** 这一行能不能动：**拥有者等于当前登录人**。判据与后端一致（后端再判一次，这里只是把按钮禁用掉）。 */
  const mine = (row: AgentSkill) => loggedIn && row.ownerId === userId

  const columns: ColumnsType<AgentSkill> = [
    {
      title: '文件夹目录',
      key: 'folder',
      width: 220,
      render: (_, row) => (
        <Text code className="skill-folder" title={row.folder}>{row.folder}</Text>
      ),
    },
    {
      title: '技能',
      key: 'name',
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Text strong className="skill-name">{row.name}</Text>
          {/* 说明为空是**没填**，不是"说明是空的" —— 与账号页「（未填显示名）」同一条规矩。
              夹两行由 CSS 做（`-webkit-line-clamp`），完整的那一份在 title 上 ——
              这一列是"一眼看全有哪些技能"用的，一行长说明会把其它行挤下去 */}
          <span className="skill-description" title={row.description || ''}>
            {row.description || '（未填说明）'}
          </span>
        </Flex>
      ),
    },
    {
      title: '拥有者',
      key: 'owner',
      width: 150,
      render: (_, row) => (
        <Space size={4} wrap>
          <Text>{row.ownerName}</Text>
          {mine(row) && <Tag color="green">我</Tag>}
        </Space>
      ),
    },
    {
      title: '最后修改',
      key: 'modified',
      width: 210,
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Text>{row.lastModifiedByName}</Text>
          <Text type="secondary" className="skill-updated-at" title={row.updatedAt}>
            {shortTime(row.updatedAt)}
          </Text>
        </Flex>
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_, row) => (
        <Space size={4} wrap>
          <Button
            size="small"
            className="skill-edit"
            disabled={!mine(row) || skills.busy}
            title={mine(row) ? '改这一条的内容' : '只有拥有者能改：这一条是 ' + row.ownerName + ' 的'}
            onClick={() => setEditing(row)}
          >
            编辑
          </Button>
          <Button
            size="small"
            danger
            className="skill-delete"
            disabled={!mine(row) || skills.busy}
            title={mine(row) ? '删掉这一条（没有回头路）' : '只有拥有者能删：这一条是 ' + row.ownerName + ' 的'}
            onClick={() => setConfirming(row)}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ]

  if (snapshot.status === 'loading' || snapshot.status === 'idle') {
    // antd 6 里 Spin 的提示文案叫 description（写成旧名 tip 只在控制台留一条 deprecation 警告）
    return <div className="diag-panel"><Spin description="正在读技能表…" /></div>
  }
  if (snapshot.status === 'error') {
    const error = snapshot.error
    return (
      <div className="diag-panel">
        <Alert
          type="error"
          showIcon
          title="读技能表失败"
          description={<div className="diag-note">
            <p>{error}</p>
            <p>这一族端点在 <Text code>/api/skills</Text>，后端没起或端口不同时都会走到这里。
              库里若还挂着一个失效的用户 id，<Text code>?userId=</Text> 那一份会回 400 ——
              那说明这次登录的人已经不在账号表里了，重新登录一次即可。</p>
          </div>}
        />
      </div>
    )
  }

  return (
    <div className="diag-panel skill-panel">
      <SkillFeedback skills={skills} />

      {/* 未登录那一句：它解释的是"为什么按钮点不动"，而按钮上写不下这句话 */}
      {!loggedIn && (
        <Alert
          type="warning"
          showIcon
          className="skill-login-notice"
          title="还没有登录：现在看得到全部技能，但新建 / 修改 / 删除都不可用"
          description={<Flex gap={8} align="center" wrap>
            <Text>拥有的判定要看"我是谁"（拥有者 = 当前登录的人）。技能不会因此消失，登录之后就只看自己的那些。</Text>
            <Button size="small" className="skill-login-entry" onClick={onLoginRequest}>去登录</Button>
          </Flex>}
        />
      )}

      <Card
        size="small"
        className="diag-card diag-card-wide"
        title={<Flex gap={8} align="center" wrap>
          <span>技能（{data?.total ?? 0}）</span>
          <Tag color="geekblue">拥有者 = 建它的人 · 只有拥有者能改能删</Tag>
        </Flex>}
        extra={<Space>
          <Button onClick={skills.reload} disabled={skills.busy}>刷新</Button>
          <Button
            type="primary"
            className="skill-create"
            disabled={!loggedIn}
            title={loggedIn ? '新建一条技能' : '未登录不能新增：请先在页头「模拟登录」里选一个人'}
            onClick={() => setCreating(true)}
          >
            新建技能
          </Button>
        </Space>}
      >
        {/* 筛没筛、按谁筛，由**后端回显的那一格**说了算（`data.userId`）——
            这里再推一份"我登录了没有"就是第二份判据 */}
        <div className="skill-scope">
          {data?.userId == null
            ? '未登录：这是全部技能（先在上方「模拟登录」里选一个人，就只看自己拥有的那些）。'
            : `只看 ${loginName ?? data.userName ?? '当前登录人'} 拥有的技能（用户 id ${data.userId}）`
              + ' —— 别人建的不在这一份里，退出登录即可看全部。'}
        </div>
        {/* 文件夹选项集这一行：它不是"这一页的数据"，而是"我能建在哪儿"。
            空的选项集有两种原因，必须分开说 —— 合成一句"没有可选文件夹"会让人以为这一页坏了 */}
        <div className="skill-folders">
          {!loggedIn
            ? '未登录：没有可选的文件夹（文件夹目录来自登录人角色的目录前缀）。'
            : folders.length === 0
              ? '当前登录人的角色一个目录前缀都没有配 —— 没有任何文件夹可选，因此建不了技能。'
                + '去「权限管理」给它的角色配一条目录前缀（角色 → 目录）。'
              : `可选文件夹（${folders.length}）：${folders.join('、')} —— 它来自当前登录人全部角色的目录前缀，`
                + '改「权限管理」里那些角色的目录即可增删。'}
        </div>
        {rows.length === 0
          ? <Empty description={data?.ownershipJudged
            ? '你还没有技能。点「新建技能」建一条：文件夹只能选上面列出的那些。'
            : '还没有技能。先在上方「模拟登录」里选一个人，就能新建一条。'} />
          : <Table
              rowKey="id"
              size="small"
              pagination={false}
              columns={columns}
              // 行上带一个稳定的归属标记：自检按它判"哪几行是我的"，
              // 而照着 ownerName 的文字去判会在改人名之后失效
              onRow={row => ({ 'data-owner-id': row.ownerId, 'data-skill-id': row.id } as never)}
              dataSource={rows}
            />}
      </Card>

      <SkillFormModal
        open={creating || editing !== null}
        skill={editing}
        folders={folders}
        ownerName={loginName ?? data?.userName ?? ''}
        skills={skills}
        onClose={() => { setCreating(false); setEditing(null) }}
      />

      <Modal
        open={confirming !== null}
        title="删除技能"
        okText="删除"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        confirmLoading={skills.busy}
        onOk={() => {
          const target = confirming
          if (!target) return
          void skills.remove(target.id, target.name).then(ok => { if (ok) setConfirming(null) })
        }}
        onCancel={() => setConfirming(null)}
      >
        {confirming && (
          <Flex vertical gap={8}>
            <Paragraph>
              将删除 <Text strong>《{confirming.name}》</Text>（文件夹 <Text code>{confirming.folder}</Text>，
              拥有者 {confirming.ownerName}）。
            </Paragraph>
            <Text type="danger">
              这一页的删除没有回头路：那一行会真的从库里消失（账号那种「只停用不删除」的规矩
              在这张表上不适用）。
            </Text>
          </Flex>
        )}
      </Modal>
    </div>
  )
}

/** 写操作反馈：原样显示后端那句话（理由见 useSkills 的文件头）。 */
function SkillFeedback({ skills }: { skills: UseSkillsResult }) {
  return (
    <>
      {skills.feedback.status === 'ok' && (
        <Alert type="success" showIcon closable title={skills.feedback.data.message}
          onClose={skills.clearFeedback} />
      )}
      {skills.feedback.status === 'error' && (
        <Alert type="error" showIcon closable title={skills.feedback.error}
          onClose={skills.clearFeedback} />
      )}
    </>
  )
}

/**
 * 建 / 改技能的表单弹窗。
 *
 * <p>`skill` 为 `null` 是新建。草稿是**弹窗自己的状态**（不是受控于父组件）：
 * 关掉就清空 —— 保存失败时它不关（用户要照着报错改一改再提交），
 * 所以"清空"只发生在真的关掉之后，而不是每一次 render。
 *
 * <p>「拥有者」那一格是**只读的一行字**，不是输入框：这个字段由服务端按当前登录人填，
 * 请求体里根本没有它（见 `api/skills.ts` 的第二条后端事实）。
 */
function SkillFormModal({ open, skill, folders, ownerName, skills, onClose }: {
  open: boolean
  /** 正在编辑的那一条；`null` 表示新建。 */
  skill: AgentSkill | null
  folders: string[]
  ownerName: string
  skills: UseSkillsResult
  onClose: () => void
}) {
  const [draft, setDraft] = useState<SkillDraft>({ folder: '', name: '', description: '', content: '' })

  // 每次打开都从"那一条当前的样子"重来：编辑到一半关掉再打开，看到的该是库里那一份，
  // 而不是上一次没提交的草稿（草稿留在那里，用户会以为已经保存了）
  useEffect(() => {
    if (!open) return
    setDraft(skill
      ? { id: skill.id, folder: skill.folder, name: skill.name, description: skill.description, content: skill.content }
      : { folder: folders[0] ?? '', name: '', description: '', content: '' })
  }, [open, skill, folders])

  const submit = async () => {
    if (!draft.name.trim() || !draft.folder) return
    const ok = skill
      ? await skills.update({ ...draft, id: skill.id })
      : await skills.create(draft)
    if (ok) onClose()
  }

  const editing = skill !== null
  const target = skill
  // 编辑时把当前那一份也放进候选：角色的目录前缀被改掉之后，
  // 这一条记录可能停在"现在已经不在选项集里"的文件夹上 —— 不让它出现的话，
  // 用户一打开编辑框就会看到文件夹被悄悄换成了别的（而保存会把记录挪过去）
  const options = target !== null && !folders.some(f => f.toLowerCase() === target.folder.toLowerCase())
    ? [target.folder, ...folders]
    : folders

  return (
    <Modal
      open={open}
      title={editing ? `编辑技能：${skill.name}` : '新建技能'}
      okText={editing ? '保存' : '创建'}
      cancelText="取消"
      width={640}
      confirmLoading={skills.busy}
      okButtonProps={{ disabled: !draft.name.trim() || !draft.folder }}
      onOk={() => void submit()}
      onCancel={onClose}
    >
      <Flex vertical gap={10}>
        <Flex vertical gap={4}>
          <Text type="secondary">文件夹目录 —— 只能是当前登录人角色的目录前缀之一</Text>
          <Select
            className="skill-folder-select"
            value={draft.folder || undefined}
            placeholder="先在上面选一个文件夹"
            onChange={folder => setDraft({ ...draft, folder })}
            options={options.map(folder => ({ value: folder, label: folder }))}
            style={{ width: '100%' }}
          />
        </Flex>
        <Flex vertical gap={4}>
          <Text type="secondary">技能名 —— 同一个人的同一个文件夹下不能重名（重名会被后端拒）</Text>
          <Input
            className="skill-name-input"
            placeholder="技能名"
            value={draft.name}
            onChange={e => setDraft({ ...draft, name: e.target.value })}
          />
        </Flex>
        <Flex vertical gap={4}>
          <Text type="secondary">说明 —— 这个技能是干什么的</Text>
          <Input
            className="skill-description-input"
            placeholder="这个技能是干什么的（可留空）"
            value={draft.description}
            onChange={e => setDraft({ ...draft, description: e.target.value })}
          />
        </Flex>
        <Flex vertical gap={4}>
          <Text type="secondary">正文 —— 技能内容本身</Text>
          <Input.TextArea
            className="skill-content-input"
            placeholder="技能正文（可留空；把这里删光再保存 = 清空正文）"
            rows={8}
            value={draft.content}
            onChange={e => setDraft({ ...draft, content: e.target.value })}
          />
        </Flex>
        <Text type="secondary" className="skill-owner-line">
          拥有者：<Text strong>{ownerName || '当前登录人'}</Text>
          {editing
            ? '（不改：归属在新增那一刻写下，编辑不会转移它）'
            : '（由服务端按当前登录人填入，这一格不能填）'}
          ；最后修改人：<Text strong>{ownerName || '当前登录人'}</Text>
          （本次保存之后由服务端记下）
        </Text>
      </Flex>
    </Modal>
  )
}

/**
 * 表格里那一格时间：把 ISO-8601 裁成 `YYYY-MM-DD HH:MM`。
 *
 * <p>裁不出来的原样返回 —— 一个格式不符的字符串显示成一格空白，会让人以为"这条没有时间"。
 * 完整值留在 `title` 上（悬停可见），所以这一刀没有丢信息。
 */
function shortTime(iso: string): string {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(iso)
  return match ? `${match[1]} ${match[2]}` : iso
}
