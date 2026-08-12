import type { AgentRun } from './types'
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

export function registerAddress(zoneCode: string): Promise<DeliveryAddress> {
  return request('/api/v1/addresses', {
    method: 'POST', body: JSON.stringify({ zoneCode, idempotencyKey: crypto.randomUUID() }),
  })
}

export function startRun(message: string, addressId: string): Promise<AgentRun> {
  return request('/api/v1/runs', {
    method: 'POST',
    body: JSON.stringify({
      conversationId: crypto.randomUUID(),
      idempotencyKey: crypto.randomUUID(),
      message,
      addressId,
    }),
  })
}

export function selectCandidate(runId: string, skuId: string): Promise<AgentRun> {
  return request(`/api/v1/runs/${runId}/selection`, {
    method: 'POST', body: JSON.stringify({ skuId }),
  })
}

export function clarify(runId: string, message: string): Promise<AgentRun> {
  return request(`/api/v1/runs/${runId}/clarifications`, {
    method: 'POST', body: JSON.stringify({ message }),
  })
}

export function relaxConstraints(runId: string, message: string): Promise<AgentRun> {
  return request(`/api/v1/runs/${runId}/constraint-relaxations`, {
    method: 'POST', body: JSON.stringify({ message }),
  })
}

export function cancelRun(runId: string): Promise<AgentRun> {
  return request(`/api/v1/runs/${runId}/cancellations`, { method: 'POST' })
}

export function decide(run: AgentRun, decision: 'APPROVE' | 'REJECT'): Promise<AgentRun> {
  const snapshot = run.confirmableSnapshot!
  return request(`/api/v1/runs/${run.runId}/approvals`, {
    method: 'POST',
    body: JSON.stringify({
      decision,
      snapshotId: snapshot.snapshotId,
      expectedSummaryHash: snapshot.summaryHash,
    }),
  })
}
