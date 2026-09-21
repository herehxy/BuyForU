// 顶部导航：品牌、全局搜索、配送区域、任务入口和用户菜单。
// 搜索框输入的内容会作为一次自然语言购物需求提交给 Agent，前端不解析条件。
import type { DeliveryAddress } from '../api'

export type NavBarProps = {
  address?: DeliveryAddress
  zoneCode: string
  registering: boolean
  onZoneChange: (zoneCode: string) => void
  onRegisterZone: () => void
  query: string
  onQueryChange: (value: string) => void
  onSearch: () => void
  searchDisabled: boolean
  taskCount: number
  onOpenTasks: () => void
  orderCount: number
  onOpenOrders: () => void
  onLogout: () => void
  userLabel: string
}

const ZONES = [
  { code: 'CN-EAST', label: '华东 · 预计 1 天' },
  { code: 'CN-CENTRAL', label: '华中 · 预计 2 天' },
  { code: 'CN-WEST', label: '西部 · 预计 3 天' },
]

export function NavBar(props: NavBarProps) {
  return (
    <header className="topbar">
      <div className="topbar-inner">
        <a className="brand" href="/" onClick={(event) => event.preventDefault()}>
          <span className="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M6 8h12l-1 12H7L6 8Z" strokeLinejoin="round" />
              <path d="M9 8V6a3 3 0 0 1 6 0v2" strokeLinecap="round" />
            </svg>
          </span>
          <span className="brand-name">BuyForU</span>
          <span className="brand-suffix">AI 导购商城</span>
        </a>

        <form className="nav-search" onSubmit={(event) => { event.preventDefault(); props.onSearch() }}>
          <span className="nav-search-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" strokeLinecap="round" />
            </svg>
          </span>
          <input
            value={props.query}
            onChange={(event) => props.onQueryChange(event.target.value)}
            placeholder="描述你要买什么，例如：5000 元以内、16GB 内存、明天能到的轻薄本"
            aria-label="购物需求搜索"
          />
          <button type="submit" disabled={props.searchDisabled}>问 AI</button>
        </form>

        <nav className="nav-actions">
          <div className="zone-picker">
            {props.address ? (
              <span className="zone-chip" title={`地址编号 ${props.address.addressId}`}>
                <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M12 21s7-5.5 7-11a7 7 0 1 0-14 0c0 5.5 7 11 7 11Z" strokeLinejoin="round" />
                  <circle cx="12" cy="10" r="2.5" />
                </svg>
                {props.address.zoneCode} · {props.address.deliveryDays} 天达
              </span>
            ) : (
              <>
                <select value={props.zoneCode} onChange={(event) => props.onZoneChange(event.target.value)}
                        aria-label="选择配送区域">
                  {ZONES.map((zone) => <option key={zone.code} value={zone.code}>{zone.label}</option>)}
                </select>
                <button type="button" className="ghost" disabled={props.registering}
                        onClick={props.onRegisterZone}>
                  {props.registering ? '登记中…' : '设置收货地'}
                </button>
              </>
            )}
          </div>

          <button type="button" className="nav-icon-button" onClick={props.onOpenTasks}>
            最近任务{props.taskCount > 0 && <span className="badge">{props.taskCount}</span>}
          </button>

          <button type="button" className="nav-icon-button" onClick={props.onOpenOrders}>
            我的订单{props.orderCount > 0 && <span className="badge">{props.orderCount}</span>}
          </button>

          <div className="nav-user">
            <span className="avatar" aria-hidden="true">{props.userLabel.slice(0, 1).toUpperCase()}</span>
            <span className="nav-user-name">{props.userLabel}</span>
            <button type="button" className="ghost" onClick={props.onLogout}>退出</button>
          </div>
        </nav>
      </div>
    </header>
  )
}
