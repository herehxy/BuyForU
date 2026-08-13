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
    throw new Error(requestId ? `${detail}（请求编号：${requestId}）` : detail)
  }
  return response.json() as Promise<T>
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
    return accepted
  }).catch((error: unknown) => {
    sessionStorage.removeItem(slot)
    throw error
  })
}

function finishIdempotencySlot(commandId: string, allowNewAttempt: boolean): void {
  const commandSlot = `buyforu:command-slot:${commandId}`
  const slot = sessionStorage.getItem(commandSlot)
  if (allowNewAttempt && slot) sessionStorage.removeItem(slot)
  sessionStorage.removeItem(commandSlot)
}

export function registerAddress(zoneCode: string): Promise<DeliveryAddress> {
  return request('/api/v1/addresses', {
    method: 'POST', body: JSON.stringify({ zoneCode, idempotencyKey: crypto.randomUUID() }),
  })
}

export function startRun(message: string, addressId: string): Promise<CommandAccepted> {
  const conversationId = crypto.randomUUID()
  return command('/api/v1/runs', 'start', `${conversationId}:${addressId}:${message}`, {
      conversationId,
      message,
      addressId,
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

// 完成与否看这一条命令的 status。SSE 会回放同一 run 的旧 waiting-user，不能当“这次点继续已经结束”。
export async function followRun(
  command: CommandAccepted,
  onProgress?: (update: ProgressUpdate) => void,
): Promise<AgentRun> {
  let delay = 400
  while (Date.now() < new Date(command.deadlineAt).getTime() + 5000) {
    const current = await getCommand(command.commandId)
    const run = await getRun(command.runId).catch(() => undefined)
    onProgress?.({ label: progressLabel(current.status, run), run })
    if (current.status === 'SUCCEEDED' || current.status === 'WAITING_USER' || current.status === 'CANCELLED') {
      finishIdempotencySlot(command.commandId, false)
      return run ?? getRun(command.runId)
    }
    if (current.status === 'FAILED' || current.status === 'EXPIRED') {
      finishIdempotencySlot(command.commandId, true)
      throw new Error(current.errorDetail ?? current.errorCode ?? 'Agent command failed')
    }
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
