import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '')
  return {
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
