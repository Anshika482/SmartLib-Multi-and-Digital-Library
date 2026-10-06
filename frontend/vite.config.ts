import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';

// The dev server proxies /api to the backend so the browser sees one origin.
// That keeps CORS out of local development entirely; a deployed build talks to
// VITE_API_BASE_URL instead, and that origin must be listed in the backend's
// CORS_ALLOWED_ORIGINS.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    // Node by default: most tests are pure logic and a DOM would only slow
    // them down. The ones that render a component ask for jsdom themselves
    // with a /** @vitest-environment jsdom */ comment at the top of the file.
    environment: 'node',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    setupFiles: ['./src/test-setup.ts'],
  },
});
