import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * Configuration Vitest (tests du frontend).
 *
 * Les tests montent de vrais composants Pinia/Vue contre un **serveur STOMP
 * simulé** (`tools/mock-protocol.mjs`) : ils vérifient le contrat de transport
 * décrit dans docs/WEBSOCKET-PROTOCOL.md, pas les règles (le moteur de règles
 * vit côté backend et a ses propres tests JUnit).
 */
export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    // GSAP et les minuteurs de toast tournent dans jsdom : on garde les vrais timers.
    globals: false,
    testTimeout: 10_000,
  },
})
