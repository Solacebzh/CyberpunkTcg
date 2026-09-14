/// <reference types="vite/client" />

/** Types des variables d'environnement exposées au client (préfixe VITE_). */
interface ImportMetaEnv {
  /** Base des URLs d'API. Vide = URLs relatives `/api` (recommandé : passe par le proxy). */
  readonly VITE_API_BASE_URL?: string
  /** URL absolue du WebSocket STOMP. Vide = `/ws` sur l'origine courante. */
  readonly VITE_WS_URL?: string
  /** Cible du proxy de dev pour l'API (côté serveur Vite uniquement). */
  readonly VITE_DEV_API_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>
  export default component
}
