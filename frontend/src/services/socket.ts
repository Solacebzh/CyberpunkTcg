/**
 * Client WebSocket STOMP.
 *
 * Enveloppe légère autour de `@stomp/stompjs` : reconnexion automatique, gestion des
 * abonnements et sérialisation JSON. Le contrat de destinations est décrit dans
 * `docs/websocket-protocol.md` — toutes les actions passent par `/app/...` et tous
 * les états diffusés par le serveur arrivent sur `/topic/...`.
 */
import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs'

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'error'

export interface GameSocketCallbacks {
  onStateChange?: (state: ConnectionState, detail?: string) => void
  onError?: (message: string) => void
}

type MessageHandler = (body: unknown) => void

/** URL du broker : `VITE_WS_URL` si définie, sinon même origine que la page (`/ws`). */
export function resolveBrokerUrl(): string {
  const configured = import.meta.env.VITE_WS_URL
  if (configured) return configured

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}/ws`
}

export class GameSocket {
  private client: Client | null = null
  private readonly handlers = new Map<string, MessageHandler>()
  private readonly subscriptions = new Map<string, StompSubscription>()

  constructor(private readonly callbacks: GameSocketCallbacks = {}) {}

  get active(): boolean {
    return this.client?.active ?? false
  }

  get connected(): boolean {
    return this.client?.connected ?? false
  }

  /** Ouvre la connexion (idempotent). La reconnexion est automatique. */
  connect(): void {
    if (this.client?.active) return

    this.callbacks.onStateChange?.('connecting')

    const client = new Client({
      brokerURL: resolveBrokerUrl(),
      reconnectDelay: 4000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      // Passer à `(msg) => console.debug('[stomp]', msg)` pour tracer le protocole.
      debug: () => undefined,
    })

    client.onConnect = (frame) => {
      this.callbacks.onStateChange?.('connected', frame.headers['server'])
      this.flushSubscriptions()
    }

    client.onDisconnect = () => {
      this.subscriptions.clear()
      this.callbacks.onStateChange?.('disconnected')
    }

    client.onWebSocketClose = () => {
      this.subscriptions.clear()
      this.callbacks.onStateChange?.('disconnected')
    }

    client.onStompError = (frame) => {
      const message = frame.headers['message'] ?? 'Erreur STOMP'
      this.callbacks.onStateChange?.('error', message)
      this.callbacks.onError?.(message)
    }

    client.onWebSocketError = (event) => {
      const message = `WebSocket indisponible (${String((event as Event).type ?? 'error')})`
      this.callbacks.onStateChange?.('error', message)
      this.callbacks.onError?.(message)
    }

    this.client = client
    client.activate()
  }

  /** Ferme la connexion proprement (désabonne puis déconnecte). */
  disconnect(): void {
    const client = this.client
    if (!client) return

    this.subscriptions.clear()
    this.client = null
    void client.deactivate().finally(() => this.callbacks.onStateChange?.('disconnected'))
  }

  /**
   * S'abonne à une destination et renvoie une fonction de désabonnement.
   * Si la connexion n'est pas encore établie, l'abonnement est mémorisé
   * et envoyé dès l'ouverture du canal.
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
