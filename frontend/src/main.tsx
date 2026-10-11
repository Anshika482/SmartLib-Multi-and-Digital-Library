import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from 'react-router';
import { Toaster } from 'sonner';
import { router } from './app/router';
import { ApiError } from './api/http';
import '@fontsource-variable/plus-jakarta-sans/wght.css';
import '@fontsource/caveat/600.css';
import './styles/index.css';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      refetchOnWindowFocus: false,
      retry: (count, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) return false;
        return count < 2;
      },
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster theme="dark" position="top-right" richColors closeButton
        toastOptions={{ style: { background: '#0d1c31', border: '1px solid rgba(110,170,255,.25)', color: '#f4f8ff' } }} />
    </QueryClientProvider>
  </StrictMode>,
);
