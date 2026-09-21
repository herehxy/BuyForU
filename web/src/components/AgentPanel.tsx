// AI 导购面板：需求输入、阶段流程、错误提示和最近任务。
// 面板只负责呈现和提交，所有状态迁移都由后端 phase 决定。
import { useState } from 'react'
import type { DeliveryAddress } from '../api'
import { phaseLabel } from '../api'
import type { AgentRun, CommandAccepted } from '../types'
import { RunFlow } from './RunFlow'

export type AgentPanelProps = {
  address?: DeliveryAddress
  requirement: string
  onRequirementChange: (value: string) => void
  onSubmit: () => void
  busy: boolean
  hint?: string
  run?: AgentRun
  errors: string[]
  act: (action: () => Promise<CommandAccepted>) => void
  recentRuns: AgentRun[]
  showTasks: boolean
  onOpenRun: (run: AgentRun) => void
  onOpenTasks: () => void
  onCloseTasks: () => void
  onViewOrders: () => void
}

const EXAMPLES = [
  '帮我找一台 5000 元以内、16GB 内存、明天能到的轻薄本',
  '想买个通勤用的降噪耳机，预算 2000 元',
  '需要一台 32GB 内存的开发用笔记本，这周能到就行',
]

export function AgentPanel(props: AgentPanelProps) {
  const [showExamples, setShowExamples] = useState(true)
  const showFlow = !props.showTasks && (Boolean(props.run) || props.busy)

  return (
    <aside className="dock">
      <div className="dock-head">
        <div className="dock-title">
          <span className="spark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
              <path d="M12 2.5 13.9 9 20.5 11l-6.6 2L12 19.5 10.1 13 3.5 11 10.1 9 12 2.5Z" />
            </svg>
          </span>
          <h2>AI 导购</h2>
        </div>
        <button type="button" className="ghost"
                disabled={!props.showTasks && props.recentRuns.length === 0}
                onClick={props.showTasks ? props.onCloseTasks : props.onOpenTasks}>
          {props.showTasks ? '返回导购' : `最近任务 ${props.recentRuns.length || ''}`.trim()}
        </button>
      </div>

      <form className="dock-composer" onSubmit={(event) => { event.preventDefault(); props.onSubmit() }}>
        <textarea
          value={props.requirement}
          placeholder="把预算、规格和到货时间一次说清，例如：5000 元以内、16GB 内存、明天能到"
          onChange={(event) => props.onRequirementChange(event.target.value)}
          aria-label="购物需求"
        />
        <div className="dock-composer-foot">
          <button type="button" className="ghost" onClick={() => setShowExamples((value) => !value)}>
            {showExamples ? '收起示例' : '看看示例'}
          </button>
          <button type="submit" disabled={props.busy || !props.requirement.trim() || !props.address}>
            {props.busy ? '正在处理…' : '开始选购'}
          </button>
        </div>
        {showExamples && (
          <div className="examples">
            {EXAMPLES.map((example) => (
              <button type="button" key={example} className="example"
                      onClick={() => props.onRequirementChange(example)}>{example}</button>
            ))}
          </div>
        )}
      </form>

      {!props.address && (
        <p className="notice">先在顶部设置收货地，Agent 才能算出到货时间。</p>
      )}

      {props.errors.map((message) => <p className="error" key={message}>{message}</p>)}

      {showFlow ? (
        props.run
          ? <RunFlow run={props.run} busy={props.busy} hint={props.hint} act={props.act}
                     onViewOrders={props.onViewOrders} />
          : <p className="notice">{props.hint ?? '正在排队…'}</p>
      ) : props.recentRuns.length > 0 ? (
        <section className="tasks">
          <h3>最近任务</h3>
          <p className="muted">刷新页面后可以在这里继续处理待选择或待审批的任务。</p>
          {props.recentRuns.map((item) => (
            <button type="button" className="task" key={item.runId} onClick={() => props.onOpenRun(item)}>
              <span className="task-text">{item.originalRequest}</span>
              <span className="task-phase">{phaseLabel(item.phase)}</span>
            </button>
          ))}
        </section>
      ) : (
        <p className="notice">还没有任务。用上方的输入框描述需求，或直接在商品卡片上让 AI 帮你买。</p>
      )}
    </aside>
  )
}
