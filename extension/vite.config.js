import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { crx } from '@crxjs/vite-plugin'
import path from 'path'
import fs from 'fs'
import crypto from 'crypto'
import manifest from './manifest.json'

function clientToolsVersion() {
  const pkg = JSON.parse(fs.readFileSync(path.resolve(__dirname, 'package.json'), 'utf8'))
  const src = fs.readFileSync(path.resolve(__dirname, 'src/chat/clientTools.js'), 'utf8')
  const hash = crypto.createHash('sha256').update(src).digest('hex').slice(0, 8)
  return pkg.version + '+' + hash
}

export default defineConfig({
  plugins: [vue(), crx({ manifest })],
  define: {
    __CLIENT_TOOLS_VERSION__: JSON.stringify(clientToolsVersion())
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src')
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        silenceDeprecations: ['legacy-js-api']
      }
    }
  }
})
