// 页面编排：登录门面、商城外壳（导航 + 商品陈列）和 AI 导购面板。
// 业务逻辑保持原有边界：需求以自然语言提交，价格与订单只由 Commerce 决定。
import { useEffect, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { followRun, cancelOrder, getRun, listAddresses, listInventory, listOrders, listRuns, pendingCommand, registerAddress, startRun } from './api'
import type { DeliveryAddress, InventoryItem } from './api'
import type { AgentRun, CommandAccepted, OrderView } from './types'
import type { User } from 'oidc-client-ts'
import { authConfigurationError, userManager } from './auth'
import { AgentPanel } from './components/AgentPanel'
import { LoginScreen } from './components/LoginScreen'
import { NavBar } from './components/NavBar'
import { OrdersPage } from './components/OrdersPage'
import { ProductGrid } from './components/ProductGrid'
import { IMAGE_SOURCE_NOTE } from './productImages'

let callbackInFlight: Promise<User> | undefined

// React StrictMode 在开发环境会重复执行 effect；共享 Promise 防止 OIDC callback 被消费两次。
function completeSignInCallback(): Promise<User> {
  callbackInFlight ??= userManager!.signinRedirectCallback().finally(() => {
    callbackInFlight = undefined
  })
  return callbackInFlight
}

const DEFAULT_REQUIREMENT = '帮我找一台 5000 元以内、16GB 内存、明天能到的轻薄本'

export function App() {
  const [user, setUser] = useState<User | null>()
  const [requirement, setRequirement] = useState(DEFAULT_REQUIREMENT)
  const [query, setQuery] = useState('')
  const [zone, setZone] = useState('CN-EAST')
  const [address, setAddress] = useState<DeliveryAddress>()
  const [run, setRun] = useState<AgentRun>()
  const [recentRuns, setRecentRuns] = useState<AgentRun[]>([])
  const [restoreError, setRestoreError] = useState<string>()
  const [actionNotice, setActionNotice] = useState<string>()
  const [progress, setProgress] = useState<string>()
  const [stock, setStock] = useState<InventoryItem[]>([])
  const [showTasks, setShowTasks] = useState(false)
  const [showOrders, setShowOrders] = useState(false)
  const [orders, setOrders] = useState<OrderView[]>([])
  const [ordersLoading, setOrdersLoading] = useState(false)
  const [ordersError, setOrdersError] = useState<string>()
  const [focusOrderId, setFocusOrderId] = useState<string>()
  const restoredCommand = useRef<string | undefined>(undefined)
  const refreshStock = () => listInventory().then(setStock).catch(() => undefined)

  // 订单列表每次都回到交易系统读取；失败时保留上一次结果并展示错误，不清空页面。
  const refreshOrders = () => {
    setOrdersLoading(true)
    return listOrders()
      .then((fetched) => { setOrders(fetched); setOrdersError(undefined) })
      .catch((failure: unknown) => {
        setOrdersError(failure instanceof Error ? failure.message : '无法读取订单列表。')
      })
      .finally(() => setOrdersLoading(false))
  }

  const openOrders = (orderId?: string) => {
    setShowTasks(false)
    setShowOrders(true)
    setFocusOrderId(orderId)
    void refreshOrders()
  }

  // 撤销走订单聚合，不经过购物图、不占用命令队列；成功后订单状态与可售库存都变了，
  // 因此两个视图都必须回到交易系统重读，不能用本地推算的结果代替。
  const cancelOrderById = (orderId: string) => {
    setOrdersError(undefined)
    return cancelOrder(orderId)
      .then(() => refreshStock())
      .then(() => refreshOrders())
  }
  const mutation = useMutation({
    mutationFn: async (action: () => Promise<CommandAccepted>) => {
      const accepted = await action()
      setProgress('任务已排队，等待执行')
      return followRun(accepted, (update) => {
        setProgress(update.label)
        if (update.run) setRun(update.run)
      })
    },
    onSuccess: (result) => {
      setRun(result)
      refreshStock()
    },
  })
  const addressMutation = useMutation({ mutationFn: registerAddress, onSuccess: setAddress })

  // 登录后从服务端恢复地址和最近任务，页面刷新不丢失人工等待状态。
  useEffect(() => {
    if (!user) return
    Promise.all([listAddresses(), listRuns(), listInventory()]).then(([addresses, runs, items]) => {
      setAddress(addresses[0])
      setRecentRuns(runs)
      setStock(items)
      setRestoreError(undefined)
      const pending = pendingCommand()
      if (pending && restoredCommand.current !== pending.commandId) {
        restoredCommand.current = pending.commandId
        // 刷新页面后继续跟踪已经接纳的命令，而不是重新提交同一个业务动作。
        mutation.mutate(() => Promise.resolve(pending))
      }
    }).catch((failure: unknown) => {
      setRestoreError(failure instanceof Error ? failure.message : '无法恢复已有任务和配送地址。')
    })
  }, [user])

  // 登录后拉一次订单数用于导航角标；失败不影响主流程。
  useEffect(() => {
    if (user) void refreshOrders()
  }, [user])

  useEffect(() => {
    if (!userManager) {
      setUser(null)
      return
    }

    if (window.location.pathname === '/auth/callback') {
      completeSignInCallback()
        .then((authenticated) => {
          window.history.replaceState({}, '', '/')
          setUser(authenticated)
        })
        .catch(() => userManager!.getUser()
          .then((authenticated) => setUser(authenticated?.expired ? null : authenticated)))
      return
    }
    // 退出后 Keycloak 回到站点根路径，清掉回调参数再读本地会话。
    userManager.signoutRedirectCallback().catch(() => undefined).finally(() => {
      window.history.replaceState({}, '', '/')
      userManager!.getUser().then((authenticated) => setUser(authenticated?.expired ? null : authenticated))
    })
  }, [])

  if (authConfigurationError) {
    return <LoginScreen onSignIn={() => undefined} configurationError={authConfigurationError} />
  }
  if (user === undefined) return <main className="boot">正在检查登录状态…</main>
  if (!user) return <LoginScreen onSignIn={() => userManager!.signinRedirect()} />

  const userLabel = user.profile?.preferred_username ?? user.profile?.name ?? user.profile?.email ?? '已登录用户'

  // 所有购物需求都以自然语言提交给 Agent；前端不解析条件、不计算金额。
  const startWith = (message: string) => {
    if (!address) {
      setActionNotice('请先在顶部设置收货地，Agent 需要它才能计算到货时间。')
      return
    }
    setActionNotice(undefined)
    setShowTasks(false)
    mutation.mutate(() => startRun(message, address.addressId))
  }

  const errors = [actionNotice, restoreError, addressMutation.error?.message, mutation.error?.message]
    .filter((message): message is string => Boolean(message))

  return (
    <div className="shop">
      <NavBar
        address={address}
        zoneCode={zone}
        registering={addressMutation.isPending}
        onZoneChange={setZone}
        onRegisterZone={() => addressMutation.mutate(zone)}
        query={query}
        onQueryChange={setQuery}
        onSearch={() => {
          if (!query.trim()) return
          setRequirement(query.trim())
          startWith(query.trim())
        }}
        searchDisabled={mutation.isPending || !query.trim()}
        taskCount={recentRuns.length}
        onOpenTasks={() => { setShowOrders(false); setShowTasks(true) }}
        orderCount={orders.length}
        onOpenOrders={() => openOrders()}
        onLogout={() => userManager!.signoutRedirect()}
        userLabel={userLabel}
      />

      {showOrders ? (
        <OrdersPage
          orders={orders}
          stock={stock}
          loading={ordersLoading}
          error={ordersError}
          focusOrderId={focusOrderId}
          onRefresh={refreshOrders}
          onCancelOrder={cancelOrderById}
          onClose={() => { setShowOrders(false); setFocusOrderId(undefined) }}
        />
      ) : (
        <main className="shop-main">
          <ProductGrid
            items={stock}
            busy={mutation.isPending}
            onAskAgent={(item) => startWith(`帮我买 ${item.name}`)}
            onRefresh={refreshStock}
          />

          <AgentPanel
            address={address}
            requirement={requirement}
            onRequirementChange={setRequirement}
            onSubmit={() => { if (requirement.trim()) startWith(requirement.trim()) }}
            busy={mutation.isPending}
            hint={progress}
            run={run}
            errors={errors}
            act={mutation.mutate}
            recentRuns={recentRuns}
            showTasks={showTasks}
            onOpenRun={(item) => {
              setShowTasks(false)
              getRun(item.runId).then(setRun).catch(() => setRun(item))
            }}
            onOpenTasks={() => setShowTasks(true)}
            onCloseTasks={() => setShowTasks(false)}
            onViewOrders={() => openOrders(run?.finalOrder?.orderId)}
          />
        </main>
      )}

      <footer className="foot">
        <span>{IMAGE_SOURCE_NOTE}</span>
        <span>金额、优惠、库存预占与订单创建只由交易系统决定，Agent 和页面都不参与计算。</span>
      </footer>
    </div>
  )
}
