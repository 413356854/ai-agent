import { useCallback, useState } from 'react'
import type { PermissionUserOption } from '../api/permissions'

/**
 * 「模拟登录」的那个人：**登录名 / 显示名 / 用户 id**。
 *
 * <p>它只记这三样。候选里还有 `status` / `usable`，那是**搜索那一刻**的状态，
 * 存下来只会在刷新之后变成一句会过期的话（"停用"这件事每次该由后端重新说）。
 */
export interface MockLoginUser {
  /** 后续业务按人区分时用的就是它（谁发布的、谁确认的）。 */
  id: number
  name: string
  /** 没填显示名时后端给空串。 */
  displayName: string
}

/**
 * 登录态存 localStorage 的键。与主题（`ai-agent.theme`）、目录收起态同一族：
 * 它记的是"这台机器上我以谁的身份在用这一页"，刷新一次就掉回未登录会让每次改动都要重选一遍。
 */
export const MOCK_LOGIN_KEY = 'ai-agent.mock-login'

/**
 * 页头与弹窗上显示的那个名字：有显示名用显示名，没填（空串）就退回登录名。
 *
 * <p>只有这一处判据 —— 页头上那块标签与弹窗里的回显都从这里取，
 * 各写一遍 `displayName || name` 的话，两处总有一处会漏掉空串那一支。
 */
export function mockLoginLabel(user: Pick<MockLoginUser, 'name' | 'displayName'>): string {
  return user.displayName || user.name
}

/**
 * 读回存着的登录态。读不出来（没存过 / 不是 JSON / 少了字段）一律当**没登录** ——
 * 手改过的 localStorage 不该让整页崩在一个页头标签上。
 */
function readStored(): MockLoginUser | null {
  try {
    const raw = window.localStorage.getItem(MOCK_LOGIN_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<MockLoginUser>
    if (typeof parsed.id !== 'number' || typeof parsed.name !== 'string') return null
    return { id: parsed.id, name: parsed.name, displayName: parsed.displayName ?? '' }
  } catch {
    return null
  }
}

export interface UseMockLoginResult {
  /** 当前登录的人；没登录是 `null`。后续业务要的 id 在 `user.id` 上。 */
  user: MockLoginUser | null
  /** 记下这个人（值来自 `GET /api/permissions/users/search` 的候选项）。 */
  login: (option: PermissionUserOption) => void
  /** 退登：把存着的那条记录删掉。 */
  logout: () => void
}

/**
 * 页头的「模拟登录」状态：读 / 登录 / 退出。
 *
 * <h2>为什么"登录"只是页面上的一个记录</h2>
 * 权限那一族与 agent 任务那一族端点**都没有登录**（能访问端口的人就是全部身份），
 * 所以这里不送口令、也不去问后端 —— 它做的事只有一件：把选中的账号记下来，
 * 让后续要按人区分的业务（谁发布的、谁确认的）有 id 可用。
 *
 * <h2>为什么选人要走搜索接口</h2>
 * 身份在后续业务里是要落库的（`publisher` / `assignee` 都是账号表里的真人），
 * 打一个名字进去的话，错一个字不会有任何东西拦下来 —— 所以候选只能来自
 * `GET /api/permissions/users/search`，与 agent 任务那两格选人是同一个端点。
 */
export function useMockLogin(): UseMockLoginResult {
  const [user, setUser] = useState<MockLoginUser | null>(readStored)

  const login = useCallback((option: PermissionUserOption) => {
    const next: MockLoginUser = {
      id: option.id,
      name: option.name,
      displayName: option.displayName,
    }
    window.localStorage.setItem(MOCK_LOGIN_KEY, JSON.stringify(next))
    setUser(next)
  }, [])

  const logout = useCallback(() => {
    window.localStorage.removeItem(MOCK_LOGIN_KEY)
    setUser(null)
  }, [])

  return { user, login, logout }
}
