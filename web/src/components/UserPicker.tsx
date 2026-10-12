import { Flex, Select, Typography } from 'antd'
import { useEffect, useRef, useState } from 'react'
import { permissionApi } from '../api/permissions'
import type { PermissionUserOption } from '../api/permissions'

const { Text } = Typography

/**
 * 「从账号表里选一个人」—— 服务端搜索选择器（定时任务那页的任务人、
 * 发布任务那页的两格选人、以及页头的模拟登录共用这一个）。
 *
 * <h2>值是什么：用户 id</h2>
 * 选出来的人会落进业务数据（任务的发布人 / 任务人、定时规则的任务人），
 * 而库里存的是 **`agent_user.id`** —— 所以这一格交出的是 `option.id`，
 * 姓名只出现在候选那一行字上（{@link userOptionLabel}）。
 * 传一个姓名上去的写法要靠后端再查一次账号表，而"这个名字指的是谁"因此在两处各判一次。
 *
 * <h2>为什么是搜索选择器，而不是一个能打字的输入框</h2>
 * 候选只能来自 `GET /api/permissions/users/search`：那两格要的是真实账号，
 * 而写入路径上会核一遍（查不到回 400）。让用户打字的话，打错的代价是
 * 提交之后才被拒 —— 而那一刻他已经打完了整个名字。
 *
 * <h2>为什么是服务端搜索（`filterOption={false}`）</h2>
 * 候选是整张账号表：一次全取回来在账号多时就是"为了填一个名字下载一张表"；
 * 而 antd 自带的前端过滤只过滤已经取回来的那几条 —— 那会让"搜不到"看起来像
 * "系统里没有这个人"（真相是它不在前 5 条里）。所以搜索词一变就重新问后端，
 * 条数与上限由后端定（默认 5、最多 20）。
 *
 * <h2>为什么选中的那一条要留在候选里</h2>
 * 候选列表每次搜索都会被替换，而 antd 的选中项显示的是**候选里那一条的 label**：
 * 候选里没有它时，那一格退化成显示 value 本身（裸的 id / 裸的登录名，看起来像没选中）。
 * ⚠️ 每次选完之后 antd 还会调一次 `onSearch('')`（它把搜索词清掉了），那一次同样会把候选清空 ——
 * 所以"选中的那一条"与候选是**同一个 state** 里的东西，不能分成两份。
 * <p>打开"改一条规则"时当前那个人**不在**候选里（没有搜过），所以调用方用
 * {@link initial} 把他交进来 —— 那一格的初值就是"这条规则现在的任务人"。
 */
export function UserPicker({ value, initial, onChange, placeholder }: {
  /**
   * 已经选中的那一条的 `Select` 值：**用户 id**（`option.id`），`null` 表示还没选。
   *
   * <p>类型放开成 `number | string` 是为了与 `options` 里那个 `value`（`option.id`
   * 或 `option.name`，见调用点）同型 —— 混着两种写法时 antd 认不出选中项，
   * 表现是"选完那一格还是空的"。新写的调用点一律给 id。
   */
  value: number | string | null
  /**
   * 打开时那一格上已有的人（改一条记录时用）。
   *
   * <p>没有它的话，`value` 有值而候选里没有对应那一条 → 选择器显示的是裸的值。
   * 它只影响**显示**：交出来的仍然只有候选里那一条。
   */
  initial?: PermissionUserOption | null
  /** 参数是候选里那一条（含 id / 登录名 / 显示名 / 是否停用）。 */
  onChange: (option: PermissionUserOption | null) => void
  placeholder: string
}) {
  /**
   * 选中的那一条，**并且它一定在 `options` 里**。
   *
   * <p>⚠️ 这一条是这个组件的关键：`Select` 显示的是**当前 `options` 里那个 `value` 对应的 label**，
   * `options` 里没有它时它退化成显示 value 本身 —— 于是格子上出现的是裸的登录名（或裸的 id），
   * 看起来像没选中。而每次选完之后 antd 会调一次 `onSearch('')`（它把搜索词清掉了），
   * 那一次会把候选清空 —— 所以"选中的那一条"必须与候选一起放进同一个 state 里，
   * 分成两份（一份 state + 一份 ref）时，清候选那一次渲染就把 label 丢了。
   */
  const [options, setOptions] = useState<PermissionUserOption[]>(initial ? [initial] : [])
  const [searching, setSearching] = useState(false)
  /** 搜索本身的失败（例如后端没起）要显示出来：静默的空下拉看起来像"没有这个人"。 */
  const [error, setError] = useState('')
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => () => { if (timer.current) clearTimeout(timer.current) }, [])

  // 初值换了人（打开另一条规则）就跟上：把候选重置成"就他一个"。
  // 依赖的是 initial 的 id 而不是它这份对象 —— 调用方每次渲染都新建一个对象时，
  // 用对象当依赖会让这一格在打字时被反复重置
  const initialId = initial?.id ?? null
  useEffect(() => { setOptions(initial ? [initial] : []) }, [initialId]) // eslint-disable-line react-hooks/exhaustive-deps

  // 外面把值清空时（提交成功之后弹窗复位）也要丢掉"选中的那一条"：
  // 留着的话，下一次点开下拉会看到上一次选的人还挂在候选里
  useEffect(() => { if (value == null) setOptions([]) }, [value])

  const search = (keyword: string) => {
    if (timer.current) clearTimeout(timer.current)
    const wanted = keyword.trim()
    if (!wanted) {
      // 空搜索词不发请求（后端也会回 400）：把候选收回"选中的那一条"
      setOptions(initial ? [initial] : [])
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

  const choose = (value: number | string) => {
    const option = options.find(item => item.id === value || item.name === value) ?? null
    // 选中的那一条留在候选最前（见上面那段：不留的话这一格会退化成裸的值）
    setOptions(option ? [option, ...options.filter(item => item.id !== option.id)] : [])
    onChange(option)
  }

  return (
    <Flex vertical gap={4} className="user-picker">
      <Select
        showSearch
        // 候选由后端按搜索词给（见组件注释第二条）：交给 antd 再过滤一遍就会把
        // "后端说匹配的那几条"又筛一次，而筛掉的正是它刚匹配上的
        filterOption={false}
        value={value ?? undefined}
        placeholder={placeholder}
        onSearch={search}
        onChange={choose}
        loading={searching}
        options={options.map(option => ({ value: option.id, label: userOptionLabel(option) }))}
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
