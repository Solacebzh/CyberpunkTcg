/**
 * Client WebSocket STOMP.
 *
 * Enveloppe légère autour de `@stomp/stompjs` : reconnexion automatique avec
 * back-off, gestion des abonnements et sérialisation JSON. Le contrat de
 * destinations est décrit dans `docs/WEBSOCKET-PROTOCOL.md` — toutes les
 * intentions partent sur `/app/...`, les états arrivent sur `/topic/...` ou
 * `/user/queue/...`.
 *
 * Identité : le serveur exige un en-tête natif `pseudo` sur le frame CONNECT
 * (doc §1.1-1.2). Il est passé via `connectHeaders` et ne figure JAMAIS dans
 * les payloads envoyés.
 */
import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error'

export interface GameSocketCallbacks {
  onStateChange?: (state: ConnectionState, detail?: string) => void
  onError?: (message: string) => void
  /** Appelé à chaque (re)connexion réussie ; `attempt` vaut 1 pour la première. */
  onConnected?: (attempt: number) => void
  /** Appelé quand le transport tombe (avant une nouvelle tentative). */
  onConnectionLost?: () => void
}

export interface GameSocketOptions extends GameSocketCallbacks {
  /** En-têtes natifs du frame CONNECT (`{ pseudo: 'Johnny' }`). */
  connectHeaders?: Record<string, string>
  /** Factory WebSocket (tests / environnements sans WebSocket global). */
  webSocketFactory?: () => WebSocket
  /** Délai de base entre deux tentatives de reconnexion (ms). */
  reconnectDelay?: number
}

/** URL du broker : `VITE_WS_URL` si définie, sinon même origine que la page (`/ws`). */
export function resolveBrokerUrl(): string {
  const configured = import.meta.env.VITE_WS_URL
  if (configured) return configured

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

/** Back-off recommandé par la doc (§1) : 2 s → 5 s, puis plafonné. */
const RECONNECT_BASE_DELAY = 2_000
const RECONNECT_MAX_DELAY = 5_000
const RECONNECT_FACTOR = 1.5

type MessageHandler = (body: unknown) => void

export class GameSocket {
  private client: Client | null = null
  private readonly handlers = new Map<string, MessageHandler>()
  private readonly subscriptions = new Map<string, StompSubscription>()
  private connectAttempts = 0
  private connectionLostNotified = false
  private nextDelay: number

  constructor(private readonly options: GameSocketOptions = {}) {
    this.nextDelay = options.reconnectDelay ?? RECONNECT_BASE_DELAY
  }

  get active(): boolean {
    return this.client?.active ?? false
  }

  get connected(): boolean {
    return this.client?.connected ?? false
  }

  /** Nombre de connexions réussies depuis la création du client. */
  get attempts(): number {
    return this.connectAttempts
  }

  /** Ouvre la connexion (idempotent). La reconnexion est automatique. */
  connect(): void {
    if (this.client?.active) return

    this.options.onStateChange?.('connecting')

    const client = new Client({
      brokerURL: resolveBrokerUrl(),
      connectHeaders: this.options.connectHeaders ?? {},
      webSocketFactory: this.options.webSocketFactory,
      reconnectDelay: this.nextDelay,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      // Passer à `(msg) => console.debug('[stomp]', msg)` pour tracer le protocole.
      debug: () => undefined,
    })

    client.onConnect = (frame) => {
      this.connectAttempts += 1
      this.connectionLostNotified = false
      this.nextDelay = this.options.reconnectDelay ?? RECONNECT_BASE_DELAY
      client.reconnectDelay = this.nextDelay
      this.options.onStateChange?.('connected', frame.headers['server'])
      // Les abonnements mémorisés sont rejoués à chaque (re)connexion.
      this.flushSubscriptions()
      this.options.onConnected?.(this.connectAttempts)
    }

    client.onDisconnect = () => {
      this.subscriptions.clear()
      this.options.onStateChange?.('disconnected')
      this.notifyConnectionLost()
    }

    client.onWebSocketClose = () => {
      this.subscriptions.clear()
      if (this.client?.active) {
        // Transport coupé alors que le client est encore actif : stompjs retente
        // tout seul, on allonge progressivement le délai entre deux essais.
        this.nextDelay = Math.min(this.nextDelay * RECONNECT_FACTOR, RECONNECT_MAX_DELAY)
        client.reconnectDelay = this.nextDelay
        this.options.onStateChange?.('connecting', `reconnexion dans ${Math.round(this.nextDelay / 1000)} s`)
      } else {
        this.options.onStateChange?.('disconnected')
      }
      this.notifyConnectionLost()
    }

    client.onStompError = (frame) => {
      const message = frame.headers['message'] ?? 'Erreur STOMP'
      this.options.onStateChange?.('error', message)
      this.options.onError?.(message)
    }

    client.onWebSocketError = (event) => {
      const message = `WebSocket indisponible (${String((event as Event).type ?? 'error')})`
      this.options.onStateChange?.('error', message)
      this.options.onError?.(message)
    }

    this.client = client
    client.activate()
  }

  /** Ferme la connexion proprement (désabonne puis déconnecte). */
  disconnect(): void {
    const client = this.client
    if (!client) return

    this.handlers.clear()
    this.subscriptions.clear()
    this.client = null
    void client.deactivate().finally(() => this.options.onStateChange?.('disconnected'))
  }

  /**
   * S'abonne à une destination et renvoie une fonction de désabonnement.
   * Si la connexion n'est pas encore établie, l'abonnement est mémorisé
   * et envoyé dès l'ouverture du canal (ou à la reconnexion).
   */
  subscribe<T = unknown>(destination: string, handler: (body: T) => void): () => void {
    const wrapped: MessageHandler = (body) => handler(body as T)
    this.handlers.set(destination, wrapped)

    if (this.client?.connected) {
      this.subscribeOnClient(destination)
    }

    return () => {
      this.handlers.delete(destination)
      this.subscriptions.get(destination)?.unsubscribe()
      this.subscriptions.delete(destination)
    }
  }

  /** Publie une intention de jeu. Renvoie `false` si le canal n'est pas prêt. */
  publish(destination: string, payload?: unknown): boolean {
    const client = this.client
    if (!client?.connected) return false

    client.publish({
      destination,
      body: payload === undefined ? '' : JSON.stringify(payload),
      headers: { 'content-type': 'application/json' },
    })
    return true
  }

  /** `onDisconnect` et `onWebSocketClose` peuvent se suivre : on notifie une seule fois. */
  private notifyConnectionLost(): void {
    if (this.connectionLostNotified) return
    this.connectionLostNotified = true
    this.options.onConnectionLost?.()
  }

  private subscribeOnClient(destination: string): void {
    const client = this.client
    const handler = this.handlers.get(destination)
    if (!client?.connected || !handler) return

    this.subscriptions.get(destination)?.unsubscribe()
    this.subscriptions.set(
      destination,
      client.subscribe(destination, (message: IMessage) => handler(parseBody(message.body))),
    )
  }

  private flushSubscriptions(): void {
    for (const destination of this.handlers.keys()) {
      this.subscribeOnClient(destination)
    }
  }
}

function parseBody(body: string): unknown {
  if (!body) return null
  try {
    return JSON.parse(body) as unknown
  } catch {
    // Le serveur peut envoyer du texte brut (ex. « pong »)
    return body
  }
}
