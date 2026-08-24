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
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
