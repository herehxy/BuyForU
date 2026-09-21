// 商品陈列：按品类和关键词浏览当前可售 SKU。
// 这里只展示商城返回的库存事实，不计算价格、不直接下单；
// 卡片上的动作是把商品整理成一句自然语言需求交给 Agent，交易仍由 Commerce 决定。
import { useMemo, useState } from 'react'
import type { InventoryItem } from '../api'
import { CATEGORY_LABELS, categoryLabel, visualFor } from '../productImages'

export type ProductGridProps = {
  items: InventoryItem[]
  busy: boolean
  onAskAgent: (item: InventoryItem) => void
  onRefresh: () => void
}

type SortKey = 'default' | 'priceAsc' | 'priceDesc'

const SORTS: Array<{ key: SortKey; label: string }> = [
  { key: 'default', label: '综合' },
  { key: 'priceAsc', label: '价格低到高' },
  { key: 'priceDesc', label: '价格高到低' },
]

function stockLabel(item: InventoryItem) {
  if (item.availableQuantity <= 0) return { text: '暂时缺货', tone: 'out' }
  if (item.availableQuantity <= 3) return { text: `仅剩 ${item.availableQuantity} 件`, tone: 'low' }
  return { text: '现货', tone: 'in' }
}

export function ProductGrid({ items, busy, onAskAgent, onRefresh }: ProductGridProps) {
  const [category, setCategory] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [sort, setSort] = useState<SortKey>('default')

  const categories = useMemo(() => {
    const seen = new Map<string, number>()
    items.forEach((item) => seen.set(item.category, (seen.get(item.category) ?? 0) + 1))
    return Array.from(seen.entries())
  }, [items])

  const visible = useMemo(() => {
    const needle = keyword.trim().toLowerCase()
    const filtered = items.filter((item) => {
      if (category !== 'all' && item.category !== category) return false
      if (!needle) return true
      return `${item.brand} ${item.name} ${item.skuId}`.toLowerCase().includes(needle)
    })
    if (sort === 'priceAsc') return [...filtered].sort((a, b) => a.unitPrice.amount - b.unitPrice.amount)
    if (sort === 'priceDesc') return [...filtered].sort((a, b) => b.unitPrice.amount - a.unitPrice.amount)
    return filtered
  }, [items, category, keyword, sort])

  return (
    <section className="catalog">
      <div className="catalog-head">
        <div>
          <h2>今日可售</h2>
          <p className="muted">
            可售是现在还能买的数量，预占是已锁定但尚未下单的件数。下单后预占消失，可售不会加回。
          </p>
        </div>
        <div className="catalog-tools">
          <input className="filter-input" value={keyword} placeholder="筛选品牌或型号"
                 onChange={(event) => setKeyword(event.target.value)} aria-label="筛选商品" />
          <select value={sort} onChange={(event) => setSort(event.target.value as SortKey)} aria-label="排序方式">
            {SORTS.map((option) => <option key={option.key} value={option.key}>{option.label}</option>)}
          </select>
          <button type="button" className="ghost" onClick={onRefresh}>刷新库存</button>
        </div>
      </div>

      <div className="chip-row">
        <button type="button" className={category === 'all' ? 'chip on' : 'chip'}
                onClick={() => setCategory('all')}>全部 {items.length}</button>
        {categories.map(([name, count]) => (
          <button type="button" key={name} className={category === name ? 'chip on' : 'chip'}
                  onClick={() => setCategory(name)}>
            {CATEGORY_LABELS[name] ?? name} {count}
          </button>
        ))}
      </div>

      {visible.length === 0 ? (
        <p className="empty">没有符合条件的商品，换个关键词或切换品类试试。</p>
      ) : (
        <div className="product-grid">
          {visible.map((item) => {
            const visual = visualFor(item.skuId, item.category)
            const stock = stockLabel(item)
            const soldOut = item.availableQuantity <= 0
            return (
              <article className="product" key={item.skuId}>
                <div className="product-thumb">
                  <img src={visual.src} alt={item.name} loading="lazy"
                       onError={(event) => { event.currentTarget.style.display = 'none' }} />
                  <span className={`thumb-fallback ${visual.tone}`} aria-hidden="true">
                    {item.brand.slice(0, 1)}
                  </span>
                  <span className="product-cat">{categoryLabel(item.category)}</span>
                </div>
                <div className="product-body">
                  <span className="product-brand">{item.brand}</span>
                  <h3>{item.name}</h3>
                  <p className="product-meta">{item.skuId}</p>
                  <div className="product-price">
                    <strong className="price">¥{item.unitPrice.amount}</strong>
                  </div>
                  <div className="product-stock">
                    <span className={`stock ${stock.tone}`}>{stock.text}</span>
                    <span className="muted">预占 {item.reservedQuantity}</span>
                  </div>
                  <button className="block" disabled={busy || soldOut}
                          onClick={() => onAskAgent(item)}>
                    {soldOut ? '补货中' : '让 AI 帮我买'}
                  </button>
                </div>
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
