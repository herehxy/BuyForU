/// <reference types="vite/client" />

/** 浏览器构建期允许读取的公开环境变量；这里绝不能声明服务端 API Key。 */
interface ImportMetaEnv {
  readonly VITE_OIDC_AUTHORITY: string
  readonly VITE_OIDC_CLIENT_ID: string
}

/** 扩展 Vite 的 import.meta 类型，使 OIDC 配置在 TypeScript 中有静态检查。 */
interface ImportMeta {
  readonly env: ImportMetaEnv
}
