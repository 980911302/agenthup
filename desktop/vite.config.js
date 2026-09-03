import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import fs from 'fs'
import crypto from 'crypto'

const backend = 'http://localhost:8080'
const proxy = {
  '/dev-api': {
    target: backend,
    changeOrigin: true,
    ws: true,
    rewrite: (p) => p.replace(/^\/dev-api/, ''),
    configure: (proxyReq) => {
      proxyReq.on('error', (err) => {
        if (err?.code === 'EPIPE' || err?.code === 'ECONNRESET' || err?.code === 'ECONNREFUSED') return
      })
    }
  },
  '/ws': {
    target: backend,
    changeOrigin: true,
    ws: true,
    configure: (proxyReq) => {
      proxyReq.on('error', (err) => {
        if (err?.code === 'EPIPE' || err?.code === 'ECONNRESET' || err?.code === 'ECONNREFUSED') return
      })
    }
  }
}

function clientToolsVersion() {
  const pkg = JSON.parse(fs.readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'))
  const src = fs.readFileSync(path.resolve(__dirname, 'src/chat/clientTools.js'), 'utf8')
  const hash = crypto.createHash('sha256').update(src).digest('hex').slice(0, 8)
  return pkg.version + '+' + hash
}

export default defineConfig({
  plugins: [vue()],
  define: {
    __CLIENT_TOOLS_VERSION__: JSON.stringify(clientToolsVersion())
  },
  // 生产环境作为站点主入口部署，开发环境同样保持根路径。
  base: '/',
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src')
    }
  },
  server: {
    port: 5175,
    host: true,
    proxy
  },
  preview: {
    port: 5175,
    host: true,
    proxy
  },
  css: {
    preprocessorOptions: {
      scss: {
        silenceDeprecations: ['legacy-js-api']
      }
    }
  }
})
