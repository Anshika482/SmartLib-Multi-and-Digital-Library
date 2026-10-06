/// <reference types="vite/client" />

/**
 * The environment values this client reads, declared so a typo is a build
 * error rather than an undefined at runtime.
 *
 * Everything here reaches the browser bundle, so nothing secret belongs in a
 * VITE_ variable - these are an address and a dev-time proxy target.
 */
interface ImportMetaEnv {
  /** Where the API is in a built deployment. Empty in dev, where Vite proxies /api. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
