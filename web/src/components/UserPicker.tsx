import { Flex, Select, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { permissionApi } from '../api/permissions'
import type { PermissionUserOption } from '../api/permissions'

const { Text } = Typography

/**
 * 「从账号表里选一个人」—— 服务端搜索选择器（agent任务那页的两格选人、
 * 以及页头的模拟登录共用这一个）。
 *
 * <h2>为什么是搜索选择器，而不是一个能打字的输入框</h2>
 * 这两处选出来的人都会落进业务数据（任务的发布人 / 任务人，库里存的是他们的用户 id）
 * 或成为当前身份。它们只能来自账号表：写入路径上会核一遍（查不到回 400），
 * 而那一刻用户已经打完了整个名字 —— 打错的代价是重来一次，而不是当场看见候选里没有这个人。
 * 所以值只能来自 `GET /api/permissions/users/search` 给出的候选。
 *
 * <h2>为什么是服务端搜索（`filterOption={false}`）</h2>
 * 候选是整张账号表：一次全取回来在账号多时就是"为了填一个名字下载一张表"；
 * 而 antd 自带的前端过滤只过滤已经取回来的那几条 —— 那会让"搜不到"看起来像
 * "系统里没有这个人"（真相是它不在前 5 条里）。所以搜索词一变就重新问后端，
 * 条数与上限由后端定（默认 5、最多 20）。
 *
 * <h2>为什么选中的那一条要留在候选里</h2>
 * 候选列表每次搜索都会被替换，而 antd 的选中项显示的是**候选里那一条的 label**：
 * 不把它留住的话，选完再搜下一个词，之前那一格会退化成裸的登录名（看起来像没选中）。
 */
export function UserPicker({ value, onChange, placeholder }: {
  /** 选中的登录名（空串表示还没选）。 */
  value: string
  /** 第二个参数是候选里那一条 —— 调用方要 `id` / `displayName` 时从这里取。 */
  onChange: (name: string, option: PermissionUserOption | null) => void
  placeholder: string
}) {
  const [options, setOptions] = useState<PermissionUserOption[]>([])
  const [searching, setSearching] = useState(false)
  /** 搜索本身的失败（例如后端没起）要显示出来：静默的空下拉看起来像"没有这个人"。 */
  const [error, setError] = useState('')
  /** 选中的那一条（见上面第三条理由：它要一直留在候选里）。 */
  const picked = useRef<PermissionUserOption | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])

  // 外面把值清空时（提交成功之后弹窗复位）也要丢掉"选中的那一条"：
  // 留着的话，下一次点开下拉会看到上一次选的人还挂在候选里
  useEffect(() => { if (!value) picked.current = null }, [value])

  const search = (keyword: string) => {
    if (timer.current) clearTimeout(timer.current)
    const wanted = keyword.trim()
    if (!wanted) {
      setOptions(picked.current ? [picked.current] : [])
      setError('')
      return
    }
    // 防抖：每敲一个字都发一次请求的话，一次输入就是五次往返，而结果只可能用最后一次
    timer.current = setTimeout(() => {
      setSearching(true)
      permissionApi.searchUsers(wanted)
        .then(result => {
          setOptions(result.users)
          setError('')
        })
        .catch((e: unknown) => {
          setOptions([])
          setError(e instanceof Error ? e.message : String(e))
        })
        .finally(() => setSearching(false))
    }, 250)
  }

  const choose = (name: string) => {
    const option = options.find(item => item.name === name) ?? null
    picked.current = option
    onChange(name, option)
  }

  return (
    <Flex vertical gap={4} className="user-picker">
      <Select
        showSearch
        // 候选由后端按搜索词给（见组件注释第二条）：交给 antd 再过滤一遍就会把
        // "后端说匹配的那几条"又筛一次，而筛掉的正是它刚匹配上的
        filterOption={false}
        value={value || undefined}
        placeholder={placeholder}
        onSearch={search}
        onChange={choose}
        loading={searching}
        options={options.map(option => ({ value: option.name, label: userOptionLabel(option) }))}
        notFoundContent={searching ? '正在搜索…' : '没有匹配的用户 —— 换个搜索词试试'}
        style={{ width: '100%' }}
      />
      {error && <Text type="danger">搜索用户失败：{error}</Text>}
    </Flex>
  )
}

/** 候选上那一行字：显示名（登录名），停用的在后面加一个标记 —— 但它仍然可以选。 */
export function userOptionLabel(option: PermissionUserOption): string {
  const base = option.displayName ? `${option.displayName}（${option.name}）` : option.name
  return option.usable ? base : `${base} · 已停用`
}
