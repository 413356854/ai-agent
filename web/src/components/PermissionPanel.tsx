import {
  Alert, Button, Card, Checkbox, Empty, Flex, Input, Modal, Select, Space, Spin, Switch, Table, Tag, Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import type { PermissionRole, PermissionUser } from '../api/permissions'
import type { UsePermissionsResult } from '../hooks/usePermissions'

const { Text, Paragraph } = Typography

/**
 * 应用管理区 · 权限管理（用户 / 角色 / 角色能调哪些工具 / 能在哪些目录下干活）。
 *
 * <h2>这一页要回答的四个问题，四张卡各答一个</h2>
 * <ol>
 *   <li><b>有谁</b> —— 账号表（角色、启用状态、改口令）；</li>
 *   <li><b>有哪些角色、各自配了什么</b> —— 角色表（工具白名单 + 目录前缀）；</li>
 *   <li><b>这个人到底能不能干这件事</b> —— 核对卡（把解析结果摆出来）；</li>
 *   <li><b>这一层的边界在哪</b> —— 顶部那两块说明（没有登录 / 口令只存散列）。</li>
 * </ol>
 *
 * <h2>★ 三条不能省的说法（每一条都对应一种具体误读）</h2>
 * <ol>
 *   <li><b>"没有账号 = 没有权限"要写出来</b>：新建的账号一个角色都没有，
 *       界面必须显式说"它的权限是空的（<b>不是</b>默认全部）" ——
 *       读者对"新用户"的默认预期恰好相反，而按那个预期去用会以为已经配好了；</li>
 *   <li><b>"这一族端点没有登录"要写出来</b>：能访问这个端口的人可以给自己开全部工具。
 *       这句话不写，使用者会把这一页当成一道安全边界 —— 而它现在是一张配置表；</li>
 *   <li><b>"口令只存散列、响应里没有这一列"要写出来</b>：否则"怎么把口令找回来"
 *       会变成一个反复被问的问题，而答案是"找不回来，只能重设"。</li>
 * </ol>
 *
 * <h2>为什么错误信息原样显示后端那一句</h2>
 * 后端的报错本来就带够了信息（"工具「search_doc」不在注册表里……现在可用的是：[…]"）。
 * 前端再翻译一遍只会让两处文案漂移 —— 与 Prompt 面板那条"接口给了正确的话，界面要用它"同源。
 */
export function PermissionPanel({ permissions }: { permissions: UsePermissionsResult }) {
  const { snapshot } = permissions
  const data = snapshot.status === 'ok' ? snapshot.data : null

  const [userModal, setUserModal] = useState(false)
  const [roleModal, setRoleModal] = useState(false)
  const [roleTarget, setRoleTarget] = useState<PermissionRole | null>(null)
  const [rolesOf, setRolesOf] = useState<PermissionUser | null>(null)
  const [passwordOf, setPasswordOf] = useState<PermissionUser | null>(null)

  const users = data?.users ?? []
  const roles = data?.roles ?? []

  const userColumns: ColumnsType<PermissionUser> = [
    {
      title: '账号',
      key: 'account',
      width: 220,
      render: (_, row) => (
        <Flex vertical gap={2}>
          <Space size={6}>
            <Text strong className="permission-user-name">{row.name}</Text>
            {!row.usable && <Tag color="red">{row.statusLabel}</Tag>}
          </Space>
          {/* 显示名为空是**没填**，不是"名字是空的" —— 与 Prompt 面板的（未署名）同一条规矩 */}
          <Text type="secondary" className="permission-meta">
            {row.displayName || '（未填显示名）'}
          </Text>
        </Flex>
      ),
    },
    {
      title: '角色（权限 = 这些角色的并集）',
      key: 'roles',
      render: (_, row) => (row.roles.length === 0
        ? <Tag color="orange">没有角色 → 没有任何权限</Tag>
        : <Space size={4} wrap>{row.roles.map(name => <Tag key={name} color="blue">{name}</Tag>)}</Space>),
    },
    {
      title: '状态',
      key: 'status',
      width: 120,
      render: (_, row) => (
        <Switch
          size="small"
          checked={row.usable}
          disabled={permissions.busy}
          onChange={checked => void permissions.updateUser(row.id, { displayName: row.displayName, enabled: checked })}
          checkedChildren="启用"
          unCheckedChildren="停用"
        />
      ),
    },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      render: (_, row) => (
        <Space size={4} wrap>
          <Button size="small" onClick={() => setRolesOf(row)}>角色</Button>
          <Button size="small" onClick={() => setPasswordOf(row)}>改口令</Button>
        </Space>
      ),
    },
  ]

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
      width: 260,
      render: (_, row) => (
        <Space size={4} wrap>
          <Button size="small" onClick={() => setRoleTarget(row)}>工具</Button>
          <Button size="small" onClick={() => setRoleTarget(row)}>目录</Button>
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
      {/* ---------------- 这一层的边界：没有登录、口令只存散列 ---------------- */}
      <Alert
        type="warning"
        showIcon
        title="这一层现在没有登录：/api/permissions/** 是匿名的"
        description={<div className="diag-note">
          <p>
            能访问这个端口的人<strong>可以给自己开「全部工具」</strong>。当前它只绑 localhost 供本地开发用，
            所以默认开着；一旦要放到别人能访问的地方，先把
            <Text code>ai.agent.permission.management-enabled</Text> 改成 <Text code>false</Text>（整族端点 404），
            再接登录链路。
          </p>
          <p>{data?.passwordNote}</p>
          <p className="is-mono">{data?.bootstrapNote}</p>
        </div>}
      />

      {/* ---------------- 上一次操作的反馈（原样显示后端那句话） ---------------- */}
      {permissions.feedback.status === 'ok' && (
        <Alert type="success" showIcon closable title={permissions.feedback.data.message}
          onClose={permissions.clearFeedback} />
      )}
      {permissions.feedback.status === 'error' && (
        <Alert type="error" showIcon closable title={permissions.feedback.error}
          onClose={permissions.clearFeedback} />
      )}

      {/* ---------------- 核对：这个人能不能干这件事 ---------------- */}
      <VerifyCard permissions={permissions} users={users} tools={data?.availableTools ?? []} />

      {/* ---------------- 账号 ---------------- */}
      <Card
        size="small"
        className="diag-card diag-card-wide"
        title={<Flex gap={8} align="center" wrap>
          <span>账号（{users.length}）</span>
          <Tag color="geekblue">权限 = 它所有角色的并集</Tag>
        </Flex>}
        extra={<Space>
          <Button onClick={permissions.reload} disabled={permissions.busy}>刷新</Button>
          <Button type="primary" onClick={() => setUserModal(true)}>新建账号</Button>
        </Space>}
      >
        {users.length === 0
          ? <Empty description="还没有账号。库里一个账号都没有时，启动会建一个初始管理员（见上面的启动说明）。" />
          : <Table
              rowKey="id"
              size="small"
              pagination={false}
              columns={userColumns}
              dataSource={users}
            />}
      </Card>

      {/* ---------------- 角色 ---------------- */}
      <Card
        size="small"
        className="diag-card diag-card-wide"
        title={<Flex gap={8} align="center" wrap>
          <span>角色（{roles.length}）</span>
          <Tag color="geekblue">工具白名单 + 目录前缀</Tag>
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

      <NewUserModal open={userModal} onClose={() => setUserModal(false)} permissions={permissions} />
      <NewRoleModal open={roleModal} onClose={() => setRoleModal(false)} permissions={permissions} />
      <RolePermissionsModal
        role={roleTarget}
        onClose={() => setRoleTarget(null)}
        permissions={permissions}
        availableTools={data?.availableTools ?? []}
      />
      <UserRolesModal
        user={rolesOf}
        onClose={() => setRolesOf(null)}
        permissions={permissions}
        roles={roles}
      />
      <PasswordModal user={passwordOf} onClose={() => setPasswordOf(null)} permissions={permissions} />
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

function NewUserModal({ open, onClose, permissions }: {
  open: boolean
  onClose: () => void
  permissions: UsePermissionsResult
}) {
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [enabled, setEnabled] = useState(true)

  const submit = async () => {
    const ok = await permissions.createUser({ name, password, displayName, enabled })
    // 成功才关：失败时关掉会把用户填的内容丢掉，而他正需要照着报错改一改
    if (ok) {
      setName(''); setPassword(''); setDisplayName(''); setEnabled(true)
      onClose()
    }
  }

  return (
    <Modal
      open={open}
      title="新建账号"
      okText="创建"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={onClose}
    >
      <Flex vertical gap={10}>
        <Input placeholder="登录名（唯一）" value={name} onChange={e => setName(e.target.value)} />
        <Input.Password
          placeholder="初始口令（必填）—— 只以散列保存，之后找不回来，只能重设"
          value={password}
          onChange={e => setPassword(e.target.value)}
        />
        <Input placeholder="显示名（可留空）" value={displayName} onChange={e => setDisplayName(e.target.value)} />
        <Space>
          <Switch checked={enabled} onChange={setEnabled} size="small" />
          <Text>启用</Text>
        </Space>
        <Text type="secondary">
          新建的账号<strong>一个角色都没有</strong>，因此没有任何权限 —— 建完还要在「角色」那一栏给它挂上角色。
        </Text>
      </Flex>
    </Modal>
  )
}

function NewRoleModal({ open, onClose, permissions }: {
  open: boolean
  onClose: () => void
  permissions: UsePermissionsResult
}) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [access, setAccess] = useState<'LISTED' | 'ALL'>('LISTED')

  const submit = async () => {
    const ok = await permissions.createRole({ name, description, access })
    if (ok) {
      setName(''); setDescription(''); setAccess('LISTED')
      onClose()
    }
  }

  return (
    <Modal
      open={open}
      title="新建角色"
      okText="创建"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={onClose}
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

function RolePermissionsModal({ role, onClose, permissions, availableTools }: {
  role: PermissionRole | null
  onClose: () => void
  permissions: UsePermissionsResult
  availableTools: string[]
}) {
  const [tools, setTools] = useState<string[] | null>(null)
  const [dirs, setDirs] = useState<string[] | null>(null)
  const [newDir, setNewDir] = useState('')

  // 打开时把当前值灌进草稿（role 变了就重灌）。
  // 【为什么用 key 而不是 useEffect】见下面 <Modal> 的 key：每次打开都是新实例，
  // 于是"草稿"天然不会留到下一次
  const currentTools = tools ?? role?.toolNames ?? []
  const currentDirs = dirs ?? role?.dirPrefixes ?? []

  const submit = async () => {
    if (!role) return
    // 两个写端点分别提交：工具与目录是两件事，合成一次提交会让"只改目录"也去重写工具清单
    const okTools = await permissions.setRoleTools(role.id, currentTools)
    const okDirs = await permissions.setRoleDirectories(role.id, currentDirs)
    if (okTools && okDirs) {
      setTools(null); setDirs(null); setNewDir('')
      onClose()
    }
  }

  return (
    <Modal
      key={role?.id ?? 'none'}
      open={role !== null}
      title={role ? `角色「${role.name}」的工具与目录` : ''}
      width={640}
      okText="保存"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={onClose}
    >
      <Flex vertical gap={14}>
        <div>
          <Text strong>能调的工具</Text>
          <Paragraph type="secondary" className="permission-meta">
            候选就是注册表里真实存在的那些。工具名写错一个字母会被后端当场拒掉（带可用清单）——
            而那种错误的后果是"这把工具永远调不到"，且只在运行期表现为「模型选错了工具」。
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
        </div>

        <div>
          <Text strong>能在哪些目录下干活</Text>
          <Paragraph type="secondary" className="permission-meta">
            按<strong>路径分段</strong>比较，不做字符串前缀：<span className="is-mono">D:\work\demo</span> 不会命中
            <span className="is-mono">D:\work\demo-other</span>；<span className="is-mono">..</span> 也会被折叠掉，
            走不出授权范围。
          </Paragraph>
          <Space direction="vertical" className="permission-dir-list">
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
        </div>
      </Flex>
    </Modal>
  )
}

function UserRolesModal({ user, onClose, permissions, roles }: {
  user: PermissionUser | null
  onClose: () => void
  permissions: UsePermissionsResult
  roles: PermissionRole[]
}) {
  const [picked, setPicked] = useState<number[] | null>(null)
  const byName = new Map(roles.map(r => [r.name, r.id]))
  const current = picked ?? (user?.roles.map(name => byName.get(name)).filter((id): id is number => id !== undefined) ?? [])

  const submit = async () => {
    if (!user) return
    const ok = await permissions.setUserRoles(user.id, current)
    if (ok) {
      setPicked(null)
      onClose()
    }
  }

  return (
    <Modal
      key={user?.id ?? 'none'}
      open={user !== null}
      title={user ? `账号「${user.name}」的角色` : ''}
      okText="保存"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={onClose}
    >
      <Flex vertical gap={10}>
        <Text type="secondary">
          提交的是<strong>一整份</strong>角色清单（后端整体替换，不是增删）——
          逐条增删要求前端算 diff，而 diff 少算一条的形态是"没撤掉一个授权"，页面上看不出来。
        </Text>
        <Checkbox.Group
          value={current}
          onChange={values => setPicked(values as number[])}
          options={roles.map(r => ({ value: r.id, label: r.name }))}
        />
        {current.length === 0 && (
          <Alert type="warning" showIcon title="一个角色都不勾 = 这个账号没有任何权限" />
        )}
      </Flex>
    </Modal>
  )
}

function PasswordModal({ user, onClose, permissions }: {
  user: PermissionUser | null
  onClose: () => void
  permissions: UsePermissionsResult
}) {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const mismatch = confirm.length > 0 && password !== confirm

  const submit = async () => {
    if (!user || !password || mismatch) return
    const ok = await permissions.changePassword(user.id, password)
    if (ok) {
      setPassword(''); setConfirm('')
      onClose()
    }
  }

  return (
    <Modal
      key={user?.id ?? 'none'}
      open={user !== null}
      title={user ? `改口令：${user.name}` : ''}
      okText="保存"
      cancelText="取消"
      okButtonProps={{ disabled: !password || mismatch }}
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={onClose}
    >
      <Flex vertical gap={10}>
        <Alert
          type="info"
          showIcon
          title="口令只以散列保存，读不回来"
          description="这一列存的是 PBKDF2 的散列，接口响应里也根本没有这个字段 —— 所以这里只有「重设」，没有「查看」。"
        />
        <Input.Password placeholder="新口令" value={password} onChange={e => setPassword(e.target.value)} />
        <Input.Password placeholder="再输一遍" value={confirm} onChange={e => setConfirm(e.target.value)} />
        {mismatch && <Text type="danger">两次输入不一致</Text>}
      </Flex>
    </Modal>
  )
}
