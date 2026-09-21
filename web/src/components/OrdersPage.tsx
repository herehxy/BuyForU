// 已提交订单页：展示交易系统里真实存在的订单，并提供「撤销待付款订单」这一项写操作。
//
// 关键边界：订单状态和金额的唯一权威在交易系统，页面既不推算状态流转，
// 也不把 Agent 运行快照里的旧状态当成当前状态。这里的每一项都来自 /api/v1/orders。
// 撤销同样不在这里判定能否成功：页面只决定按钮是否出现，是否放行由交易系统的条件更新决定。
import { useState } from 'react'
import type { InventoryItem } from '../api'
import type { Money, OrderView } from '../types'
import { orderStatusLabel } from '../types'
import { visualFor, categoryLabel } from '../productImages'

export type OrdersPageProps = {
  orders: OrderView[]
  stock: InventoryItem[]
  loading: boolean
  error?: string
  onRefresh: () => void
  onClose: () => void
  onCancelOrder: (orderId: string) => Promise<void>
  focusOrderId?: string
}

// 状态徽标配色按“待处理 → 进行中 → 完结”分组，不表达任何业务规则。
const STATUS_TONE: Record<string, string> = {
  PENDING_PAYMENT: 'pending',
  PAID: 'progress',
  FULFILLING: 'progress',
  SHIPPED: 'progress',
  COMPLETED: 'done',
  CANCELLED: 'closed',
  REFUND_PENDING: 'refund',
  REFUNDED: 'closed',
}

// 只有待付款可以撤销。这只是界面上的按钮可见性判断，真正的准入由交易系统的
// 条件更新（UPDATE ... WHERE status = 'PENDING_PAYMENT'）决定；非法迁移会被拒绝。
function canCancel(order: OrderView): boolean {
  return order.status === 'PENDING_PAYMENT'
}

function formatMoney(money: Money): string {
  const amount = Number(money.amount)
  const text = Number.isFinite(amount)
    ? amount.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : String(money.amount)
  return `¥${text}`
}

function formatTime(value: string): string {
  const at = new Date(value)
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('zh-CN')
}

function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`order-status tone-${STATUS_TONE[status] ?? 'closed'}`}>
      {orderStatusLabel(status)}
    </span>
  )
}

function OrderCard({ order, stock, expanded, cancelling, cancelError, onToggle, onCancel }: {
  order: OrderView
  stock: InventoryItem[]
  expanded: boolean
  cancelling: boolean
  cancelError?: string
  onToggle: () => void
  onCancel: () => void
}) {
  const item = stock.find((entry) => entry.skuId === order.quote.skuId)
  const visual = visualFor(order.quote.skuId, item?.category)
  // 目录里的 name 已含品牌（如 "Cedar Buds 2"），不要再拼一次 brand。
  const name = item ? item.name : `SKU ${order.quote.skuId}`
  const discounts = order.quote.discounts.reduce((sum, discount) => sum + Number(discount.amount.amount), 0)

  return (
    <article className={expanded ? 'order-card open' : 'order-card'}>
      <header className="order-head">
        <span className="order-no">订单号 {order.orderId}</span>
        <span className="order-time">下单时间 {formatTime(order.createdAt)}</span>
        <StatusBadge status={order.status} />
      </header>

      <div className="order-body">
        <div className="order-thumb">
          <img src={visual.src} alt={name} loading="lazy"
               onError={(event) => { event.currentTarget.style.display = 'none' }} />
          <span className={`thumb-fallback ${visual.tone}`} aria-hidden="true">{name.slice(0, 1)}</span>
        </div>
        <div className="order-item">
          <h4>{name}</h4>
          <p className="muted">
            {item && <>{categoryLabel(item.category)} · </>}
            SKU {order.quote.skuId} · 数量 {order.quote.quantity}
          </p>
          <p className="muted">预计 {order.quote.deliveryPromise} 送达</p>
        </div>
        <div className="order-amount">
          <span className="muted">应付</span>
          <strong className="price">{formatMoney(order.quote.payableAmount)}</strong>
          <button type="button" className="ghost" onClick={onToggle}>
            {expanded ? '收起明细' : '查看明细'}
          </button>
          {canCancel(order) && (
            <button type="button" className="ghost danger" disabled={cancelling} onClick={onCancel}>
              {cancelling ? '撤销中…' : '撤销订单'}
            </button>
          )}
        </div>
      </div>

      {cancelError && <p className="error">{cancelError}</p>}

      {expanded && (
        <div className="order-detail">
          <dl className="bill-lines">
            <div><dt>商品金额</dt><dd>{formatMoney(order.quote.itemAmount)}</dd></div>
            {order.quote.discounts.map((discount) => (
              <div key={discount.code}><dt>{discount.description}</dt>
                <dd className="minus">-{formatMoney(discount.amount)}</dd></div>
            ))}
            {order.quote.discounts.length === 0 && (
              <div><dt>优惠</dt><dd className="muted">无</dd></div>
            )}
            <div><dt>运费</dt><dd>{formatMoney(order.quote.shippingFee)}</dd></div>
            <div className="bill-total"><dt>应付合计</dt>
              <dd className="price">{formatMoney(order.quote.payableAmount)}</dd></div>
          </dl>
          <dl className="order-meta-lines">
            <div><dt>当前状态</dt><dd><StatusBadge status={order.status} /></dd></div>
            <div><dt>优惠合计</dt><dd>{discounts > 0 ? `-¥${discounts.toFixed(2)}` : '无'}</dd></div>
            <div><dt>库存预占</dt><dd>{order.reservationId}</dd></div>
            <div><dt>确认快照</dt><dd>{order.sourceSnapshotId}</dd></div>
            <div><dt>数据版本</dt><dd>v{order.version}</dd></div>
            {order.quote.observedAt && (
              <div><dt>报价时间</dt><dd>{formatTime(order.quote.observedAt)}</dd></div>
            )}
          </dl>
          <p className="order-note">
            状态与金额由交易系统维护，本页只做展示；Agent 运行记录里保存的只是下单时刻的快照。
            撤销订单只作用于订单聚合，不会回写历史运行记录。
          </p>
        </div>
      )}
    </article>
  )
}

export function OrdersPage(props: OrdersPageProps) {
  const [expanded, setExpanded] = useState<string | undefined>(props.focusOrderId)
  const [cancellingId, setCancellingId] = useState<string>()
  // 按订单号记录错误：撤销按钮在折叠状态下也可见，错误不能只在展开时才显示出来。
  const [cancelError, setCancelError] = useState<{ orderId: string; message: string }>()

  // 撤销不可逆，先让用户明确确认；能否真正撤销仍由服务端判定。
  const requestCancel = (order: OrderView) => {
    if (!window.confirm(`确认撤销订单 ${order.orderId}？撤销后预占库存会立即释放，该操作不可撤销。`)) return
    setCancellingId(order.orderId)
    setCancelError(undefined)
    props.onCancelOrder(order.orderId)
      .catch((failure: unknown) => {
        setCancelError({
          orderId: order.orderId,
          message: failure instanceof Error ? failure.message : '撤销失败，请刷新后重试。',
        })
      })
      .finally(() => setCancellingId(undefined))
  }

  return (
    <main className="orders">
      <div className="orders-head">
        <div>
          <h2>我的订单</h2>
          <p className="muted">
            共 {props.orders.length} 笔，来自交易系统当前状态；除撤销待付款订单外，本页不提供付款等写操作。
          </p>
        </div>
        <div className="orders-head-actions">
          <button type="button" className="secondary" disabled={props.loading} onClick={props.onRefresh}>
            {props.loading ? '刷新中…' : '刷新'}
          </button>
          <button type="button" className="ghost" onClick={props.onClose}>返回商城</button>
        </div>
      </div>

      {props.error && <p className="error">{props.error}</p>}

      {props.loading && props.orders.length === 0 && <p className="notice">正在读取订单…</p>}

      {!props.loading && !props.error && props.orders.length === 0 && (
        <section className="orders-empty">
          <h3>还没有已提交的订单</h3>
          <p className="muted">
            在商城选择商品并由 AI 导购走完「确认金额 → 创建订单」后，订单会出现在这里。
          </p>
          <button type="button" onClick={props.onClose}>去挑商品</button>
        </section>
      )}

      <div className="orders-list">
        {props.orders.map((order) => (
          <OrderCard
            key={order.orderId}
            order={order}
            stock={props.stock}
            expanded={expanded === order.orderId}
            cancelling={cancellingId === order.orderId}
            cancelError={cancelError?.orderId === order.orderId ? cancelError.message : undefined}
            onToggle={() => setExpanded(expanded === order.orderId ? undefined : order.orderId)}
            onCancel={() => requestCancel(order)}
          />
        ))}
      </div>
    </main>
  )
}
