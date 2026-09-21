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

// 报价由 Commerce 生成，页面只做展示，不重算任何金额。
export type QuoteView = {
  skuId: string
  quantity: number
  itemAmount: Money
  payableAmount: Money
  shippingFee: Money
  deliveryPromise: string
  observedAt?: string
  expiresAt?: string
  discounts: Array<{ code: string; description: string; amount: Money }>
}

// 用户批准时需要原样提交 snapshotId + summaryHash，前端不能修改其中金额。
export type Snapshot = {
  snapshotId: string
  summaryHash: string
  expiresAt: string
  quote: QuoteView
  reservation: { reservationId: string; status: string; expiresAt: string }
}

// 已提交订单：orderId 与 createdAt 来自 Commerce，状态流由交易系统推进。
export type OrderView = {
  orderId: string
  userId: string
  sourceSnapshotId: string
  reservationId: string
  status: string
  createdAt: string
  version: number
  quote: QuoteView
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
  finalOrder?: OrderView
  updatedAt?: string
  planSpec?: { clarification?: { required: boolean; question?: string } }
}

export type CommandAccepted = {
  commandId: string
  runId: string
  status: string
  queueClass: string
  acceptedAt: string
  deadlineAt: string
  eventUrl: string
  statusUrl: string
}

export type AgentCommand = CommandAccepted & {
  attempts: number
  errorCode?: string
  errorDetail?: string
}

// 订单状态取自 Commerce 的 OrderStatus 枚举，仅做中文展示。
const ORDER_STATUS_LABELS: Record<string, string> = {
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  FULFILLING: '备货中',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUND_PENDING: '退款中',
  REFUNDED: '已退款',
}

export function orderStatusLabel(status: string): string {
  return ORDER_STATUS_LABELS[status] ?? status
}
