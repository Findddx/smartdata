import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  preview: {
    port: 4173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
      '/oauth2': { target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080', changeOrigin: false },
      '/login': { target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080', changeOrigin: false },
      '/logout': { target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080',
        changeOrigin: false,
      },
      '/oauth2': { target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080', changeOrigin: false },
      '/login': { target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080', changeOrigin: false },
      '/logout': { target: process.env.SMARTDATA_API_ORIGIN ?? 'http://127.0.0.1:8080', changeOrigin: false },
    },
  },
  build: {
    target: 'es2023',
    sourcemap: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: true,
    pool: 'threads',
    maxWorkers: 1,
  },
})
