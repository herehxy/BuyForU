// AI 导购面板里的流程视图：阶段进度、补充信息、候选选择、库存锁定、账单确认和订单结果。
// 允许显示哪些操作完全由后端 phase 决定，前端不自行推进状态。
import { useState } from 'react'
import { cancelRun, clarify, decide, phaseLabel, relaxConstraints, selectCandidate } from '../api'
import type { AgentRun, Candidate, CommandAccepted } from '../types'
import { orderStatusLabel } from '../types'
import { visualFor } from '../productImages'

export type RunFlowProps = {
  run: AgentRun
  busy: boolean
  hint?: string
  act: (action: () => Promise<CommandAccepted>) => void
  onViewOrders?: () => void
}

const PIPELINE = [
  { match: ['NEW', 'SEARCHING'], label: '理解需求并搜索' },
  { match: ['NEEDS_CLARIFICATION'], label: '补充信息' },
  { match: ['PRESENTING_CANDIDATES'], label: '选择商品' },
  { match: ['PREPARING_CONFIRMABLE_ORDER', 'WAITING_APPROVAL'], label: '确认金额' },
  { match: ['CREATING_ORDER', 'COMPLETED'], label: '创建订单' },
] as const

const PHASE_ORDER = ['NEW', 'SEARCHING', 'NEEDS_CLARIFICATION', 'PRESENTING_CANDIDATES',
  'PREPARING_CONFIRMABLE_ORDER', 'WAITING_APPROVAL', 'CREATING_ORDER', 'COMPLETED']

const RELAX_FIELDS = [
  { id: 'budgetMax', label: '预算上限' },
  { id: 'budgetMin', label: '预算下限' },
  { id: 'preferredBrands', label: '品牌' },
  { id: 'requiredAttributes', label: '规格' },
  { id: 'deliveryBy', label: '送达时间' },
  { id: 'quantity', label: '数量' },
  { id: 'query', label: '搜索词' },
] as const

export function AgentProgress({ phase, hint }: { phase?: string; hint?: string }) {
  const current = phase ?? 'NEW'
  return (
    <div className="progress">
      <div className="status"><span className="pulse" />{hint || phaseLabel(current)}</div>
      <ol className="steps">
        {PIPELINE.map((step) => {
          const phases = step.match as readonly string[]
          const active = phases.includes(current)
          const done = PHASE_ORDER.indexOf(current) > PHASE_ORDER.indexOf(phases[phases.length - 1])
          return <li key={step.label} className={active ? 'active' : done ? 'done' : ''}>{step.label}</li>
        })}
      </ol>
    </div>
  )
}

function RelaxForm({ runId, busy, act }: {
  runId: string
  busy: boolean
  act: (action: () => Promise<CommandAccepted>) => void
}) {
  const [relaxation, setRelaxation] = useState('')
  const [fields, setFields] = useState<string[]>([])
  const toggle = (id: string) => setFields((current) =>
    current.includes(id) ? current.filter((item) => item !== id) : [...current, id])
  return (
    <form className="clarification" onSubmit={(event) => {
      event.preventDefault()
      if (relaxation.trim() && fields.length > 0) {
        act(() => relaxConstraints(runId, relaxation.trim(), fields))
      }
    }}>
      <h3>当前硬性条件下没有合适商品</h3>
      <p>先勾选允许改的条件，再写具体要求。没勾选的字段不会动。</p>
      <div className="chip-row">
        {RELAX_FIELDS.map((field) => (
          <label key={field.id} className={fields.includes(field.id) ? 'chip on' : 'chip'}>
            <input type="checkbox" checked={fields.includes(field.id)}
                   onChange={() => toggle(field.id)} /> {field.label}
          </label>
        ))}
      </div>
      <textarea value={relaxation} placeholder="例如：预算可以提高到 5500 元"
                onChange={(event) => setRelaxation(event.target.value)} />
      <div className="actions">
        <button className="secondary" type="button" disabled={busy}
                onClick={() => act(() => cancelRun(runId))}>不放宽，取消任务</button>
        <button disabled={busy || !relaxation.trim() || fields.length === 0}>批准这些条件变更</button>
      </div>
    </form>
  )
}

function CandidateRow({ candidate, runId, busy, act }: {
  candidate: Candidate
  runId: string
  busy: boolean
  act: (action: () => Promise<CommandAccepted>) => void
}) {
  const visual = visualFor(candidate.skuId)
  return (
    <article className={candidate.available ? 'pick' : 'pick unavailable'}>
      <div className="pick-thumb">
        <img src={visual.src} alt={candidate.name} loading="lazy"
             onError={(event) => { event.currentTarget.style.display = 'none' }} />
        <span className={`thumb-fallback ${visual.tone}`} aria-hidden="true">{candidate.brand.slice(0, 1)}</span>
      </div>
      <div className="pick-body">
        <span className="pick-brand">{candidate.brand}</span>
        <h4>{candidate.name}</h4>
        {Object.keys(candidate.attributes).length > 0 && (
          <div className="pick-attrs">
            {Object.entries(candidate.attributes).map(([key, value]) => (
              <span key={key}>{key} {value}</span>
            ))}
          </div>
        )}
        <p className="pick-meta">{candidate.deliveryDate} 送达 · {candidate.available ? '有货' : '暂时缺货'}</p>
      </div>
      <div className="pick-side">
        <strong className="price">¥{candidate.displayPrice.amount}</strong>
        <button disabled={busy || !candidate.available}
                onClick={() => act(() => selectCandidate(runId, candidate.skuId))}>选择并锁定</button>
      </div>
    </article>
  )
}

export function RunFlow({ run, busy, hint, act, onViewOrders }: RunFlowProps) {
  const [clarification, setClarification] = useState('')
  return (
    <div className="run">
      {(busy || run) && <AgentProgress phase={run.phase} hint={hint} />}
      {run.lastError && <p className="error">{run.lastError}</p>}

      {run.phase === 'NEEDS_CLARIFICATION' && (
        <form className="clarification" onSubmit={(event) => {
          event.preventDefault()
          if (clarification.trim()) act(() => clarify(run.runId, clarification.trim()))
        }}>
          <h3>还需要一点信息</h3>
          <p>{run.planSpec?.clarification?.question ?? '请补充缺少的购物条件。'}</p>
          <textarea value={clarification} placeholder="例如：主要用于办公，偶尔剪辑视频"
                    onChange={(event) => setClarification(event.target.value)} />
          <div className="actions">
            <button className="secondary" type="button" disabled={busy}
                    onClick={() => act(() => cancelRun(run.runId))}>取消任务</button>
            <button disabled={busy || !clarification.trim()}>{busy ? '正在处理…' : '继续'}</button>
          </div>
        </form>
      )}

      {run.phase === 'PRESENTING_CANDIDATES' && (
        <div className="picks">
          {run.candidateSet.map((candidate) => (
            <CandidateRow key={candidate.skuId} candidate={candidate} runId={run.runId}
                          busy={busy} act={act} />
          ))}
          <button className="secondary block" disabled={busy}
                  onClick={() => act(() => cancelRun(run.runId))}>没有合适商品，取消任务</button>
        </div>
      )}

      {run.phase === 'PREPARING_CONFIRMABLE_ORDER' && run.selectedCandidateIndex >= 0
        && run.selectedCandidateIndex < run.candidateSet.length && (
        <article className="snapshot">
          <h3>正在锁定库存并生成确认快照</h3>
          <p>如果上一次调用因网络或 Commerce 协议错误中断，可以安全重试；后端会复用同一个
            effectId，不会重复预占库存。</p>
          <button disabled={busy} onClick={() => act(() => selectCandidate(
            run.runId, run.candidateSet[run.selectedCandidateIndex].skuId))}>
            {busy ? '正在处理…' : '重试锁定库存'}
          </button>
        </article>
      )}

      {run.phase === 'NEEDS_CONSTRAINT_RELAXATION' && (
        <RelaxForm runId={run.runId} busy={busy} act={act} />
      )}

      {run.phase === 'WAITING_APPROVAL' && run.confirmableSnapshot && (
        <article className="snapshot bill">
          <h3>{run.candidateSet[run.selectedCandidateIndex]?.name ?? run.confirmableSnapshot.quote.skuId}</h3>
          <p className="muted">SKU {run.confirmableSnapshot.quote.skuId} · 数量 {run.confirmableSnapshot.quote.quantity}</p>
          <dl className="bill-lines">
            <div><dt>商品金额</dt><dd>¥{run.confirmableSnapshot.quote.itemAmount.amount}</dd></div>
            {run.confirmableSnapshot.quote.discounts.map((discount) => (
              <div key={discount.code}><dt>{discount.description}</dt>
                <dd className="minus">-¥{discount.amount.amount}</dd></div>
            ))}
            <div><dt>运费</dt><dd>¥{run.confirmableSnapshot.quote.shippingFee.amount}</dd></div>
            <div className="bill-total"><dt>最终应付</dt>
              <dd className="price">¥{run.confirmableSnapshot.quote.payableAmount.amount}</dd></div>
          </dl>
          <p className="muted">预计 {run.confirmableSnapshot.quote.deliveryPromise} 送达</p>
          {run.confirmableSnapshot.quote.observedAt &&
            <p className="muted">价格查询于 {new Date(run.confirmableSnapshot.quote.observedAt).toLocaleString()}</p>}
          <p className="reserve">库存已临时锁定至 {new Date(run.confirmableSnapshot.expiresAt).toLocaleTimeString()}</p>
          <div className="actions">
            <button className="secondary" disabled={busy} onClick={() => act(() => decide(run, 'REJECT'))}>取消</button>
            <button disabled={busy} onClick={() => act(() => decide(run, 'APPROVE'))}>确认创建订单</button>
          </div>
        </article>
      )}

      {run.phase === 'COMPLETED' && run.finalOrder && (
        <article className="success">
          <h3>订单已创建</h3>
          <p className="order-no">{run.finalOrder.orderId}</p>
          <strong>{orderStatusLabel(run.finalOrder.status)}</strong>
          <p className="muted">
            应付 ¥{run.finalOrder.quote.payableAmount.amount} · 预计 {run.finalOrder.quote.deliveryPromise} 送达
          </p>
          {onViewOrders && (
            <button type="button" className="secondary" onClick={onViewOrders}>查看我的订单</button>
          )}
        </article>
      )}

      {run.phase === 'CANCELLED' && <article className="success">
        <h3>任务已取消</h3>
        <p>已释放本任务占用的库存，不会创建订单。</p></article>}
    </div>
  )
}
