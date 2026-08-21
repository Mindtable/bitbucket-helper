import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import type { ProxyOptions } from 'vite'

const backendProxy: ProxyOptions = {
  target: 'http://127.0.0.1:8080',
  changeOrigin: true,
  configure: (proxy) => {
    proxy.on('proxyReq', (proxyRequest) => {
      proxyRequest.setHeader('Origin', 'http://127.0.0.1:8080')
    })
  },
}

export default {
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  server: {
    proxy: {
      '/api/v1': {
        ...backendProxy,
      },
    },
  },
}
