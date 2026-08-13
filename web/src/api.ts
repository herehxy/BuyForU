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

function command(path: string, body?: unknown): Promise<CommandAccepted> {
  return request(path, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

export function registerAddress(zoneCode: string): Promise<DeliveryAddress> {
  return request('/api/v1/addresses', {
    method: 'POST', body: JSON.stringify({ zoneCode, idempotencyKey: crypto.randomUUID() }),
  })
}

export function startRun(message: string, addressId: string): Promise<CommandAccepted> {
  return command('/api/v1/runs', {
      conversationId: crypto.randomUUID(),
      message,
      addressId,
  })
}

export function selectCandidate(runId: string, skuId: string): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/selection`, { skuId })
}

export function clarify(runId: string, message: string): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/clarifications`, { message })
}

export function relaxConstraints(runId: string, message: string, fields: string[]): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/constraint-relaxations`, { message, fields })
}

export function cancelRun(runId: string): Promise<CommandAccepted> {
  return command(`/api/v1/runs/${runId}/cancellations`)
}

export function decide(run: AgentRun, decision: 'APPROVE' | 'REJECT'): Promise<CommandAccepted> {
  const snapshot = run.confirmableSnapshot!
  return command(`/api/v1/runs/${run.runId}/approvals`, {
      decision,
      snapshotId: snapshot.snapshotId,
      expectedSummaryHash: snapshot.summaryHash,
  })
}

// 使用 fetch 流而非 EventSource，确保 OIDC Bearer Token 只放在请求头中。
export async function followRun(command: CommandAccepted, onProgress?: (event: string) => void): Promise<AgentRun> {
  const token = await accessToken()
  const response = await fetch(command.eventUrl, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
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
        const event = frame.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim()
        if (event) onProgress?.(event)
        if (event === 'command.completed' || event === 'run.waiting-user' || event === 'command.cancelled') {
          return getRun(command.runId)
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
  return pollUntilTerminal(command, onProgress)
}

async function pollUntilTerminal(command: CommandAccepted, onProgress?: (event: string) => void): Promise<AgentRun> {
  let delay = 500
  while (Date.now() < new Date(command.deadlineAt).getTime() + 5000) {
    const current = await getCommand(command.commandId)
    onProgress?.(`command.${current.status.toLowerCase()}`)
    if (current.status === 'SUCCEEDED' || current.status === 'WAITING_USER' || current.status === 'CANCELLED') {
      return getRun(command.runId)
    }
    if (current.status === 'FAILED' || current.status === 'EXPIRED') {
      throw new Error(current.errorDetail ?? current.errorCode ?? 'Agent command failed')
    }
    await new Promise((resolve) => setTimeout(resolve, delay))
    delay = Math.min(delay * 1.5, 5000)
  }
  throw new Error('Agent command exceeded its deadline')
}
