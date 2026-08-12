// 浏览器 OIDC Authorization Code + PKCE 配置；这里只保存短期会话，不接触 DeepSeek 密钥。
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

const authority = import.meta.env.VITE_OIDC_AUTHORITY
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID

export const authConfigurationError = !authority || !clientId
  ? '缺少 VITE_OIDC_AUTHORITY 或 VITE_OIDC_CLIENT_ID，无法安全登录。'
  : undefined

export const userManager = authConfigurationError ? undefined : new UserManager({
  authority,
  client_id: clientId,
  redirect_uri: `${window.location.origin}/auth/callback`,
  post_logout_redirect_uri: window.location.origin,
  response_type: 'code',
  scope: 'openid buyforu.api',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
})

export async function accessToken(): Promise<string> {
  // 所有 API 请求发送前都重新读取当前 access token，过期时不继续调用业务接口。
  if (!userManager) throw new Error(authConfigurationError)
  const user = await userManager.getUser()
  if (!user || user.expired) throw new Error('登录已过期，请重新登录。')
  return user.access_token
}
