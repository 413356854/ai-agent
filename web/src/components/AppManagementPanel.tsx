import { Alert, Card, Flex, Tag, Typography } from 'antd'

/**
 * 「AI Agent 应用管理」的目录位（这一区目前只有目录，没有功能）。
 *
 * <h2>为什么先把这一页做出来，而不是等真有接口了再加</h2>
 * 因为它是**导航结构的一部分**：目录要能一眼看出"这个控制台有几块"。
 * 等接口齐了再补目录，中间这段时间所有人看到的是一个"只有对话台"的控制台。
 *
 * <h2>★ 这一页有意不画假数据</h2>
 * 一张画着"3 个应用、2 个已上线"的假表格，与"这一页还没有后端"在页面上长得
 * 一模一样，而后果不同：前者会被当成可用的功能去用（并开始怀疑数据为什么不变）。
 * 所以这里给的是**聚合清单**：每一行写清"要聚合什么、数据从哪个已有端点来、
 * 还差什么" —— 它是这一页的下一个实现步骤，随时可核对，也不会被读成已完成。
 *
 * 与「配置与实测必须同屏」（AlertPanel 灰度块）是同一条纪律：
 * 只说"计划要做什么"，不说"现在已经有了"。
 */

/** 一行 = 一个要被聚合进应用清单的字段。`gap` 非空表示这个字段**还没有端点** */
interface PlannedField {
  /** 字段在应用清单里显示成什么 */
  field: string
  /** 现在从哪儿能读到它（「—」= 当前没有任何端点给这个数） */
  source: string
  /** 后端对应的位置 */
  backend: string
  /** 还差什么 */
  gap: string
}

const PLANNED: PlannedField[] = [
  {
    field: '模型 / 温度 / 上下文窗口',
    source: '`GET /api/diag/threads/config`',
    backend: 'ChatServiceImpl + TokenBudget',
    gap: '窗口与温度现在是全局配置，没有"按应用覆盖"的概念',
  },
  {
    field: '该应用当前生效的提示词版本与指纹',
    source: '`GET /api/prompts/current`',
    backend: 'PromptService + `prompt_version` 表',
    gap: '§5.2 的生效版本是全局唯一一份，应用与应用之间分不开',
  },
  {
    field: '启用了哪几把工具、各自调用次数与平均耗时',
    source: '`GET /api/cost/summary`（按工具那一栏）+ MCP 工具清单',
    backend: '`agent_tool_invocation` 表 + McpToolExposure',
    gap: '成本那一栏是**实际调用过**的，不等于**已启用**的清单',
  },
  {
    field: '写操作要不要人工确认、超时多少秒',
    source: '`GET /api/agent/limits`（闸门状态）+ 待确认卡',
    backend: 'ApprovalGate + ToolHints',
    gap: '当前是全局闸门 + 每次运行的权限档位，没有"按应用固定档位"',
  },
  {
    field: '知识库规模与分块策略',
    source: '`GET /api/diag/rag/corpus`',
    backend: 'RAG 解析 + 分块链路',
    gap: '语料是启动时装载的静态资源，不随应用切换',
  },
  {
    field: '灰度状态、告警规则与通知路由',
    source: '`GET /api/canary` + `GET /api/alerts/rules`',
    backend: 'CanaryService + `alert_rule` 表',
    gap: '灰度挂在**模型版本**上，不挂在应用上',
  },
  {
    field: '每个应用的任务数、失败率、拒绝次数、token 与金额',
    source: '`GET /api/agent/runs` + `GET /api/cost/summary`',
    backend: '`agent_run` / `cost` 聚合',
    gap: '运行记录里没有"应用"这一维，现在只能按租户与业务归因键拆',
  },
]

/** 这一页明确不做的事。"不做"要和"还没做"分开写，否则读者会把两者混成"功能坏了" */
const OUT_OF_SCOPE = [
  '不调任何后端：这一页零请求，页面上所有内容都是静态的规划清单。',
  '不做增删改：应用的注册、改配置、上下线都还没有后端接口，界面也就不给按钮 —— 给一个点了会 404 的按钮，比没有按钮更糟。',
  '不做运行历史：单次运行与五层 trace 属于「对话台 · Agent 工具调用」，这里只放"这个应用一共跑了多少"。',
  '不判"配置对不对"：模型选得合不合适、权限档位宽不宽，判据都在后端（ToolHints / ApprovalGate），这一页只如实显示。',
]

export function AppManagementPanel() {
  return (
    <div className="diag-panel">
      {/*
        Alert 用 warning 而不是 info：这一页**现在不能用来做任何事**，
        用中性色会让它看起来像一个已经能用的页面。
        ⚠️ 标题这个 prop 在 antd 6 里叫 `title`：写成旧名 `message` 不报错，
        只在控制台留一条 deprecation 警告 —— 而自检最后一条断言盯的就是控制台干净。
      */}
      <Alert
        type="warning"
        showIcon
        title="这一区目前只有目录，页面还是空的：应用注册表没有后端接口"
        description={
          <div className="diag-note">
            <p>
              下面那张表是<strong>聚合清单</strong>，不是应用列表 ——
              它逐行写明"应用清单上要显示什么、现在能从哪个已有端点读到、还差什么"。
              后端补齐之后，每一行会变成应用清单里的一列。
            </p>
            <p>
              在那之前，界面上<strong>不会</strong>出现任何看起来像应用数据的数字：
              假数据与真数据在页面上长得一模一样，而"这里其实还没有数据"这件事
              没有任何地方会报错。
            </p>
          </div>
        }
      />

      <Card
        size="small"
        className="diag-card diag-card-wide"
        title={
          <Flex gap={8} align="center" wrap>
            <span>应用清单：一个 AI Agent 应用由哪几块配置决定</span>
            <Tag color="geekblue">目录已就位 · 聚合未接线</Tag>
          </Flex>
        }
      >
        <div className="rag-table-scroll">
          <table className="rag-metric-table app-plan-table">
            <thead>
              <tr>
                <th>应用清单上的字段</th>
                <th>现在从哪儿读</th>
                <th>后端位置</th>
                <th>还差什么</th>
              </tr>
            </thead>
            <tbody>
              {PLANNED.map(row => (
                <tr key={row.field}>
                  <td className="app-plan-field">{row.field}</td>
                  <td className="is-mono">{row.source}</td>
                  <td>{row.backend}</td>
                  <td className="app-plan-gap">{row.gap}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Typography.Text type="secondary" className="diag-note app-plan-summary">
          表中 7 行里，<strong>6 行的数据已经存在</strong>（只是散在六个面板上，没有"按应用"这一维），
          真正缺的是一个应用注册表：应用的标识、它引用哪一版提示词、启用哪几把工具、
          固定哪一档权限。这一区要做的就是把这些收进一张表，再让对话台按选中的应用去调用。
        </Typography.Text>
      </Card>

      <Card size="small" className="diag-card diag-card-wide" title="这一页现在不做什么">
        <ul className="cost-notes">
          {OUT_OF_SCOPE.map(line => <li key={line}>{line}</li>)}
        </ul>
      </Card>
    </div>
  )
}
