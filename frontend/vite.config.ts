import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'
import { readFileSync } from 'fs'

// Read the canonical Maven product version, including direct Vite invocations.
const appVersion = readFileSync(path.resolve(__dirname, '../backend/pom.xml'), 'utf-8')
  .match(/<artifactId>investory<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1]
if (!appVersion || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(appVersion)) {
  throw new Error('Invalid Investory version in backend/pom.xml')
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '')
  return {
    define: {
      __APP_VERSION__: JSON.stringify(appVersion),
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
      outDir: mode === 'electron' ? '../desktop/www' : '../backend/src/main/resources/static',
      emptyOutDir: true,
    },
  }
})
