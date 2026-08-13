import { FormEvent, useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { cancelRun, clarify, decide, followRun, getRun, listAddresses, listRuns, phaseLabel, registerAddress, relaxConstraints, selectCandidate, startRun } from './api'
import type { DeliveryAddress } from './api'
import type { AgentRun, CommandAccepted } from './types'
import type { User } from 'oidc-client-ts'
import { authConfigurationError, userManager } from './auth'

let callbackInFlight: Promise<User> | undefined

// React StrictMode 在开发环境会重复执行 effect；共享 Promise 防止 OIDC callback 被消费两次。
function completeSignInCallback(): Promise<User> {
  callbackInFlight ??= userManager!.signinRedirectCallback().finally(() => {
    callbackInFlight = undefined
  })
  return callbackInFlight
}

export function App() {
  const [user, setUser] = useState<User | null>()
  const [message, setMessage] = useState('帮我找一台 5000 元以内、16GB 内存、明天能到的轻薄本')
  const [zone, setZone] = useState('CN-EAST')
  const [address, setAddress] = useState<DeliveryAddress>()
  const [run, setRun] = useState<AgentRun>()
  const [recentRuns, setRecentRuns] = useState<AgentRun[]>([])
  const [restoreError, setRestoreError] = useState<string>()
  const [progress, setProgress] = useState<string>()
  const mutation = useMutation({
    mutationFn: async (action: () => Promise<CommandAccepted>) => {
      const accepted = await action()
      setProgress('任务已排队，等待执行')
      return followRun(accepted, (update) => {
        setProgress(update.label)
        if (update.run) setRun(update.run)
      })
    },
    onSuccess: setRun,
  })
  const addressMutation = useMutation({ mutationFn: registerAddress, onSuccess: setAddress })

  // 登录后从服务端恢复地址和最近任务，页面刷新不丢失人工等待状态。
  useEffect(() => {
    if (!user) return
    Promise.all([listAddresses(), listRuns()]).then(([addresses, runs]) => {
      setAddress(addresses[0])
      setRecentRuns(runs)
      setRestoreError(undefined)
    }).catch((failure: unknown) => {
      setRestoreError(failure instanceof Error ? failure.message : '无法恢复已有任务和配送地址。')
    })
  }, [user])

  useEffect(() => {
    if (!userManager) {
      setUser(null)
      return
    }
    if (window.location.pathname === '/auth/callback') {
      completeSignInCallback()
        .then((authenticated) => {
          window.history.replaceState({}, '', '/')
          setUser(authenticated)
        })
        .catch(() => userManager!.getUser()
          .then((authenticated) => setUser(authenticated?.expired ? null : authenticated)))
      return
    }
    // 退出后 Keycloak 回到站点根路径，清掉回调参数再读本地会话。
    userManager.signoutRedirectCallback().catch(() => undefined).finally(() => {
      window.history.replaceState({}, '', '/')
      userManager!.getUser().then((authenticated) => setUser(authenticated?.expired ? null : authenticated))
    })
  }, [])

  if (authConfigurationError) return <main className="shell"><div className="error">{authConfigurationError}</div></main>
  if (user === undefined) return <main className="shell"><p>正在检查登录状态…</p></main>
  if (!user) return <main className="shell login"><h1>BuyForU</h1><p>登录后才能创建和审批订单。</p>
    <button onClick={() => userManager!.signinRedirect()}>安全登录</button></main>

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (address) mutation.mutate(() => startRun(message, address.addressId))
  }

  return (
    <main className="shell">
      <header>
        <div className="eyebrow">BUYFORU · SAFE SHOPPING AGENT</div>
        <h1>把复杂购物，变成一次清楚的决定。</h1>
        <p>Agent 可以搜索和比较；价格、库存与订单始终由商城系统确认。</p>
        <button className="secondary" onClick={() => userManager!.signoutRedirect()}>退出登录</button>
      </header>

      <form className="composer" onSubmit={submit}>
        {!address && <div className="address-row">
          <select value={zone} onChange={(event) => setZone(event.target.value)}>
            <option value="CN-EAST">华东（预计 1 天）</option>
            <option value="CN-CENTRAL">华中（预计 2 天）</option>
            <option value="CN-WEST">西部（预计 3 天）</option>
          </select>
          <button type="button" disabled={addressMutation.isPending}
                  onClick={() => addressMutation.mutate(zone)}>登记配送区域</button>
        </div>}
        {address && <p className="address-ok">配送区域已登记：{address.zoneCode}，地址编号 {address.addressId}</p>}
        <textarea value={message} onChange={(event) => setMessage(event.target.value)} />
        <button disabled={mutation.isPending || !address}>开始选购</button>
      </form>

      {addressMutation.error && <div className="error">{addressMutation.error.message}</div>}
      {mutation.error && <div className="error">{mutation.error.message}</div>}
      {(mutation.isPending || run) && (
        <AgentProgress phase={run?.phase} hint={mutation.isPending ? progress : undefined} />
      )}
      {restoreError && <div className="error">恢复已有数据失败：{restoreError}</div>}
      {run && <RunView run={run} busy={mutation.isPending} act={mutation.mutate} />}
      {!run && recentRuns.length > 0 && <section className="history">
        <h2>最近任务</h2>
        {recentRuns.map((item) => <button type="button" className="history-item" key={item.runId}
          onClick={() => getRun(item.runId).then(setRun).catch(() => setRun(item))}>
          <span>{item.originalRequest}</span><strong>{phaseLabel(item.phase)}</strong>
        </button>)}
      </section>}
    </main>
  )
}

const PIPELINE = [
  { match: ['NEW', 'SEARCHING'], label: '理解需求并搜索' },
  { match: ['NEEDS_CLARIFICATION'], label: '补充信息' },
  { match: ['PRESENTING_CANDIDATES'], label: '选择商品' },
  { match: ['PREPARING_CONFIRMABLE_ORDER', 'WAITING_APPROVAL'], label: '确认金额' },
  { match: ['CREATING_ORDER', 'COMPLETED'], label: '创建订单' },
] as const

function AgentProgress({ phase, hint }: { phase?: string; hint?: string }) {
  const current = phase ?? 'NEW'
  return (
    <section className="progress">
      <div className="status"><span />{hint || phaseLabel(current)}</div>
      <ol className="steps">
        {PIPELINE.map((step) => {
          const phases = step.match as readonly string[]
          const active = phases.includes(current)
          const done = pipelineDone(current, phases[phases.length - 1])
          return <li key={step.label} className={active ? 'active' : done ? 'done' : ''}>{step.label}</li>
        })}
      </ol>
    </section>
  )
}

function pipelineDone(phase: string, stepEnd: string): boolean {
  const order = ['NEW', 'SEARCHING', 'NEEDS_CLARIFICATION', 'PRESENTING_CANDIDATES',
    'PREPARING_CONFIRMABLE_ORDER', 'WAITING_APPROVAL', 'CREATING_ORDER', 'COMPLETED']
  return order.indexOf(phase) > order.indexOf(stepEnd)
}

const RELAX_FIELDS = [
  { id: 'budgetMax', label: '预算' },
  { id: 'preferredBrands', label: '品牌' },
  { id: 'requiredAttributes', label: '规格' },
  { id: 'deliveryBy', label: '送达时间' },
  { id: 'quantity', label: '数量' },
  { id: 'query', label: '搜索词' },
] as const

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
      <h2>当前硬性条件下没有合适商品</h2>
      <p>先勾选允许改的条件，再写具体要求。没勾选的字段不会动。</p>
      <div className="address-row">
        {RELAX_FIELDS.map((field) => (
          <label key={field.id}>
            <input type="checkbox" checked={fields.includes(field.id)}
                   onChange={() => toggle(field.id)} /> {field.label}
          </label>
        ))}
      </div>
      <textarea value={relaxation} placeholder="例如：预算可以提高到 5500 元"
                onChange={(event) => setRelaxation(event.target.value)} />
      <button disabled={busy || !relaxation.trim() || fields.length === 0}>批准这些条件变更</button>
      <button type="button" className="secondary" disabled={busy}
              onClick={() => act(() => cancelRun(runId))}>不放宽，取消任务</button>
    </form>
  )
}

function RunView({ run, busy, act }: {
  run: AgentRun
  busy: boolean
  act: (action: () => Promise<CommandAccepted>) => void
}) {
  // UI 严格按照后端 phase 显示允许的动作，不能由前端跳过选品或快照确认。
  const [clarification, setClarification] = useState('')
  return (
    <section className="run">
      <p className="phase-copy">{phaseLabel(run.phase)}</p>
      {run.lastError && <p className="error">{run.lastError}</p>}

      {run.phase === 'NEEDS_CLARIFICATION' && (
        <form className="clarification" onSubmit={(event) => {
          event.preventDefault()
          if (clarification.trim()) act(() => clarify(run.runId, clarification.trim()))
        }}>
          <h2>还需要一点信息</h2>
          <p>{run.planSpec?.clarification?.question ?? '请补充缺少的购物条件。'}</p>
          <textarea value={clarification}
                    onChange={(event) => setClarification(event.target.value)} />
          <button disabled={busy || !clarification.trim()}>继续</button>
          <button type="button" className="secondary" disabled={busy}
                  onClick={() => act(() => cancelRun(run.runId))}>取消任务</button>
        </form>
      )}

      {run.phase === 'PRESENTING_CANDIDATES' && (
        <div className="grid">
          {run.candidateSet.map((candidate) => (
            <article className="card" key={candidate.skuId}>
              <div className="brand">{candidate.brand}</div>
              <h2>{candidate.name}</h2>
              <dl>{Object.entries(candidate.attributes).map(([key, value]) => (
                <div key={key}><dt>{key}</dt><dd>{value}</dd></div>
              ))}</dl>
              <strong>¥{candidate.displayPrice.amount}</strong>
              <p>{candidate.deliveryDate} 送达 · {candidate.available ? '有货' : '暂时缺货'}</p>
              <button disabled={busy || !candidate.available}
                      onClick={() => act(() => selectCandidate(run.runId, candidate.skuId))}>
                选择并锁定库存
              </button>
            </article>
          ))}
          <button className="secondary" disabled={busy}
                  onClick={() => act(() => cancelRun(run.runId))}>没有合适商品，取消任务</button>
        </div>
      )}

      {run.phase === 'NEEDS_CONSTRAINT_RELAXATION' && (
        <RelaxForm runId={run.runId} busy={busy} act={act} />
      )}

      {run.phase === 'WAITING_APPROVAL' && run.confirmableSnapshot && (
        <article className="snapshot">
          <h2>{run.candidateSet[run.selectedCandidateIndex]?.name ?? run.confirmableSnapshot.quote.skuId}</h2>
          <p>SKU：{run.confirmableSnapshot.quote.skuId} · 数量：{run.confirmableSnapshot.quote.quantity}</p>
          <p>商品金额：¥{run.confirmableSnapshot.quote.itemAmount.amount}</p>
          {run.confirmableSnapshot.quote.discounts.map((discount) =>
            <p key={discount.code}>{discount.description}：-¥{discount.amount.amount}</p>)}
          <p>运费：¥{run.confirmableSnapshot.quote.shippingFee.amount}</p>
          <div><span>最终应付</span><strong>¥{run.confirmableSnapshot.quote.payableAmount.amount}</strong></div>
          <p>预计 {run.confirmableSnapshot.quote.deliveryPromise} 送达</p>
          {run.confirmableSnapshot.quote.observedAt &&
            <p>价格查询于 {new Date(run.confirmableSnapshot.quote.observedAt).toLocaleString()}</p>}
          <p>库存已临时锁定至 {new Date(run.confirmableSnapshot.expiresAt).toLocaleTimeString()}</p>
          <div className="actions">
            <button className="secondary" disabled={busy} onClick={() => act(() => decide(run, 'REJECT'))}>取消</button>
            <button disabled={busy} onClick={() => act(() => decide(run, 'APPROVE'))}>确认创建订单</button>
          </div>
        </article>
      )}

      {run.phase === 'COMPLETED' && run.finalOrder && (
        <article className="success">
          <h2>订单已创建</h2>
          <p>{run.finalOrder.orderId}</p>
          <strong>{run.finalOrder.status}</strong>
        </article>
      )}

      {run.phase === 'CANCELLED' && <article className="success"><h2>任务已取消</h2>
        <p>已释放本任务占用的库存，不会创建订单。</p></article>}
    </section>
  )
}
