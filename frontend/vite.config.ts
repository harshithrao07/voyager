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
