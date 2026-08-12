// Agent API 的前端只读视图类型；权威领域模型仍定义在 Java commerce-port 中。
export type Money = { amount: number; currency: string }

export type Candidate = {
  productId: string
  skuId: string
  name: string
  brand: string
  attributes: Record<string, string>
  displayPrice: Money
  available: boolean
  deliveryDate: string
}

// 用户批准时需要原样提交 snapshotId + summaryHash，前端不能修改其中金额。
export type Snapshot = {
  snapshotId: string
  summaryHash: string
  expiresAt: string
  quote: {
    skuId: string
    quantity: number
    itemAmount: Money
    payableAmount: Money
    shippingFee: Money
    deliveryPromise: string
    discounts: Array<{ code: string; description: string; amount: Money }>
  }
  reservation: { reservationId: string; status: string; expiresAt: string }
}

// phase 决定页面允许显示的操作，服务端仍会再次校验状态转换。
export type AgentRun = {
  runId: string
  originalRequest: string
  phase: string
  candidateSet: Candidate[]
  selectedCandidateIndex: number
  confirmableSnapshot?: Snapshot
  lastError?: string
  finalOrder?: { orderId: string; status: string }
  planSpec?: { clarification?: { required: boolean; question?: string } }
}
