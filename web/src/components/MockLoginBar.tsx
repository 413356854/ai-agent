import { Button, Flex, Modal, Typography } from 'antd'
import { useState } from 'react'
import type { PermissionUserOption } from '../api/permissions'
import type { UseMockLoginResult } from '../hooks/useMockLogin'
import { mockLoginLabel } from '../hooks/useMockLogin'
import { UserPicker } from './UserPicker'

const { Text } = Typography

/**
 * 页头右上角的「模拟登录」：未登录时是一个入口按钮，登录后是「显示名 + 退出」。
 *
 * <h2>它解决的是什么</h2>
 * 这一页后面要按人区分的业务（谁发布的任务、谁确认的）需要一个当前身份，
 * 而后端**没有登录**（能访问端口的人就是全部身份）—— 这个控件就是那个身份的来源：
 * 从真实的账号表里选一个人，把他的 id 记下来给后续业务用。
 *
 * <h2>为什么与主题开关一样在所有视图上都在</h2>
 * 它说的是"现在以谁的身份在用这一页"，而不是某一页的数据 ——
 * 跟着某个视图出现或消失的话，人切一次视图就得重新确认一次自己是谁。
 *
 * <h2>为什么显示的是显示名，而记的是 id</h2>
 * 名字是给人看的（显示名没填时退回登录名，判据只有 `mockLoginLabel` 一处）；
 * id 才是后续业务用的那一份 —— 名字可以改、也可以重名，而 id 不会。
 * 所以页头那块标签上带着 `data-login-user-id`（自检直接读它，不去解析显示文字）。
 *
 * <h2>为什么退出只是"把记录删掉"</h2>
 * 没有服务端会话可退 —— 退出就是删掉 `localStorage` 里那条记录，
 * 于是刷新之后仍然是未登录（自检钉住了这一条）。
 */
export function MockLoginBar({ login }: { login: UseMockLoginResult }) {
  const [open, setOpen] = useState(false)
  /** 弹窗里选中的那一条候选（含 id / 显示名 / 是否停用）。 */
  const [picked, setPicked] = useState<PermissionUserOption | null>(null)

  const close = () => {
    setOpen(false)
    setPicked(null)
  }

  const confirm = () => {
    if (!picked) return
    login.login(picked)
    close()
  }

  const { user } = login

  return (
    <>
      <Flex align="center" gap={8} className="mock-login">
        {user ? (
          <>
            <span
              className="mock-login-chip"
              title={`${user.name} · 用户 id ${user.id}`}
              // 后续业务认的是这个 id（自检也读它，不去解析那行显示文字）
              data-login-user={user.name}
              data-login-user-id={user.id}
            >
              模拟登录：{mockLoginLabel(user)}
            </span>
            <Button size="small" className="mock-login-exit" onClick={login.logout}>退出</Button>
          </>
        ) : (
          <Button size="small" className="mock-login-entry" onClick={() => setOpen(true)}>
            模拟登录
          </Button>
        )}
      </Flex>

      <Modal
        open={open}
        title="模拟登录"
        okText="登录"
        cancelText="取消"
        className="mock-login-modal"
        // 关掉之后卸载内容：候选下拉是挂在 `<body>` 上的浮层（antd 的 Select 走 portal），
        // 弹窗仅仅隐藏时它会留在页面上 —— 卸载同时让下一次打开是一格干净的选择器
        destroyOnHidden
        // 没选人就不让登：这个记录是后续业务的身份，空着一条比没有更糟 ——
        // 它会让"没有身份"和"身份是空的"长得一样
        okButtonProps={{ disabled: !picked }}
        onOk={confirm}
        onCancel={close}
      >
        <Flex vertical gap={10}>
          <UserPicker
            value={picked?.name ?? ''}
            onChange={(_name, option) => setPicked(option)}
            placeholder="搜索登录名，从候选里选一个真实用户"
          />
          {picked ? (
            <Text type="secondary">
              将记为 <Text strong>{mockLoginLabel(picked)}</Text>（{picked.name}），
              后续业务用他的用户 id <Text code>{picked.id}</Text>。
            </Text>
          ) : (
            <Text type="secondary">
              先在上面搜一个登录名，再从候选里选一个人 —— 记下来的是账号表里的那个人，不是一个名字。
            </Text>
          )}
          {picked && !picked.usable && (
            <Text type="warning">
              这个账号已停用：可以登录，但停用的账号在后端没有任何权限（判据在后端）。
            </Text>
          )}
        </Flex>
      </Modal>
    </>
  )
}
