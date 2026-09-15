import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

/**
 * Configuration Vite du client Cyberpunk TCG.
 *
 * En développement, le frontend appelle des URLs *relatives* (`/api/...`) : le proxy
 * ci-dessous les redirige vers le backend Spring Boot. Aucun appel à `localhost`
 * n'est fait depuis le navigateur — indispensable pour que le jeu fonctionne
 * aussi via un accès distant (amis, tunnel, prévisualisation).
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiTarget = env.VITE_DEV_API_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue(), tailwindcss()],

    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },

    server: {
      // 0.0.0.0 : accessible depuis l'extérieur du conteneur (tunnel/prévisualisation)
      host: '0.0.0.0',
      port: 5173,
      // Les hôtes de prévisualisation distants sont acceptés en dev
      allowedHosts: true,
      proxy: {
        // API REST → Spring Boot
        '/api': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/images': {
          target: apiTarget,
          changeOrigin: true,
        },
        // WebSocket STOMP → Spring Boot (même origine côté navigateur)
        '/ws': {
          target: apiTarget,
          changeOrigin: true,
          ws: true,
        },
      },
    },

    build: {
      target: 'es2022',
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 900,
    },
  }
})
