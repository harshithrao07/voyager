import { createRoot } from 'react-dom/client'
import { Toaster } from 'sonner'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <>
    <App />
    <Toaster
      richColors
      position="bottom-right"
      toastOptions={{
        style: {
          background: '#0c0e12',
          border: '1px solid #333539',
          color: '#e2e2e8',
        },
      }}
    />
  </>
)
