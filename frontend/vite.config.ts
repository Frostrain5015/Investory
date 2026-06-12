import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'
import { readFileSync } from 'fs'

const pkg = JSON.parse(readFileSync(path.resolve(__dirname, 'package.json'), 'utf-8')) as { version: string }

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '')
  return {
    define: {
      __APP_VERSION__: JSON.stringify(pkg.version),
    },
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: 'https://localhost:8443',
          changeOrigin: true,
          secure: false,
        },
        '/oauth': {
          target: 'https://localhost:8443',
          changeOrigin: true,
          secure: false,
        },
      },
    },
    base: env.VITE_BASE || '/',
    build: {
      outDir: mode === 'electron' ? '../desktop/dist' : '../backend/src/main/resources/static',
      emptyOutDir: true,
    },
  }
})
