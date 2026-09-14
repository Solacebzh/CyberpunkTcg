/**
 * Harnais de test : fausse WebSocket + client STOMP « brut » pour le second joueur.
 *
 * Le frontend testé utilise son **vrai** code (`useGameSocket` → `@stomp/stompjs`) ;
 * seule l'extrémité réseau est remplacée par une socket en mémoire branchée sur le
 * serveur simulé `devtools/mock-protocol.ts`.
 */
import { vi } from 'vitest'

import {
  MockGameServer,
  parseFrame,
  serializeFrame,
  splitFrames,
  type MockCard,
  type MockFrame,
  type MockSessionApi,
} from '../../../devtools/mock-protocol'

let activeServer: MockGameServer | null = null

/** Sockets créées par le client STOMP du frontend (contrôle du transport en test). */
export const createdSockets: FakeWebSocket[] = []

export function currentServer(): MockGameServer {
  if (!activeServer) throw new Error('Aucun serveur simulé installé (appelle installMockServer)')
  return activeServer
}

/** Complète une carte de test avec les champs toujours présents dans `GET /api/cards`. */
export function card(partial: MockCard): MockCard {
  return {
    subtitle: null,
    ram: 1,
    tags: [],
    text: '',
    abilities: [],
    imageUrl: null,
    setCode: 'TEST',
    collectorNumber: '000',
    rarity: null,
    ...partial,
  }
}

/** WebSocket en mémoire : mêmes callbacks que l'API navigateur, livraison asynchrone. */
export class FakeWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3

  readonly url: string
  binaryType = 'blob'
  readyState = FakeWebSocket.CONNECTING

  onopen: ((event: unknown) => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: unknown) => void) | null = null
  onerror: ((event: unknown) => void) | null = null

  /** Frames émises par ce client (assertions de protocole). */
  readonly sent: string[] = []

  private readonly api: MockSessionApi

  constructor(url: string, _protocols?: string[]) {
    this.url = url
    createdSockets.push(this)
    this.api = currentServer().createSession((frame) => this.deliver(frame))
    queueMicrotask(() => {
      if (this.readyState !== FakeWebSocket.CONNECTING) return
      this.readyState = FakeWebSocket.OPEN
      this.onopen?.({ type: 'open' })
    })
  }

  send(data: string | ArrayBuffer): void {
    if (this.readyState !== FakeWebSocket.OPEN) throw new Error('WebSocket fermée')
    const text = typeof data === 'string' ? data : new TextDecoder().decode(data)
    this.sent.push(text)
    this.api.receive(text)
  }

  close(): void {
    if (this.readyState === FakeWebSocket.CLOSED) return
    this.readyState = FakeWebSocket.CLOSED
    this.api.close()
    queueMicrotask(() => this.onclose?.({ type: 'close', code: 1000, reason: 'closed', wasClean: true }))
  }

  terminate(): void {
    this.close()
  }

  /** Coupure réseau brutale (le client STOMP reste actif et doit retenter). */
  forceClose(): void {
    this.readyState = FakeWebSocket.CLOSED
    this.api.close()
    queueMicrotask(() => this.onclose?.({ type: 'close', code: 1006, reason: 'transport perdu', wasClean: false }))
  }

  private deliver(frame: string): void {
    queueMicrotask(() => {
      if (this.readyState === FakeWebSocket.OPEN) this.onmessage?.({ data: frame })
    })
  }
}

/**
 * Installe le serveur simulé, la fausse WebSocket globale et un `fetch` qui sert
 * le catalogue (le deck builder et les illustrations passent par `/api/cards`).
 */
export function installMockServer(cards: MockCard[], seed = 42): MockGameServer {
  const server = new MockGameServer({ cards, seed })
  activeServer = server
  createdSockets.length = 0

  vi.stubGlobal('WebSocket', FakeWebSocket)
  vi.stubGlobal('fetch', async () =>
    Promise.resolve({
      ok: true,
      status: 200,
      json: async () => cards,
    }) as unknown as Response,
  )

  return server
}

export function uninstallMockServer(): void {
  activeServer = null
  vi.unstubAllGlobals()
}

/** Frames SEND émises par les clients du frontend, décodées. */
export function sentCommands(server: MockGameServer): Array<{ destination: string; body: Record<string, unknown> }> {
  return server.received
    .filter((frame: MockFrame) => frame.command === 'SEND')
    .map((frame) => ({
      destination: frame.headers.destination ?? '',
      body: frame.body ? (JSON.parse(frame.body) as Record<string, unknown>) : {},
    }))
}

export function commandsTo(server: MockGameServer, suffix: string): Array<Record<string, unknown>> {
  return sentCommands(server)
    .filter((command) => command.destination.endsWith(suffix))
    .map((command) => command.body)
}

/** Laisse passer microtâches et timers 0 (livraison STOMP, watchers Vue). */
export async function tick(times = 4): Promise<void> {
  for (let i = 0; i < times; i += 1) {
    await Promise.resolve()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

/** Second joueur : parle STOMP directement au serveur simulé (aucun code applicatif). */
export class RawClient {
  readonly messages: Array<{ destination: string; body: Record<string, unknown> }> = []

  private readonly api: MockSessionApi
  private subscriptionCount = 0

  constructor(server: MockGameServer, readonly pseudo: string) {
    this.api = server.createSession((frame) => this.onFrame(frame))
  }

  connect(): void {
    this.api.receive(
      serializeFrame('CONNECT', { 'accept-version': '1.2', 'heart-beat': '0,0', pseudo: this.pseudo }),
    )
  }

  subscribe(destination: string): void {
    this.subscriptionCount += 1
    this.api.receive(serializeFrame('SUBSCRIBE', { id: `raw-${this.subscriptionCount}`, destination }))
  }

  send(destination: string, payload?: unknown): void {
    this.api.receive(
      serializeFrame(
        'SEND',
        { destination, 'content-type': 'application/json' },
        payload === undefined ? '' : JSON.stringify(payload),
      ),
    )
  }

  /** Fermeture brutale du socket (rafraîchissement de page, coupure réseau). */
  disconnect(): void {
    this.api.close()
  }

  /** Enveloppes `GameStateMessage` reçues, dans l'ordre. */
  states(): Array<Record<string, unknown>> {
    return this.messages.filter((message) => message.body.type === 'STATE').map((message) => message.body)
  }

  /** Dernier `GameStateDTO` reçu (l'intérieur de l'enveloppe). */
  lastState(): Record<string, unknown> | null {
    const states = this.states()
    const last = states.length > 0 ? states[states.length - 1] : null
    return (last?.state as Record<string, unknown> | undefined) ?? null
  }

  errors(): Array<Record<string, unknown>> {
    return this.messages.filter((message) => message.body.type === 'ERROR').map((message) => message.body)
  }

  private onFrame(text: string): void {
    for (const raw of splitFrames(text)) {
      const frame = parseFrame(raw)
      if (frame.command !== 'MESSAGE') continue
      this.messages.push({
        destination: frame.headers.destination ?? '',
        body: frame.body ? (JSON.parse(frame.body) as Record<string, unknown>) : {},
      })
    }
  }
}
