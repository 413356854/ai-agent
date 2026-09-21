import {
  Alert, Button, Card, Checkbox, Empty, Flex, Input, Modal, Select, Space, Spin, Table, Tag, Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import type { PermissionRole, PermissionUser } from '../api/permissions'
import type { UsePermissionsResult } from '../hooks/usePermissions'
import { PermissionFeedback } from './PermissionNotice'

const { Text, Paragraph } = Typography

/**
 * 应用管理区 · 权限管理（角色：能调哪些工具 / 能在哪些目录下干活 / 有哪些成员）。
 *
 * <h2>这一页要回答的两个问题</h2>
 * <ol>
 *   <li><b>有哪些角色、各自配了什么</b> —— 角色表（工具白名单 + 目录前缀）；</li>
 *   <li><b>这个人到底能不能干这件事</b> —— 核对卡（把解析结果摆出来）。</li>
 * </ol>
 *
 * <h2>为什么「谁有这个角色」不在角色表里</h2>
 * 成员是<b>点「成员」才查</b>的：那一列要随账号数增长，而且它回答的是"这个角色对谁生效"——
 * 与"这个角色配了什么"（工具 / 目录）是不同的两个问题，摆在同表里每行就有两个主语。
 * 弹窗里勾着的就是它的全部成员，于是"看谁有它"与"改谁有它"落在同一处，不会有两份说法。
 *
 * <h2>★ "空权限"那两句话不能省</h2>
 * 角色上的工具清单为空 = 一把工具都调不到（角色表里那枚橙色标签）；
 * 成员为空 = 这份工具与目录对谁都不生效（成员弹窗里那条警告）。
 * 这两处在库里都只是一个空集合，而读者对空的默认预期是"没有限制"——恰好是反的。
 *
 * <h2>为什么角色行上四个编辑入口各开一个弹窗</h2>
 * 工具 / 目录 / 成员 / 基本信息是四件独立的事，各对应一个写端点（各自整体替换）。
 * 合成一个"保存"去提交全部，会让"只改目录"也去重写工具清单与成员 ——
 * 那正是 {@code PermissionService} 特意分开的理由（见它的接口注释）。
 *
 * <h2>为什么错误信息原样显示后端那一句</h2>
 * 后端的报错本来就带够了信息（"工具「search_doc」不在注册表里……现在可用的是：[…]"）。
 * 前端再翻译一遍只会让两处文案漂移 —— 与 Prompt 面板那条"接口给了正确的话，界面要用它"同源。
 */
export function PermissionPanel({ permissions }: { permissions: UsePermissionsResult }) {
  const { snapshot } = permissions
  const data = snapshot.status === 'ok' ? snapshot.data : null

  const [roleModal, setRoleModal] = useState(false)
  const [editing, setEditing] = useState<PermissionRole | null>(null)
  const [toolsOf, setToolsOf] = useState<PermissionRole | null>(null)
  const [dirsOf, setDirsOf] = useState<PermissionRole | null>(null)
  const [membersOf, setMembersOf] = useState<PermissionRole | null>(null)

  const users = data?.users ?? []
  const roles = data?.roles ?? []

  const roleColumns: ColumnsType<PermissionRole> = [
    {
      title: '角色',
      key: 'role',
      // ⚠️ 宽度必须显式给：不给时 antd 把剩余宽度平摊，而"能调的工具"那一栏里
      // 是一串短标签（不会被压窄），于是富余的空间全从这一列扣 ——
      // 实测后果是角色名被拆成**一个字一行**（截图里看得出来）
      width: 220,
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Space size={6}>
            <Text strong className="permission-role-name">{row.name}</Text>
            <Tag color={row.access === 'ALL' ? 'red' : 'default'}>{row.accessLabel}</Tag>
          </Space>
          <Text type="secondary" className="permission-meta">{row.description || '（没有说明）'}</Text>
        </Flex>
      ),
    },
    {
      title: '能调的工具',
      key: 'tools',
      render: (_, row) => {
        if (row.access === 'ALL') {
          // 「全部工具」必须说清它意味着什么：将来新增的工具会自动落进这个角色
          return <Text>全部已注册工具（{data?.availableTools.length ?? 0} 把）—— <Text type="secondary">将来新增的工具会自动包含进来</Text></Text>
        }
        if (row.toolNames.length === 0) {
          return <Tag color="orange">清单为空 → 一把工具都调不到</Tag>
        }
        return <Space size={4} wrap>{row.toolNames.map(name => <Tag key={name} className="is-mono">{name}</Tag>)}</Space>
      },
    },
    {
      title: '能在哪些目录下干活',
      key: 'dirs',
      render: (_, row) => (row.dirPrefixes.length === 0
        ? <Tag color="orange">没有目录前缀 → 任何目录都不被授权</Tag>
        : <Flex vertical gap={2}>
            {row.dirPrefixes.map(prefix => <Text key={prefix} className="is-mono">{prefix}</Text>)}
          </Flex>),
    },
    {
      title: '操作',
      key: 'actions',
      // 五个按钮一行放不下，`wrap` 折行；宽度给够，免得折成"一列按钮"
      width: 320,
      render: (_, row) => (
        <Space size={4} wrap>
          <Button size="small" onClick={() => setEditing(row)}>编辑</Button>
          <Button size="small" onClick={() => setToolsOf(row)}>工具</Button>
          <Button size="small" onClick={() => setDirsOf(row)}>目录</Button>
          <Button size="small" onClick={() => setMembersOf(row)}>成员</Button>
          <Button
            size="small"
            danger
            onClick={() => {
              Modal.confirm({
                title: `删除角色「${row.name}」？`,
                // 这句话是本操作的**代价说明**：级联会真的把授权关系删掉
                content: '拥有这个角色的账号会同时失去它（外键级联）。账号本身不受影响，'
                  + '它们的权限按剩下的角色重新取并集。',
                okText: '删除',
                okButtonProps: { danger: true },
                cancelText: '取消',
                onOk: () => permissions.deleteRole(row.id),
              })
            }}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ]

  if (snapshot.status === 'loading' || snapshot.status === 'idle') {
    // ⚠️ antd 6 里 Spin 的那个提示文案叫 `description`：写成旧名 `tip` 不报错，
    // 只在控制台留一条 deprecation 警告 —— 而自检最后一条断言盯的就是控制台干净
    return <div className="diag-panel"><Spin description="正在读权限表…" /></div>
  }
  if (snapshot.status === 'error') {
    return (
      <div className="diag-panel">
        <Alert
          type="error"
          showIcon
          title="读权限表失败"
          description={<div className="diag-note">
            <p>{snapshot.error}</p>
            <p>这一族端点在关闭时（<Text code>ai.agent.permission.management-enabled=false</Text>）整族返回 404，
              所以"连不上"与"被关掉了"要分开看。</p>
          </div>}
        />
      </div>
    )
  }

  return (
    <div className="diag-panel permission-panel">
      <PermissionFeedback permissions={permissions} />

      {/* ---------------- 核对：这个人能不能干这件事 ---------------- */}
      <VerifyCard permissions={permissions} users={users} tools={data?.availableTools ?? []} />

      {/* ---------------- 角色 ---------------- */}
      <Card
        size="small"
        className="diag-card diag-card-wide"
        title={<Flex gap={8} align="center" wrap>
          <span>角色（{roles.length}）</span>
          <Tag color="geekblue">工具白名单 + 目录前缀 + 成员</Tag>
        </Flex>}
        extra={<Button type="primary" onClick={() => setRoleModal(true)}>新建角色</Button>}
      >
        {roles.length === 0
          ? <Empty description="还没有角色。账号只有挂上角色才会获得权限。" />
          : <Table
              rowKey="id"
              size="small"
              pagination={false}
              columns={roleColumns}
              dataSource={roles}
            />}
      </Card>

      <NewRoleModal open={roleModal} onClose={() => setRoleModal(false)} permissions={permissions} />
      <RoleEditModal role={editing} onClose={() => setEditing(null)} permissions={permissions} />
      <RoleToolsModal
        role={toolsOf}
        onClose={() => setToolsOf(null)}
        permissions={permissions}
        availableTools={data?.availableTools ?? []}
      />
      <RoleDirectoriesModal role={dirsOf} onClose={() => setDirsOf(null)} permissions={permissions} />
      <RoleUsersModal
        role={membersOf}
        onClose={() => setMembersOf(null)}
        permissions={permissions}
        users={users}
      />
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  核对卡                                                              */
/* ------------------------------------------------------------------ */

function VerifyCard({ permissions, users, tools }: {
  permissions: UsePermissionsResult
  users: PermissionUser[]
  tools: string[]
}) {
  const [account, setAccount] = useState<string>()
  const [tool, setTool] = useState<string>()
  const [dir, setDir] = useState('')
  const verdict = permissions.verdict

  return (
    <Card
      size="small"
      className="diag-card diag-card-wide"
      title={<Flex gap={8} align="center" wrap>
        <span>核对：这个人能不能在这把工具上、在这个目录里干活</span>
        <Tag color="geekblue">结论与运行期读的是同一份解析</Tag>
      </Flex>}
    >
      <Flex gap={8} wrap align="center">
        <Select
          className="permission-pick"
          placeholder="账号"
          value={account}
          onChange={setAccount}
          options={users.map(u => ({ value: u.name, label: u.usable ? u.name : `${u.name}（${u.statusLabel}）` }))}
        />
        <Select
          className="permission-pick"
          placeholder="工具（可留空，只问账号）"
          allowClear
          value={tool}
          onChange={setTool}
          options={tools.map(t => ({ value: t, label: t }))}
        />
        <Input
          className="permission-dir"
          placeholder="工作区目录，例如 D:\work\demo（可留空）"
          value={dir}
          onChange={e => setDir(e.target.value)}
        />
        <Button
          className="permission-verify-go"
          type="primary"
          disabled={!account || permissions.busy}
          onClick={() => void permissions.verify(account as string, tool, dir)}
        >
          核对
        </Button>
      </Flex>

      {verdict.status === 'loading' && <Spin className="permission-spin" />}      {verdict.status === 'error' && <Alert className="permission-verdict" type="error" showIcon title={verdict.error} />}
      {verdict.status === 'ok' && (
        <Alert
          className="permission-verdict"
          type={verdict.data.allowed ? 'success' : 'warning'}
          showIcon
          title={`${verdict.data.allowed ? '允许' : '不允许'} —— ${verdict.data.reason}`}
          description={<div className="diag-note">
            <p>
              工具那一半：{verdict.data.toolAllowed ? '通过' : '不通过'}
              {verdict.data.tool ? `（问的是 ${verdict.data.tool}）` : '（没问工具）'}
              ；目录那一半：{verdict.data.directoryAllowed ? '通过' : '不通过'}
              {verdict.data.matchedPrefix ? `（命中前缀 ${verdict.data.matchedPrefix}）` : ''}
            </p>
            <p>他拥有的角色：{verdict.data.roles.length === 0 ? '（一个都没有）' : verdict.data.roles.join('、')}</p>
            <p className="is-mono">
              他实际能调的工具：{verdict.data.effectiveTools.length === 0 ? '（一把都没有）' : verdict.data.effectiveTools.join('、')}
            </p>
          </div>}
        />
      )}
    </Card>
  )
}

/* ------------------------------------------------------------------ */
/*  弹窗                                                                */
/* ------------------------------------------------------------------ */

function NewRoleModal({ open, onClose, permissions }: {
  open: boolean
  onClose: () => void
  permissions: UsePermissionsResult
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [access, setAccess] = useState<'LISTED' | 'ALL'>('LISTED')

  const close = () => {
    setName(''); setDescription(''); setAccess('LISTED')
    onClose()
  }

  const submit = async () => {
    const ok = await permissions.createRole({ name, description, access })
    if (ok) close()
  }

  return (
    <Modal
      open={open}
      title="新建角色"
      okText="创建"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={10}>
        <Input placeholder="角色名（唯一）" value={name} onChange={e => setName(e.target.value)} />
        <Input placeholder="这个角色是干什么的（可留空）" value={description} onChange={e => setDescription(e.target.value)} />
        <Select
          value={access}
          onChange={value => setAccess(value)}
          options={[
            { value: 'LISTED', label: '清单白名单 —— 只能调清单里那几把工具（默认，且清单为空时一把都调不到）' },
            { value: 'ALL', label: '全部工具 —— 包括将来新增的' },
          ]}
        />
        <Text type="secondary">
          默认是<strong>清单白名单</strong>而不是"全部工具"：两个方向里贵的那一边是"多给了权限"，
          所以默认值取窄的那一边。
        </Text>
      </Flex>
    </Modal>
  )
}

function RoleEditModal({ role, onClose, permissions }: {
  role: PermissionRole | null
  onClose: () => void
  permissions: UsePermissionsResult
}) {
  // 草稿：null = "还没动过"，显示的是这个角色当前的值。
  // 关弹窗（无论保存还是取消）都清回 null —— 于是下次打开读的是新状态，而不是上一次的草稿
  const [name, setName] = useState<string | null>(null)
  const [description, setDescription] = useState<string | null>(null)
  const [access, setAccess] = useState<string | null>(null)

  const currentName = name ?? role?.name ?? ''
  const currentDescription = description ?? role?.description ?? ''
  const currentAccess = access ?? role?.access ?? 'LISTED'

  const close = () => {
    setName(null); setDescription(null); setAccess(null)
    onClose()
  }

  const submit = async () => {
    if (!role) return
    const ok = await permissions.updateRole(role.id, {
      name: currentName, description: currentDescription, access: currentAccess,
    })
    if (ok) close()
  }

  return (
    <Modal
      open={role !== null}
      title={role ? `编辑角色：${role.name}` : ''}
      okText="保存"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={10}>
        <Input placeholder="角色名（唯一）" value={currentName} onChange={e => setName(e.target.value)} />
        <Input
          placeholder="这个角色是干什么的（可留空）"
          value={currentDescription}
          onChange={e => setDescription(e.target.value)}
        />
        <Select
          value={currentAccess}
          onChange={value => setAccess(value)}
          options={[
            { value: 'LISTED', label: '清单白名单 —— 只能调清单里那几把工具（清单为空时一把都调不到）' },
            { value: 'ALL', label: '全部工具 —— 包括将来新增的' },
          ]}
        />
        <Text type="secondary">
          工具与目录不在这里改：它们各有各的入口（角色行上的「工具」「目录」），
          因为它们是<strong>各自整体替换</strong>的两份清单 —— 顺手一起提交会让"只改个名字"也去重写它们。
        </Text>
      </Flex>
    </Modal>
  )
}

function RoleToolsModal({ role, onClose, permissions, availableTools }: {
  role: PermissionRole | null
  onClose: () => void
  permissions: UsePermissionsResult
  availableTools: string[]
}) {
  const [tools, setTools] = useState<string[] | null>(null)
  const currentTools = tools ?? role?.toolNames ?? []

  const close = () => {
    setTools(null)
    onClose()
  }

  const submit = async () => {
    if (!role) return
    const ok = await permissions.setRoleTools(role.id, currentTools)
    if (ok) close()
  }

  return (
    <Modal
      open={role !== null}
      title={role ? `角色「${role.name}」能调的工具` : ''}
      width={640}
      okText="保存"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={10}>
        <Paragraph type="secondary" className="permission-meta">
          候选就是注册表里真实存在的那些。工具名写错一个字母会被后端当场拒掉（带可用清单）——
          而那种错误的后果是"这把工具永远调不到"，且只在运行期表现为「模型选错了工具」。
          提交的是<strong>一整份</strong>清单：取消勾选就是撤掉，后端整体替换。
        </Paragraph>
        {role?.access === 'ALL' ? (
          <Alert type="info" showIcon title="这个角色是「全部工具」，工具清单对它不生效" />
        ) : (
          <Checkbox.Group
            value={currentTools}
            onChange={values => setTools(values as string[])}
            options={availableTools.map(t => ({ value: t, label: <span className="is-mono">{t}</span> }))}
          />
        )}
      </Flex>
    </Modal>
  )
}

function RoleDirectoriesModal({ role, onClose, permissions }: {
  role: PermissionRole | null
  onClose: () => void
  permissions: UsePermissionsResult
}) {
  const [dirs, setDirs] = useState<string[] | null>(null)
  const [newDir, setNewDir] = useState('')
  const currentDirs = dirs ?? role?.dirPrefixes ?? []

  const close = () => {
    setDirs(null); setNewDir('')
    onClose()
  }

  const submit = async () => {
    if (!role) return
    const ok = await permissions.setRoleDirectories(role.id, currentDirs)
    if (ok) close()
  }

  return (
    <Modal
      open={role !== null}
      title={role ? `角色「${role.name}」能在哪些目录下干活` : ''}
      width={640}
      okText="保存"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={10}>
        <Paragraph type="secondary" className="permission-meta">
          按<strong>路径分段</strong>比较，不做字符串前缀：<span className="is-mono">D:\work\demo</span> 不会命中
          <span className="is-mono">D:\work\demo-other</span>；<span className="is-mono">..</span> 也会被折叠掉，
          走不出授权范围。提交的是<strong>一整份</strong>前缀清单：移除就是撤掉，后端整体替换。
        </Paragraph>
        {/* ⚠️ antd 6 把 `direction` 改名成 `orientation`：旧名只在控制台留一条 deprecation
            警告 —— 而自检最后一条断言盯的就是控制台干净（这一处是打开目录弹窗时才暴露的） */}
        <Space orientation="vertical" className="permission-dir-list">
          {currentDirs.length === 0 && <Text type="secondary">（没有目录前缀 —— 任何目录都不被授权）</Text>}
          {currentDirs.map(prefix => (
            <Space key={prefix}>
              <Text className="is-mono">{prefix}</Text>
              <Button size="small" danger onClick={() => setDirs(currentDirs.filter(p => p !== prefix))}>移除</Button>
            </Space>
          ))}
        </Space>
        <Flex gap={8} className="permission-dir-add">
          <Input
            placeholder="目录前缀，例如 D:\work\demo"
            value={newDir}
            onChange={e => setNewDir(e.target.value)}
          />
          <Button
            disabled={!newDir.trim()}
            onClick={() => {
              const value = newDir.trim()
              if (value && !currentDirs.includes(value)) setDirs([...currentDirs, value])
              setNewDir('')
            }}
          >
            添加
          </Button>
        </Flex>
      </Flex>
    </Modal>
  )
}

function RoleUsersModal({ role, onClose, permissions, users }: {
  role: PermissionRole | null
  onClose: () => void
  permissions: UsePermissionsResult
  users: PermissionUser[]
}) {
  const [picked, setPicked] = useState<number[] | null>(null)
  // 角色名唯一，所以"谁有它"就是"谁的 roles 里有这个名字"
  const current = picked ?? (role === null
    ? []
    : users.filter(u => u.roles.includes(role.name)).map(u => u.id))

  const close = () => {
    setPicked(null)
    onClose()
  }

  const submit = async () => {
    if (!role) return
    const ok = await permissions.setRoleUsers(role.id, current)
    if (ok) close()
  }

  return (
    <Modal
      open={role !== null}
      title={role ? `角色「${role.name}」的成员` : ''}
      width={560}
      okText="保存"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={10}>
        <Text type="secondary">
          勾选的就是这个角色<strong>全部</strong>的成员（后端整体替换，不是增删）——
          取消勾选 = 那个人失去这个角色；提交的是这一整份清单。
          停用的账号也在候选里：停用期间配好的角色，重新启用之后就生效。
        </Text>
        <Checkbox.Group
          value={current}
          onChange={values => setPicked(values as number[])}
          options={users.map(u => ({
            value: u.id,
            label: u.usable ? `${u.name}（#${u.id}）` : `${u.name}（#${u.id} · ${u.statusLabel}）`,
          }))}
        />
        {current.length === 0 && (
          <Alert
            type="warning"
            showIcon
            title="一个账号都不勾 = 这个角色没有成员"
            description="它的工具白名单与目录前缀对谁都还不生效 —— 直到有账号被勾进来。"
          />
        )}
      </Flex>
    </Modal>
  )
}
