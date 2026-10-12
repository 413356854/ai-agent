import { Alert, Button, Card, Empty, Flex, Input, InputNumber, Modal, Radio, Select, Space, Spin, Switch, Tag, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { AgentTaskSchedule } from '../api/schedules'
import { CRON_FIELDS, DEFAULT_DRAFT, WEEKDAYS, buildCron, describeDraft, draftOf } from '../cron'
import type { ScheduleDraft } from '../cron'
import type { ScheduleForm, UseSchedulesResult } from '../hooks/useSchedules'
import { Clamped } from './Clamped'
import { UserPicker } from './UserPicker'

const { Text, Paragraph } = Typography

/**
 * 应用管理区 · 定时任务（对接 `agent_task_schedule` 表）。
 *
 * <h2>这一页是什么：一张"到点会派活"的规则表</h2>
 * 每条规则四件事：**定时正则 / 发布人 / 任务人 / 任务描述**。到点它做两件事：
 * ① 把描述里的**周期说法**（"昨天""上个月""本周"）交给模型解析成那一刻的具体日期；
 * ② 按解析后的那句生成一条 agent 任务记录（走的是与人点「发布任务」同一个入口）。
 * 那条任务接下来在 agent任务 那一页按它自己的状态链走。
 * <p>所以**描述那一格是"模板"而不是"最终那句话"** —— 界面上必须说清这一点：
 * 用户写"把昨天的失败运行汇总成晨报"是对的写法，而库里那条任务上会是
 * "把 2026-10-11 的失败运行汇总成晨报"。解析失败时**这一次不派活**（留到下一个周期重试），
 * 因为拿模板原文派出去会让 Agent 去猜一个日子，而猜错是干错事、不是晚一天。
 *
 * <h2>★ 五条不能省的说法</h2>
 * <ol>
 *   <li><b>「启用」与「在池子里」是两件事</b>：前者是库里那一列，后者是"它此刻在不在调度线程池里"。
 *       启用但正则被手工改坏了时会「启用」为真、`scheduled` 为假 —— 而那正是
 *       "规则配好了它就是不动"的答案。两格都要画出来，并且分开说；</li>
 *   <li><b>「下一次触发」必须显示</b>：它是唯一能让人确认"我配的这句到底对不对"的证据。
 *       只看正则本身，`0 0 9 * * MON-FRI` 与 `0 0 9 * * *` 的区别要读的人自己推；</li>
 *   <li><b>停用的规则要说出"它不会跑"</b>：`nextRuns` 为空不是"算不出来"，
 *       而是"它不在池子里" —— 两种原因在界面上长得一样，而该做的事完全不同
 *       （一个是去启用，一个是去改正则）；</li>
 *   <li><b>改正则 = 删旧线程重加这件事要说出来</b>：它是这一页的行为承诺
 *       （后端固定这么做，见 `AgentTaskScheduleService` 的类注释），
 *       不说的话"改完下一次按哪个算"只能靠猜；</li>
 *   <li><b>「已经派过任务的规则不能删」是一个要写在前面的限制</b>：那些任务还留在
 *       agent任务 那一页，而这条规则是它们唯一的由来。删不掉时后端会给一句完整的话，
 *       但用户最好是**点之前**就知道该用停用。</li>
 * </ol>
 *
 * <h2>★ "页面配置化定时规则"就是那个弹窗</h2>
 * 用户不写正则：选「每隔 / 每天 / 每周 / 每月」，填几个数，弹窗右上角**实时**显示
 * "拼出来的表达式 + 中文说法 + 接下来 5 次触发时刻"。三样里最后一样是后端算的
 * （`POST …/preview`）—— 前端只负责把选择拼成 6 段表达式（`web/src/cron.ts`），
 * 而"这句能不能用、下次什么时候跑"只有后端一个答案（线程池装线程用的是同一份判断）。
 *
 * <p>手写那一档（`custom`）保留着：五种形态覆盖不了的东西（`MON-FRI`、`L`、区间步长）
 * 总得有个入口，而它旁边照样有后端给的预览兜着。
 *
 * <h2>为什么按钮的可用性由这里算，而"这条规则现在长什么样"由后端算</h2>
 * 前者是"表单填完了没有"（纯界面判断），后者是业务状态（`enabled` / `scheduled` /
 * `nextRuns` / `runs`）。业务状态一律用后端给的那一份 —— 前端再推一遍
 * 就是第二份判据，而两份不一致时表现为"按钮看着能点、点完报错"。
 */
export function SchedulePanel({ schedules, loginName }: {
  schedules: UseSchedulesResult
  /** 当前登录那个人的显示名（`mockLoginLabel` 算出来的那一份）；没登录是 `null`。 */
  loginName: string | null
}) {
  const { snapshot } = schedules
  const data = snapshot.status === 'ok' ? snapshot.data : null
  const [editing, setEditing] = useState<AgentTaskSchedule | null>(null)
  const [creating, setCreating] = useState(false)

  if (snapshot.status === 'loading' || snapshot.status === 'idle') {
    // antd 6 里 Spin 的提示文案叫 description（写成旧名 tip 只在控制台留一条警告）
    return <div className="diag-panel"><Spin description="正在读定时任务规则…" /></div>
  }
  if (snapshot.status === 'error') {
    return (
      <div className="diag-panel">
        <Alert
          type="error"
          showIcon
          title="读定时任务规则失败"
          description={<div className="diag-note">
            <p>{snapshot.error}</p>
            <p>这一族端点在 <Text code>/api/agent-task-schedules</Text>，后端没起或端口不同时都会走到这里。</p>
          </div>}
        />
      </div>
    )
  }

  const rules = data?.rules ?? []

  return (
    <div className="diag-panel schedule-panel">
      <ScheduleFeedback schedules={schedules} />

      <Card
        size="small"
        className="diag-card diag-card-wide"
        title={<Flex gap={8} align="center" wrap>
          <span>定时任务（{data?.total ?? 0} 条规则）</span>
          <Tag color="geekblue">到点只做一件事：按规则生成一条 agent 任务</Tag>
        </Flex>}
        extra={<Space>
          <Button onClick={schedules.reload} disabled={schedules.busy}>刷新</Button>
          <Button type="primary" disabled={!loginName} onClick={() => setCreating(true)}>配一条规则</Button>
        </Space>}
      >
        {/* 池子那一栏是**必须显示的**：这一页的失败形态是"规则配好了、它就是不动"，
            而那句话有两个完全不同的原因 —— 规则不在池子里，与在池子里但还没到点。
            不把池子摆出来，这两件事在界面上长得一样 */}
        <div className="schedule-pool" data-pool-scheduled={data?.pool.scheduled ?? 0}
          data-pool-size={data?.pool.poolSize ?? 0}>
          <Text type="secondary">
            调度线程池：<strong>{data?.pool.scheduled ?? 0}</strong> 条规则挂着线程
            （上限 {data?.pool.poolSize ?? 0} —— 同一瞬间最多几条规则能真触发），
            当前线程 {data?.pool.poolSizeNow ?? 0}（按需建，用到了才建）、
            正在跑 {data?.pool.activeThreads ?? 0}；
            改规则会取消旧线程，所以队列里可能还排着 {data?.pool.cancelledPending ?? 0} 个待清理的节点。
          </Text>
        </div>
        <div className="task-board-scope">
          {loginName == null
            ? '未登录：列表是全部规则，但配新规则需要一个"发布人"—— 先在页头「模拟登录」里选一个人。'
            : `配新规则的发布人会是「${loginName}」（每次触发派出的任务以他的名义发布）—— 这一页按人不筛：`
              + '一条别人配的规则照样会在半夜派活，所以列表永远是全量。'}
        </div>

        {rules.length === 0
          ? <Empty description="还没有定时规则。点「配一条规则」配一条：选执行频率、选任务人、写清要做什么，
              它会按你配的频率自动派出 agent 任务。" />
          : (
            <div className="schedule-list">
              {rules.map(rule => (
                <RuleCard
                  key={rule.id}
                  rule={rule}
                  busy={schedules.busy}
                  onEdit={() => setEditing(rule)}
                  onToggle={() => void schedules.setEnabled(rule.id, !rule.enabled)}
                  onTrigger={() => void schedules.trigger(rule.id)}
                  onRemove={() => void schedules.remove(rule.id)}
                />
              ))}
            </div>
          )}
      </Card>

      <RuleModal
        open={creating || editing != null}
        rule={editing}
        schedules={schedules}
        publisherName={loginName}
        onClose={() => { setCreating(false); setEditing(null); schedules.clearPreview() }}
      />
    </div>
  )
}

/** 写操作的反馈（原样显示后端那句话，与权限两页 / agent任务同一条规矩）。 */
function ScheduleFeedback({ schedules }: { schedules: UseSchedulesResult }) {
  return (
    <>
      {schedules.feedback.status === 'ok' && (
        <Alert type="success" showIcon closable title={schedules.feedback.data.message}
          onClose={schedules.clearFeedback} />
      )}
      {schedules.feedback.status === 'error' && (
        <Alert type="error" showIcon closable title={schedules.feedback.error}
          onClose={schedules.clearFeedback} />
      )}
    </>
  )
}

/* ------------------------------------------------------------------ */
/*  一条规则                                                            */
/* ------------------------------------------------------------------ */

/**
 * 一条规则一行。
 *
 * <h2>四个动作各自的判据</h2>
 * `trigger`（现在就跑一次）**停用也能点** —— 那正是"先配好、确认一次再启用"的用法；
 * `enabled` 是幂等开关；`remove` 在"已经派过任务"时会被后端拒绝（400），
 * 所以按钮不禁用（禁用了就说不出原因），而是让它回一句完整的话。
 */
function RuleCard({ rule, busy, onEdit, onToggle, onTrigger, onRemove }: {
  rule: AgentTaskSchedule
  busy: boolean
  onEdit: () => void
  onToggle: () => void
  onTrigger: () => void
  onRemove: () => void
}) {
  const [overflow, setOverflow] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const open = overflow && expanded
  /**
   * 「启用了，但它现在不在调度线程池里」—— 这一格是这一页最容易被忽略的失败。
   * 判据是后端给的两格一起看（`enabled` 与 `scheduled`），**不在这里推**。
   */
  const notScheduled = rule.enabled && !rule.scheduled
  const neverRan = rule.runs === 0

  return (
    <div className={`schedule-card is-${rule.enabled ? 'on' : 'off'}`} data-schedule-id={rule.id}
      data-enabled={rule.enabled ? '1' : '0'} data-scheduled={rule.scheduled ? '1' : '0'}>
      <div className="schedule-card-head">
        <Tag color={rule.enabled ? (notScheduled ? 'red' : 'green') : 'default'}>
          {rule.enabled ? (notScheduled ? '启用（未挂上线程）' : '启用') : '停用'}
        </Tag>
        <Text strong className="schedule-cron" data-cron={rule.cron}>{rule.cron}</Text>
        <Text type="secondary" className="schedule-cron-desc">{rule.cronDescription}</Text>
        <span className="schedule-card-spacer" />
        <Space size={4}>
          <Button size="small" disabled={busy} onClick={onTrigger}>现在跑一次</Button>
          <Button size="small" disabled={busy} onClick={onEdit}>改</Button>
          <Button size="small" disabled={busy} onClick={onToggle}>{rule.enabled ? '停用' : '启用'}</Button>
          <Button size="small" danger disabled={busy} onClick={onRemove}>删</Button>
        </Space>
      </div>

      <div className="schedule-card-body">
        <span className="schedule-card-text">
          <Clamped text={rule.description} className="schedule-card-desc" maxHeight={60}
            open={open} onOverflow={(_region, more) => setOverflow(more)} />
        </span>
        {overflow && (
          <button type="button" className="task-card-toggle" onClick={() => setExpanded(!expanded)}>
            {expanded ? '收起' : '展开全文'}
          </button>
        )}
      </div>

      <div className="schedule-card-meta">
        {/* 显示的是**姓名**那一份（后端 JOIN 出来的），而改规则时回传的是 `assigneeName`（登录名）——
            两者的区别写在 api/schedules.ts 上 */}
        <span>{rule.publisher} → {rule.assignee || '（未指派任务人）'}</span>
        {/* 下一次触发时刻来自后端（同一份判断喂给线程池），前端不自己算 —— 算两遍就会
            "预览说 09:00、实际 10:00 跑" */}
        {rule.nextRuns.length > 0
          ? <span className="schedule-next" data-next={rule.nextRuns[0]}>下一次触发 {rule.nextRuns[0]}</span>
          : <span className="schedule-next is-none" data-next="">
            {rule.enabled
              ? '算不出下一次触发 —— 这条规则不会自己跑（正则可能被手工改坏了）'
              : '停用中，不会自己跑（「现在跑一次」仍然可以按）'}
          </span>}
        <span>
          {neverRan
            ? '一次都还没派过任务'
            : `已派 ${rule.runs} 条任务，最后一次 ${rule.lastRunAt ?? '（时间没记下来）'}`}
        </span>
      </div>

      {/* 启用但没挂上线程：这是一个要显式说出来的状态，它的现象就是"它永远不触发" */}
      {notScheduled && (
        <Alert
          type="error"
          showIcon
          className="schedule-card-alert"
          title="这条规则现在是「启用」，但它不在调度线程池里 —— 它不会自动触发"
          description="多半是它的正则被手工改过库里那一行（界面上的写入路径不允许存下算不出触发时刻的表达式）。改成一句合法的正则再保存一次，它就会被重新装进池子。"
        />
      )}
      {rule.nextRuns.length > 1 && (
        <div className="schedule-card-runs">
          接下来几次：{rule.nextRuns.slice(1).join(' / ')}
        </div>
      )}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/*  配规则 / 改规则：那个"页面配置化定时规则"的弹窗                        */
/* ------------------------------------------------------------------ */

/**
 * 一份配置的编辑弹窗（新建与修改共用）。
 *
 * <h2>为什么 `custom` 那一档不隐藏</h2>
 * 五种形态覆盖不了的东西（`MON-FRI`、月末 `L`、区间步长）总得有个入口，
 * 而把它藏起来的结果是用户去别的字段里凑一个表达不了他意思的值 ——
 * 那样配出来的规则"看起来配好了"，而跑的时刻不是他要的。
 * 手写那一档旁边照样有后端给的预览（这就够了：他要的是"能不能用"的答案）。
 *
 * <h2>预览什么时候发</h2>
 * 配置那几格一变（或手写框停下 350ms）就发一次。**防抖放在这一层**而不是 hook 里：
 * 它是界面手感的事（"我还在打字"），而 hook 只负责"发一次、认最后一次的结果"。
 *
 * <h2>发布人为什么不是输入框</h2>
 * 它就是**当前登录的人**（页头那个身份）：一条定时规则以谁的名义派活，
 * 在业务上就是"谁配的它"。让它可填会多出一种"以别人的名义自动派活"，
 * 而那种记录在库里与正常记录一模一样。
 */
function RuleModal({ open, rule, schedules, publisherName, onClose }: {
  open: boolean
  rule: AgentTaskSchedule | null
  schedules: UseSchedulesResult
  publisherName: string | null
  onClose: () => void
}) {
  const [draft, setDraft] = useState<ScheduleDraft>(DEFAULT_DRAFT)
  const [assignee, setAssignee] = useState('')
  const [description, setDescription] = useState('')
  const [enabled, setEnabled] = useState(true)
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null)

  // 打开时把"要改的那一条"摊成表单初值。`rule` 为空 = 新建，用缺省的那些值
  useEffect(() => {
    if (!open) return
    if (rule) {
      setDraft(draftOf(rule.cron))
      // ⚠️ 填的是 `assigneeName`（**登录名**）而不是 `assignee`（姓名）：
      // 这一格提交时要回传登录名，而姓名是"给人看的"那一份（显示名优先、可以重名）。
      // 两者填错的表现是"改规则永远失败"，报的还是"账号表里没有「李工」这个人"
      setAssignee(rule.assigneeName)
      setDescription(rule.description)
      setEnabled(rule.enabled)
    }
    else {
      setDraft(DEFAULT_DRAFT)
      setAssignee('')
      setDescription('')
      setEnabled(true)
    }
  }, [open, rule])

  const cron = useMemo(() => buildCron(draft), [draft])
  const { runPreview, preview, previewError, previewing, clearPreview } = schedules

  // 每次表达式变了就重新要一次预览（防抖 350ms）。手写那一档同样走这一条 ——
  // "这句能不能用"不在这里判断，一律问后端
  useEffect(() => {
    if (!open) return
    if (debounce.current) clearTimeout(debounce.current)
    debounce.current = setTimeout(() => runPreview(cron), 350)
    return () => {
      if (debounce.current) clearTimeout(debounce.current)
    }
  }, [open, cron, runPreview])

  const close = () => { clearPreview(); onClose() }

  const submit = async () => {
    if (!rule && !publisherName) return
    const form: ScheduleForm = { assignee, description: description.trim(), cron, enabled }
    const ok = rule
      ? await schedules.update(rule.id, form)
      : await schedules.create({ ...form, publisher: publisherName ?? '' })
    // 成功才关：失败时关掉会把用户填的内容丢掉，而他正需要照着报错改一改
    if (ok) close()
  }

  // 「保存」的可用性：三格必填 + **预览必须已经通过**。
  // 最后一条是刻意的 —— 用户在输入框里改到一半时正则可能是坏的，
  // 而那时点保存会得到一句 400；不如让按钮先点不动（后端照样会再校验一遍）
  const canSubmit = assignee.trim().length > 0 && description.trim().length > 0
    && preview.status === 'ok' && preview.data.cron === cron && !schedules.busy
    && (rule != null || publisherName != null)

  return (
    <Modal
      open={open}
      width={780}
      title={rule ? `改定时规则 #${rule.id}` : '配一条定时规则'}
      okText={rule ? '保存（删旧线程重加）' : '保存并启用'}
      cancelText="取消"
      confirmLoading={schedules.busy}
      okButtonProps={{ disabled: !canSubmit }}
      onOk={() => void submit()}
      onCancel={close}
    >
      <Flex vertical gap={12}>
        {/* 发布人：不是输入框，是当前登录的人（理由见上面那段） */}
        <Flex vertical gap={4}>
          <Text strong>发布人</Text>
          <Text type="secondary">
            {rule
              ? `${rule.publisher}（发布人不可改 —— 改它等于换一个人来背这条规则；那件事请用「停用旧的、建一条新的」）`
              : `${publisherName ?? '（未登录）'} —— 以当前登录的人的名义派活，界面上不提供"以别人名义"这一档`}
          </Text>
        </Flex>

        <UserPicker value={assignee} onChange={name => setAssignee(name)}
          placeholder="任务人（必填）—— 搜索登录名，从候选里选一个真实用户：每次触发的任务派给他" />

        <Input.TextArea rows={3} value={description} onChange={e => setDescription(e.target.value)}
          placeholder={'任务描述模板（必填）—— 可以写周期说法，比如「把昨天的失败运行汇总成一份晨报」；' +
            '到点会先让模型把「昨天」这类词换成那一刻的具体日期，再按换好之后的那句派活'} />

        <RuleBuilder draft={draft} onChange={setDraft} />

        {/* 拼出来的表达式 + 后端给的中文说法 + 未来 5 次触发时刻。这一块是这个弹窗的重点：
            "我配的到底对不对"只有这三样答得出来 */}
        <div className="schedule-preview" data-cron-preview={cron}>
          <Flex vertical gap={4}>
            <Flex gap={8} align="center" wrap>
              <Text strong>定时正则</Text>
              <Text code className="schedule-cron-text">{cron || '（空）'}</Text>
              <Text type="secondary">
                {CRON_FIELDS.join(' ')}（6 段）—— Unix crontab 的 5 段式这里不接受，秒那一段必须写出来
              </Text>
            </Flex>
            {previewError != null
              ? <Alert type="error" showIcon title="这句正则不能用" description={
                <div className="diag-note">
                  <p>{previewError}</p>
                  <p>改上面那几格，或者切到「手写表达式」把它改成后端认的 6 段形态。</p>
                </div>} />
              : preview.status === 'ok'
                ? <div className="schedule-preview-runs">
                  <div>中文说法：<strong data-preview-desc>{preview.data.description}</strong></div>
                  <div>
                    接下来 5 次触发：
                    <span data-preview-runs>{preview.data.nextRuns.join(' / ')}</span>
                  </div>
                  <div className="schedule-preview-hint">
                    这几行是**后端算的**（与调度线程池装线程用的是同一份判断）——
                    它们就是"我配的对不对"的答案。保存之后列表上那一格显示的也是同一份算法。
                  </div>
                </div>
                : <Text type="secondary">{previewing ? '正在向后端要预览…' : '（改一下上面几格就会拿一次预览）'}</Text>}
          </Flex>
        </div>

        <Flex gap={12} align="center" wrap>
          <Flex gap={6} align="center">
            <Switch checked={enabled} onChange={setEnabled} />
            <Text>{enabled ? '保存后启用（当场装进调度线程池）' : '先停用（保存但不自动跑，可以先按「现在跑一次」确认）'}</Text>
          </Flex>
        </Flex>

        <Text type="secondary">
          {rule
            ? '保存时后端固定做「删掉旧线程、按新正则重新装进调度线程池」—— 所以下一次触发一定是按上面这句正则算的，'
              + '旧线程上还没跑的那一次会被丢掉（那正是"改了规则"该有的效果）。'
              + '描述里可以写「昨天 / 上个月 / 本周」这类周期说法：到点会先让模型把它们换成那一刻的具体日期，'
              + '再按换好之后的那句派活（解析失败时这一次不派活，留到下一个周期重试）。'
            : '保存后这条规则会立刻装进调度线程池。到点它做两件事：先把描述模板里的周期说法（「昨天」「上个月」）'
              + '解析成那一刻的具体日期，再按解析后的那句生成一条 agent 任务 —— '
              + '后面那条链（判需求 → 拆任务点 → 判授权 → 执行 → 验收）与人手发布的任务完全一样，在 agent任务 那一页看。'
              + '解析这一步调不通时这一次不派活，留到下一个周期重试。'}
        </Text>
      </Flex>
    </Modal>
  )
}

/**
 * 执行频率那几格 —— <b>页面配置化定时规则</b>的本体。
 *
 * <p>五种形态各对应一句能一眼读懂的规则，而"每隔 N 天里的第几小时"这类组合刻意不给：
 * 那种正则需要人自己推（`0/6` 是从 0 点起算、`8-18` 是闭区间），
 * 一旦开始需要推，用户该做的是切到「手写表达式」直接写。
 */
function RuleBuilder({ draft, onChange }: {
  draft: ScheduleDraft
  onChange: (next: ScheduleDraft) => void
}) {
  const patch = (part: Partial<ScheduleDraft>) => onChange({ ...draft, ...part })
  const step = draft.unit === 'hour' ? 1 : 1
  const max = draft.unit === 'second' ? 59 : draft.unit === 'minute' ? 59 : 23

  return (
    <Flex vertical gap={8} className="schedule-builder">
      <Flex gap={8} align="center" wrap>
        <Text strong>执行频率</Text>
        <Radio.Group
          value={draft.mode}
          onChange={e => patch({ mode: e.target.value as ScheduleDraft['mode'] })}
          optionType="button"
          buttonStyle="solid"
          options={[
            { value: 'interval', label: '每隔' },
            { value: 'daily', label: '每天' },
            { value: 'weekly', label: '每周' },
            { value: 'monthly', label: '每月' },
            { value: 'custom', label: '手写表达式' },
          ]}
        />
      </Flex>

      {draft.mode === 'interval' && (
        <Flex gap={8} align="center" wrap>
          <Text>每</Text>
          <InputNumber min={1} max={max} step={step} value={draft.every}
            onChange={value => patch({ every: Number(value ?? 1) })} style={{ width: 90 }} />
          <Select
            value={draft.unit}
            style={{ width: 110 }}
            onChange={unit => patch({ unit, every: unit === 'hour' ? Math.min(draft.every, 23) : draft.every })}
            options={[
              { value: 'second', label: '秒' },
              { value: 'minute', label: '分钟' },
              { value: 'hour', label: '小时' },
            ]}
          />
          <Text type="secondary">
            {draft.unit === 'hour'
              ? '「每 N 小时」从 0 点起算（这是 cron 的语义，不是"从现在起算"）'
              : '秒与分的最小粒度都是 1'}
          </Text>
        </Flex>
      )}

      {(draft.mode === 'daily' || draft.mode === 'weekly' || draft.mode === 'monthly') && (
        <Flex gap={8} align="center" wrap>
          {draft.mode === 'weekly' && (
            <>
              <Text>星期</Text>
              <Select
                mode="multiple"
                style={{ minWidth: 240 }}
                value={draft.weekdays}
                onChange={weekdays => patch({ weekdays })}
                options={WEEKDAYS.map(day => ({ value: day.value, label: day.label }))}
                placeholder="选一天或几天（可多选）"
              />
              {/*
                两个常用组合做成按钮，而不是让用户在多选下拉里点五次。
                判据是实测踩到的一件事：**缺省勾着周一到周五**，于是"把周二、周四去掉"这件事
                在多选里是"再点一次取消"，而它与"点一次选中"在屏幕上长得一样 ——
                点错了不会报错，只会拼出一句多两天的正则。
                按钮上写的是它**会变成什么**（不是当前态），一次点击就是一份完整的集合。
              */}
              <Button size="small" onClick={() => patch({ weekdays: [1, 2, 3, 4, 5] })}>工作日</Button>
              <Button size="small" onClick={() => patch({ weekdays: [6, 0] })}>周末</Button>
              <Button size="small" onClick={() => patch({ weekdays: [] })}>清空</Button>
            </>
          )}
          {draft.mode === 'monthly' && (
            <>
              <Text>每月</Text>
              <InputNumber min={1} max={31} value={draft.dayOfMonth}
                onChange={value => patch({ dayOfMonth: Number(value ?? 1) })} style={{ width: 80 }} />
              <Text>号</Text>
            </>
          )}
          <Text>{draft.mode === 'daily' ? '每天' : ''}</Text>
          <InputNumber min={0} max={23} value={draft.hour}
            onChange={value => patch({ hour: Number(value ?? 0) })} style={{ width: 70 }} />
          <Text>时</Text>
          <InputNumber min={0} max={59} value={draft.minute}
            onChange={value => patch({ minute: Number(value ?? 0) })} style={{ width: 70 }} />
          <Text>分</Text>
        </Flex>
      )}

      {draft.mode === 'custom' && (
        <Flex vertical gap={4}>
          <Input
            value={draft.expression}
            onChange={e => patch({ expression: e.target.value })}
            placeholder="例：0 0 9 ? * MON-FRI（工作日 09:00:00）、0 0/30 8-18 ? * MON-FRI（工作日 8~18 点每半小时）、0 0 0 L * ?（每月最后一天 00:00:00）"
          />
          <Text type="secondary">
            6 段依次是 {CRON_FIELDS.join(' / ')}。「日」与「周」不能同时写具体值 ——
            解析会把它们当成"或"，也就是两个日子都会跑，而且不报错；不用的那一格写 ? 或 *。
          </Text>
        </Flex>
      )}

      <Text type="secondary" className="schedule-builder-summary">
        这一份配置说的是：<strong data-draft-summary>{describeDraft(draft)}</strong>
      </Text>

      {draft.mode === 'weekly' && draft.weekdays.length === 0 && (
        <Alert type="warning" showIcon title="还没选星期几"
          description="一个星期都没选时，「周」那一格会写成通配 *，也就是「每天都跑」—— 那不是你要的。先选一天或几天。" />
      )}
      {draft.mode === 'monthly' && draft.dayOfMonth > 28 && (
        <Alert type="warning" showIcon title={`每月 ${draft.dayOfMonth} 号：有几个月没有这一天`}
          description={`2 月没有 ${draft.dayOfMonth} 号、4/6/9/11 月只有 30 天 —— 那些月份里这条规则不会触发。要"每月都跑一次"请选 1~28 号。`} />
      )}
    </Flex>
  )
}

/** 让 Paragraph 的导入有意义（长描述的展示在 Clamped 里，这里只留一处提示文案）。 */
export function ScheduleNote() {
  return <Paragraph type="secondary">定时任务到点只生成一条 agent 任务记录；执行本身在 agent任务 那一页。</Paragraph>
}
