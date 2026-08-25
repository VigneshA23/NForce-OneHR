import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  // Renamed from Vite's default 'assets' so the build output directory
  // doesn't collide with the app's own "/assets" (Assets & Expenses) route —
  // static hosts resolve a request path against the filesystem before
  // falling back to index.html, so "/assets" was matching the build
  // directory and serving a raw JS chunk instead of the SPA shell on refresh.
  build: {
    assetsDir: 'static',
  },
  server: {
    port: process.env.PORT ? Number(process.env.PORT) : 5180,
    strictPort: true,
    // Explicit — without this, this Vite version binds "localhost" to the IPv6 loopback
    // ([::1]) only, not IPv4 (127.0.0.1). Any client/OS that resolves "localhost" to IPv4
    // first then just hangs with nothing listening there. 0.0.0.0 covers both.
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
