import { fileURLToPath, URL } from 'node:url'
import { mkdir, writeFile } from 'node:fs/promises'

import { defineConfig } from 'vite'
import type { Plugin } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import { sites } from '@openai/sites-vite-plugin'

function staticSpaWorker(): Plugin {
  return {
    name: 'autosense-static-spa-worker',
    apply: 'build',
    async closeBundle() {
      const serverDirectory = fileURLToPath(new URL('./dist/server/', import.meta.url))
      await mkdir(serverDirectory, { recursive: true })
      await writeFile(
        fileURLToPath(new URL('./dist/server/index.js', import.meta.url)),
        `export default {
  async fetch(request, env) {
    const response = await env.ASSETS.fetch(request)
    const acceptsHtml = request.headers.get('Accept')?.includes('text/html')
    if (response.status !== 404 || request.method !== 'GET' || !acceptsHtml) return response
    const indexUrl = new URL('/index.html', request.url)
    return env.ASSETS.fetch(new Request(indexUrl, request))
  },
}\n`,
        'utf8',
      )
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
    sites(),
    staticSpaWorker(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
