import {
  Alert, Button, Card, Empty, Flex, Input, Modal, Space, Spin, Switch, Table, Tag, Typography,
} from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useState } from 'react'
import type { PermissionUser } from '../api/permissions'
import type { UsePermissionsResult } from '../hooks/usePermissions'
import { PermissionFeedback } from './PermissionNotice'

const { Text } = Typography

/**
 * 应用管理区 · 用户管理（对接 `agent_user` 表：添加 / 修改 / 改口令）。
 *
 * <h2>这一页答两个问题</h2>
 * <ol>
 *   <li><b>有谁</b> —— 账号表（显示名、启停状态、他属于哪些角色）；</li>
 *   <li><b>怎么加一个 / 改一个</b> —— 新建账号、编辑弹窗、改口令。</li>
 * </ol>
 *
 * <h2>★ 两条不能省的说法（每条对应一种具体误读）</h2>
 * <ol>
 *   <li><b>"新建的账号一个角色都没有 = 没有任何权限"要说出来</b>：读者对"新用户"的
 *       默认预期恰好相反（以为默认给点什么），按那个预期去用会以为已经配好了；</li>
 *   <li><b>"口令只存散列、响应里没有这一列"要说出来</b>：否则"怎么把口令找回来"
 *       会变成一个反复被问的问题，而答案是"找不回来，只能重设"。</li>
 * </ol>
 *
 * <h2>为什么没有"删除账号"，只有启用/停用开关</h2>
 * 后端的账号表刻意不提供删除（见 `AccountMapper` 的类注释）：删掉一行会让它名下的
 * 授权关系被外键级联带走，于是"这个人当时是什么权限"这个复盘问题永远没有答案。
 * 停用即等价于"让他不能再用"——停用的账号在解析期一律没有权限。
 *
 * <h2>为什么角色那一列是只读的</h2>
 * 账号↔角色关系的编辑只在「权限管理」那一页（角色记录上的「成员」）——
 * 同一份关系有两个界面入口就是两份判据，而它们不一致时页面上看不出来。
 * 这一列回答的是"他现在是谁"，改他去那边改。
 */
export function UserPanel({ permissions }: { permissions: UsePermissionsResult }) {
  const { snapshot } = permissions
  const data = snapshot.status === 'ok' ? snapshot.data : null

  const [userModal, setUserModal] = useState(false)
  const [editing, setEditing] = useState<PermissionUser | null>(null)
  const [passwordOf, setPasswordOf] = useState<PermissionUser | null>(null)

  const users = data?.users ?? []

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
      title: '角色（权限 = 这些角色的并集；编辑在「权限管理」里）',
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
      width: 200,
      render: (_, row) => (
        <Space size={4} wrap>
          <Button size="small" onClick={() => setEditing(row)}>编辑</Button>
          <Button size="small" onClick={() => setPasswordOf(row)}>改口令</Button>
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
          ? <Empty description="还没有账号。库里一个账号都没有时，启动会建一个初始管理员。" />
          : <Table
              rowKey="id"
              size="small"
              pagination={false}
              columns={userColumns}
              dataSource={users}
            />}
      </Card>

      <NewUserModal open={userModal} onClose={() => setUserModal(false)} permissions={permissions} />
      <EditUserModal user={editing} onClose={() => setEditing(null)} permissions={permissions} />
      <PasswordModal user={passwordOf} onClose={() => setPasswordOf(null)} permissions={permissions} />
    </div>
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

  const reset = () => {
    setName(''); setPassword(''); setDisplayName(''); setEnabled(true)
  }
  const close = () => { reset(); onClose() }

  const submit = async () => {
    const ok = await permissions.createUser({ name, password, displayName, enabled })
    // 成功才关：失败时关掉会把用户填的内容丢掉，而他正需要照着报错改一改
    if (ok) close()
  }

  return (
    <Modal
      open={open}
      title="新建账号"
      okText="创建"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
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
          新建的账号<strong>一个角色都没有</strong>，因此没有任何权限 —— 建完还要在「权限管理」那一页
          找个角色，把它加进那个角色的成员里。
        </Text>
      </Flex>
    </Modal>
  )
}

function EditUserModal({ user, onClose, permissions }: {
  user: PermissionUser | null
  onClose: () => void
  permissions: UsePermissionsResult
}) {
  // 草稿：null = "还没动过"，显示的是这个账号当前的值。
  // 关弹窗（无论保存还是取消）都清回 null —— 于是下次打开读的是新状态，而不是上一次的草稿
  const [displayName, setDisplayName] = useState<string | null>(null)
  const [enabled, setEnabled] = useState<boolean | null>(null)

  const currentName = displayName ?? user?.displayName ?? ''
  const currentEnabled = enabled ?? user?.usable ?? false

  const close = () => {
    setDisplayName(null); setEnabled(null)
    onClose()
  }

  const submit = async () => {
    if (!user) return
    const ok = await permissions.updateUser(user.id, { displayName: currentName, enabled: currentEnabled })
    if (ok) close()
  }

  return (
    <Modal
      open={user !== null}
      title={user ? `编辑账号：${user.name}` : ''}
      okText="保存"
      cancelText="取消"
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={10}>
        <Input
          placeholder="显示名（可留空）"
          value={currentName}
          onChange={e => setDisplayName(e.target.value)}
        />
        <Space>
          <Switch checked={currentEnabled} onChange={setEnabled} size="small" />
          <Text>{currentEnabled ? '启用' : '停用'}</Text>
        </Space>
        <Text type="secondary">
          <strong>停用</strong>是这个界面上"删除账号"的等价物：停用的账号一律没有权限，
          而它与它角色的关系留在库里 —— 重新启用就恢复。账号表刻意不做真删除（见 `AccountMapper` 的注释）。
        </Text>
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

  // 取消也清空：口令留在输入框里（下次打开还在）没有理由
  const close = () => {
    setPassword(''); setConfirm('')
    onClose()
  }

  const submit = async () => {
    if (!user || !password || mismatch) return
    const ok = await permissions.changePassword(user.id, password)
    if (ok) close()
  }

  return (
    <Modal
      open={user !== null}
      title={user ? `改口令：${user.name}` : ''}
      okText="保存"
      cancelText="取消"
      okButtonProps={{ disabled: !password || mismatch }}
      confirmLoading={permissions.busy}
      onOk={() => void submit()}
      onCancel={close}
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
