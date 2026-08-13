import type { AgentCommand, AgentRun, CommandAccepted } from './types'
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
  return request<CommandAccepted>(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey(op, fingerprint) },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).catch((error: unknown) => {
    sessionStorage.removeItem(`buyforu:idem:${op}:${fingerprint}`)
    throw error
  })
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

// 命令事件只说明调度，业务进度以 run.phase 为准。
export async function followRun(
  command: CommandAccepted,
  onProgress?: (update: ProgressUpdate) => void,
): Promise<AgentRun> {
  const publish = async (label: string) => {
    const run = await getRun(command.runId).catch(() => undefined)
    onProgress?.({ label: run ? phaseLabel(run.phase) : label, run })
    return run
  }

  let lastEventId = '0'
  for (let attempt = 0; attempt < 3; attempt++) {
    const token = await accessToken()
    const response = await fetch(command.eventUrl, {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'text/event-stream',
        'Last-Event-ID': lastEventId,
      },
    })
    if (!response.ok || !response.body) return pollUntilTerminal(command, onProgress)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const frames = buffer.split('\n\n')
        buffer = frames.pop() ?? ''
        for (const frame of frames) {
          const id = frame.split('\n').find((line) => line.startsWith('id:'))?.slice(3).trim()
          if (id) lastEventId = id
          const event = frame.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim()
          if (!event || event === 'heartbeat') continue
          const run = await publish(commandEventLabel(event))
          if (event === 'command.completed' || event === 'run.waiting-user' || event === 'command.cancelled') {
            return run ?? getRun(command.runId)
          }
          if (event === 'command.failed') {
            const failed = await getCommand(command.commandId)
            throw new Error(failed.errorDetail ?? failed.errorCode ?? 'Agent command failed')
          }
        }
      }
    } finally {
      reader.releaseLock()
    }
  }
  return pollUntilTerminal(command, onProgress)
}

async function pollUntilTerminal(
  command: CommandAccepted,
  onProgress?: (update: ProgressUpdate) => void,
): Promise<AgentRun> {
  let delay = 500
  while (Date.now() < new Date(command.deadlineAt).getTime() + 5000) {
    const current = await getCommand(command.commandId)
    const run = await getRun(command.runId).catch(() => undefined)
    onProgress?.({ label: run ? phaseLabel(run.phase) : commandEventLabel(`command.${current.status.toLowerCase()}`), run })
    if (current.status === 'SUCCEEDED' || current.status === 'WAITING_USER' || current.status === 'CANCELLED') {
      return run ?? getRun(command.runId)
    }
    if (current.status === 'FAILED' || current.status === 'EXPIRED') {
      throw new Error(current.errorDetail ?? current.errorCode ?? 'Agent command failed')
    }
    await new Promise((resolve) => setTimeout(resolve, delay))
    delay = Math.min(delay * 1.5, 5000)
  }
  throw new Error('Agent command exceeded its deadline')
}

export function phaseLabel(phase: string): string {
  return PHASE_LABELS[phase] ?? phase
}

const PHASE_LABELS: Record<string, string> = {
  NEW: '已接收需求',
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

function commandEventLabel(event: string): string {
  switch (event) {
    case 'command.accepted': return '任务已排队，等待执行'
    case 'command.started': return 'Agent 开始处理'
    case 'command.retry-wait': return '下游繁忙，稍后重试'
    case 'command.completed': return '这一步已完成'
    case 'run.waiting-user': return '等待你操作'
    case 'command.cancelled': return '任务已取消'
    case 'command.failed': return '处理失败'
    default: return '正在处理…'
  }
}
