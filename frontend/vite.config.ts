import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const backendUrl = process.env.VITE_BACKEND_URL || 'http://localhost:8081'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
  ],
  server: {
    host: "0.0.0.0",
    fs: {
      // The Docs page imports markdown and screenshots from the repo-root
      // /docs directory, which sits outside this Vite root.
      allow: ['..'],
    },
    proxy: {
      '/app': backendUrl,
      '/api': backendUrl,
      '/ws': {
        target: backendUrl.replace(/^http/, 'ws'),
        ws: true,
      },
    },
  },
})
