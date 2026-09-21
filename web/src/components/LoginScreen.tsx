// 登录门面：视觉按电商登录页组织，认证方式仍是 OIDC Authorization Code + PKCE，
// 点击后跳转到身份服务（Keycloak）自带的账号密码表单，商城不接触密码。
export type LoginScreenProps = {
  onSignIn: () => void
  configurationError?: string
}

const HIGHLIGHTS = [
  { title: '价格与库存由商城确认', body: 'Agent 只负责搜索和比较，最终金额、预占和订单都由交易系统算。' },
  { title: '下单前人工确认', body: '选中商品后生成确认快照，锁定报价与库存，你点头才会创建订单。' },
  { title: '身份由统一认证签发', body: '登录走 OIDC 授权码 + PKCE，令牌带 issuer 与 audience，商城不保存密码。' },
]

export function LoginScreen({ onSignIn, configurationError }: LoginScreenProps) {
  if (configurationError) {
    return (
      <main className="login-page">
        <div className="login-card">
          <h1>无法登录</h1>
          <p className="error">{configurationError}</p>
        </div>
      </main>
    )
  }

  return (
    <main className="login-page">
      <section className="login-hero">
        <span className="brand-mark large" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M6 8h12l-1 12H7L6 8Z" strokeLinejoin="round" />
            <path d="M9 8V6a3 3 0 0 1 6 0v2" strokeLinecap="round" />
          </svg>
        </span>
        <h1>说清需求，<br />剩下的交给 AI 导购。</h1>
        <p className="login-lede">
          把预算、规格和到货时间一次说清楚，Agent 会搜索、比价、锁定库存，
          最后把一张可以核对的账单交给你确认。
        </p>
        <ul className="login-points">
          {HIGHLIGHTS.map((item) => (
            <li key={item.title}>
              <strong>{item.title}</strong>
              <span>{item.body}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="login-card">
        <h2>账号登录</h2>
        <p className="login-card-hint">新用户可在登录页直接注册，本地环境已关闭邮箱验证。</p>
        <button className="primary block" onClick={onSignIn}>登录 / 注册</button>
        <p className="login-card-foot">
          登录由统一身份服务（Keycloak · OIDC PKCE）完成，商城只接收签发给本应用的访问令牌。
        </p>
      </section>
    </main>
  )
}
