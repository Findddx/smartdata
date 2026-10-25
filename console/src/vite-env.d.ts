/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ENABLE_MOCK_FALLBACK?: string
  readonly VITE_DEMO_AUTH?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
