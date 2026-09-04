import type { AgentCommand, AgentRun, CommandAccepted, Money } from './types'
import { accessToken } from './auth'

// 唯一的前端请求入口：统一附加 OIDC access token，并把服务端 requestId 带入可见错误。
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = await accessToken()
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`, ...init?.headers },
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}))
    const requestId = problem.requestId ?? response.headers.get('X-Request-Id')
    const detail = problem.detail ?? `Request failed: ${response.status}`
    throw new ApiError(response.status, requestId ? `${detail}（请求编号：${requestId}）` : detail)
  }
  return response.json() as Promise<T>
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

export type DeliveryAddress = { addressId: string; zoneCode: string; deliveryDays: number }

export function listAddresses(): Promise<DeliveryAddress[]> {
  return request('/api/v1/addresses')
}

export type InventoryItem = {
  skuId: string
  name: string
  brand: string
  category: string
  unitPrice: Money
  availableQuantity: number
  reservedQuantity: number
}

export function listInventory(): Promise<InventoryItem[]> {
  return request('/api/v1/inventory')
}

export function listRuns(): Promise<AgentRun[]> {
  return request('/api/v1/runs')
}

export function getRun(runId: string): Promise<AgentRun> {
  return request(`/api/v1/runs/${runId}`)
}

export function getCommand(commandId: string): Promise<AgentCommand> {
  return request(`/api/v1/commands/${commandId}`)
}

// 同一操作复用同一把 key，刷新/连点不会变成两条命令。失败后再换 key。
function idempotencyKey(op: string, fingerprint: string): string {
  const store = sessionStorage
  const slot = `buyforu:idem:${op}:${fingerprint}`
  const existing = store.getItem(slot)
  if (existing) return existing
  const created = crypto.randomUUID()
  store.setItem(slot, created)
  return created
}

function command(path: string, op: string, fingerprint: string, body?: unknown): Promise<CommandAccepted> {
  const slot = `buyforu:idem:${op}:${fingerprint}`
  return request<CommandAccepted>(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey(op, fingerprint) },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).then((accepted) => {
    // 记录 command 与幂等槽的关系；后台执行失败后，下一次人工重试必须生成新 key，
    // 不能永远重放已经 FAILED 的旧命令。
    sessionStorage.setItem(`buyforu:command-slot:${accepted.commandId}`, slot)
    sessionStorage.setItem(ACTIVE_COMMAND_KEY, JSON.stringify(accepted))
    return accepted
  }).catch((error: unknown) => {
    // 网络中断或 5xx 时服务端可能已经持久化命令，必须保留同一 key 供安全重试。
    // 明确的 4xx 表示请求未被正常接纳，用户修改后可以使用新 key。
    if (error instanceof ApiError && error.status < 500) sessionStorage.removeItem(slot)
    throw error
  })
}

const ACTIVE_COMMAND_KEY = 'buyforu:active-command'

function finishIdempotencySlot(commandId: string, allowNewAttempt: boolean): void {
  const commandSlot = `buyforu:command-slot:${commandId}`
  const slot = sessionStorage.getItem(commandSlot)
  if (allowNewAttempt && slot) sessionStorage.removeItem(slot)
  sessionStorage.removeItem(commandSlot)
  sessionStorage.removeItem(`buyforu:last-event:${commandId}`)
  const active = pendingCommand()
  if (active?.commandId === commandId) sessionStorage.removeItem(ACTIVE_COMMAND_KEY)
}

export function registerAddress(zoneCode: string): Promise<DeliveryAddress> {
  return request('/api/v1/addresses', {
    method: 'POST', body: JSON.stringify({ zoneCode, idempotencyKey: crypto.randomUUID() }),
  })
}

export function startRun(message: string, addressId: string): Promise<CommandAccepted> {
  const fingerprint = `${addressId}\u001f${message}`
  const draftKey = 'buyforu:start-draft'
  let draft: { fingerprint: string; conversationId: string } | undefined
  try { draft = JSON.parse(sessionStorage.getItem(draftKey) ?? '') } catch { draft = undefined }
  if (!draft || draft.fingerprint !== fingerprint) {
    draft = { fingerprint, conversationId: crypto.randomUUID() }
    sessionStorage.setItem(draftKey, JSON.stringify(draft))
  }
  const conversationId = draft.conversationId
  return command('/api/v1/runs', 'start', `${conversationId}:${addressId}:${message}`, {
      conversationId,
      message,
      addressId,
  }).then((accepted) => {
    // 只有确认拿到 202 后才结束 draft；响应丢失时仍保留原 conversationId 和幂等键。
    sessionStorage.removeItem(draftKey)
    return accepted
  })
}

export function selectCandidate(runId: string, skuId: string): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/selection`, 'select', `${runId}:${skuId}`, { skuId })
}

export function clarify(runId: string, message: string): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/clarifications`, 'clarify', `${runId}:${message}`, { message })
}

export function relaxConstraints(runId: string, message: string, fields: string[]): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/constraint-relaxations`, 'relax',
    `${runId}:${fields.slice().sort().join(',')}:${message}`, { message, fields })
}

export function cancelRun(runId: string): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/cancellations`, 'cancel', runId)
}

export function decide(run: AgentRun, decision: 'APPROVE' | 'REJECT'): Promise<CommandAccepted> {
  const snapshot = run.confirmableSnapshot!
  return command(`/api/v1/runs/${run.runId}/approvals`, decision.toLowerCase(),
    `${run.runId}:${decision}:${snapshot.snapshotId}`, {
      decision,
      snapshotId: snapshot.snapshotId,
      expectedSummaryHash: snapshot.summaryHash,
  })
}

export type ProgressUpdate = { label: string; run?: AgentRun }

export function pendingCommand(): CommandAccepted | undefined {
  try {
    const stored = sessionStorage.getItem(ACTIVE_COMMAND_KEY)
    return stored ? JSON.parse(stored) as CommandAccepted : undefined
  } catch {
    sessionStorage.removeItem(ACTIVE_COMMAND_KEY)
    return undefined
  }
}

// 完成与否看这一条命令的 status。SSE 会回放同一 run 的旧 waiting-user，不能当“这次点继续已经结束”。
export async function followRun(
  command: CommandAccepted,
  onProgress?: (update: ProgressUpdate) => void,
): Promise<AgentRun> {
  const deadline = new Date(command.deadlineAt).getTime() + 5000

  const refresh = async (): Promise<{ done: boolean; run?: AgentRun }> => {
    const current = await getCommand(command.commandId)
    const run = await getRun(command.runId).catch(() => undefined)
    onProgress?.({ label: progressLabel(current.status, run), run })
    if (current.status === 'SUCCEEDED' || current.status === 'WAITING_USER' || current.status === 'CANCELLED') {
      finishIdempotencySlot(command.commandId, false)
      return { done: true, run: run ?? await getRun(command.runId) }
    }
    if (current.status === 'FAILED' || current.status === 'EXPIRED') {
      finishIdempotencySlot(command.commandId, true)
      throw new Error(current.errorDetail ?? current.errorCode ?? 'Agent command failed')
    }
    return { done: false, run }
  }

  // fetch 流允许附加 Bearer Token。reader.read() 增加 15 秒软超时：如果开发代理缓冲 SSE，
  // 就关闭该流并切换轮询，不让页面永久堵在一次读取上。
  const lastEventKey = `buyforu:last-event:${command.commandId}`
  const lastEventId = sessionStorage.getItem(lastEventKey) ?? '0'
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    const token = await accessToken()
    const response = await fetch(command.eventUrl, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream', 'Last-Event-ID': lastEventId },
    })
    if (response.ok && response.body) reader = response.body.getReader()
  } catch {
    // 建连失败时直接进入下方指数退避轮询；命令事实仍在 PostgreSQL。
  }

  if (reader) {
    const decoder = new TextDecoder()
    let buffer = ''
    while (Date.now() < deadline) {
      let read: ReadableStreamReadResult<Uint8Array> | undefined
      try {
        read = await Promise.race([
          reader.read(),
          new Promise<undefined>((resolve) => setTimeout(() => resolve(undefined), 15_000)),
        ])
      } catch {
        break
      }
      if (!read) break
      if (read?.done) break
      if (read?.value) {
        buffer += decoder.decode(read.value, { stream: true }).replace(/\r\n/g, '\n')
        const frames = buffer.split('\n\n')
        buffer = frames.pop() ?? ''
        // 事件内容只用于判断是否属于当前 command，真正状态仍从权威查询接口读取。
        const relevant = frames.some((frame) => {
          const idLine = frame.split('\n').find((line) => line.startsWith('id:'))
          if (idLine) {
            const eventId = idLine.slice(3).trim()
            if (eventId) sessionStorage.setItem(lastEventKey, eventId)
          }
          const data = frame.split('\n').filter((line) => line.startsWith('data:'))
            .map((line) => line.slice(5).trim()).join('\n')
          if (!data) return false
          try {
            const envelope = JSON.parse(data) as { commandId?: string }
            return !envelope.commandId || envelope.commandId === command.commandId
          } catch {
            return true
          }
        })
        if (!relevant) continue
      }
      // 新事件到达后只通过权威查询接口确认命令状态。
      const state = await refresh()
      if (state.done) {
        await reader.cancel().catch(() => undefined)
        return state.run!
      }
    }
    await reader.cancel().catch(() => undefined)
  }

  let delay = 500
  while (Date.now() < deadline) {
    const state = await refresh()
    if (state.done) return state.run!
    await new Promise((resolve) => setTimeout(resolve, delay))
    delay = Math.min(Math.round(delay * 1.4), 2000)
  }
  throw new Error('Agent command exceeded its deadline')
}

function progressLabel(status: string, run?: AgentRun): string {
  if (status === 'RETRY_WAIT') return '下游繁忙，稍后重试'
  if (status === 'QUEUED') return '任务已排队，等待执行'
  if (status === 'RUNNING') {
    if (run?.phase === 'NEEDS_CLARIFICATION') return '正在根据补充信息重新规划'
    if (run?.phase === 'NEW') return '正在理解需求并规划'
    return 'Agent 正在处理'
  }
  return run ? phaseLabel(run.phase) : '正在处理…'
}

export function phaseLabel(phase: string): string {
  return PHASE_LABELS[phase] ?? phase
}

const PHASE_LABELS: Record<string, string> = {
  NEW: '正在理解需求并规划',
  NEEDS_CLARIFICATION: '需要你补充信息',
  SEARCHING: '正在搜索商品',
  PRESENTING_CANDIDATES: '请选择商品',
  PREPARING_CONFIRMABLE_ORDER: '正在锁定库存并报价',
  WAITING_APPROVAL: '请确认订单和金额',
  CREATING_ORDER: '正在创建订单',
  NEEDS_CONSTRAINT_RELAXATION: '当前条件没有合适商品',
  COMPLETED: '订单已创建',
  CANCELLED: '任务已取消',
  FAILED: '处理失败',
}
