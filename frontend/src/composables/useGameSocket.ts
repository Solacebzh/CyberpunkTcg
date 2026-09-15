/**
 * `useGameSocket` — accès unique et partagé au canal STOMP du jeu.
 *
 * Responsabilités :
 *  1. **Connexion** avec l'en-tête natif `pseudo` (identité, doc §1.1) et
 *     reconnexion automatique (back-off 2 → 5 s) ;
 *  2. **Abonnements** aux destinations du protocole (doc §2.2), rejoués à
 *     chaque reconnexion, avec fan-out vers plusieurs abonnés applicatifs ;
 *  3. **Intentions** : `sendAction()` (partie), commandes de lobby, `resync` ;
 *  4. **Ordre imposé** : « s'abonner avant d'agir, puis resync » (doc §11.1).
 *
 * Ce composable est un **singleton de module** : `useGameSocket()` renvoie
 * toujours le même objet réactif, quel que soit l'appelant (store ou composant).
 * Il ne contient aucune règle de jeu : il ne fait que transporter des messages.
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue'

import { GameSocket, type ConnectionState } from '@/services/socket'
import {
  Ws,
  isValidPseudo,
  type CreateRoomRequest,
  type GameCommand,
  type GameLogMessage,
  type GameNotice,
  type GameStateMessage,
  type JoinRoomRequest,
  type LeaveRoomRequest,
  type LobbyState,
  type PongMessage,
  type RoomList,
  type WsError,
} from '@/types/game'

export type Unsubscribe = () => void

const PSEUDO_STORAGE_KEY = 'cyberpunk-tcg.pseudo'
const DEFAULT_PSEUDO = 'NetRunner'

type Handler<T> = (payload: T) => void

// --- État partagé (module) ---------------------------------------------------

const pseudo = ref<string>(loadStoredPseudo())
const status = ref<ConnectionState>('disconnected')
const statusDetail = ref<string | null>(null)

const stateHandlers = new Set<Handler<GameStateMessage>>()
const noticeHandlers = new Set<Handler<GameNotice>>()
const errorHandlers = new Set<Handler<WsError>>()
const lobbyHandlers = new Set<Handler<LobbyState>>()
const roomHandlers = new Set<Handler<RoomList>>()
const pongHandlers = new Set<Handler<PongMessage>>()
const gameLogHandlers = new Set<Handler<GameLogMessage>>()
const reconnectedHandlers = new Set<Handler<number>>()

/** Abonnements STOMP actifs : rejoués tels quels après chaque reconnexion. */
const registrations = new Map<string, (body: unknown) => void>()
const disposers = new Map<string, Unsubscribe>()
/** Actions à exécuter dès que le canal est ouvert (ex. `resync`). */
const pendingOnConnect: Array<() => void> = []

let client: GameSocket | null = null

// --- Helpers internes --------------------------------------------------------

function loadStoredPseudo(): string {
  if (typeof window === 'undefined') return DEFAULT_PSEUDO
  const stored = window.localStorage.getItem(PSEUDO_STORAGE_KEY)
  return stored && isValidPseudo(stored) ? stored : DEFAULT_PSEUDO
}

function storePseudo(value: string): void {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(PSEUDO_STORAGE_KEY, value)
}

/** Retire les champs `null`/`undefined` : le serveur omet déjà les champs nuls. */
function compact<T extends Record<string, unknown>>(payload: T): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(payload)) {
    if (value !== null && value !== undefined) out[key] = value
  }
  return out
}

function addHandler<T>(set: Set<Handler<T>>, handler: Handler<T>): Unsubscribe {
  set.add(handler)
  return () => set.delete(handler)
}

function broadcast<T>(set: Set<Handler<T>>, payload: T): void {
  for (const handler of set) handler(payload)
}

function flushPending(): void {
  if (pendingOnConnect.length === 0) return
  const actions = [...pendingOnConnect]
  pendingOnConnect.length = 0
  for (const action of actions) action()
}

function applyRegistrations(): void {
  if (!client) return
  for (const [destination, handler] of registrations) {
    disposers.get(destination)?.()
    disposers.set(destination, client.subscribe(destination, handler))
  }
}

function register(destination: string, handler: (body: unknown) => void): Unsubscribe {
  registrations.set(destination, handler)
  if (client) disposers.set(destination, client.subscribe(destination, handler))

  return () => {
    registrations.delete(destination)
    disposers.get(destination)?.()
    disposers.delete(destination)
  }
}

function unregister(destination: string): void {
  registrations.delete(destination)
  disposers.get(destination)?.()
  disposers.delete(destination)
}

function ensureClient(): GameSocket {
  if (client) return client

  client = new GameSocket({
    connectHeaders: { pseudo: pseudo.value },
    onStateChange: (next, detail) => {
      status.value = next
      statusDetail.value = detail ?? null
    },
    onError: (message) => {
      statusDetail.value = message
    },
    onConnected: (attempt) => {
      status.value = 'connected'
      applyRegistrations()
      flushPending()
      if (attempt > 1) broadcast(reconnectedHandlers, attempt)
    },
    onConnectionLost: () => {
      // Rien à nettoyer : les abonnements sont rejoués à la reconnexion.
    },
  })

  // Les files privées sont permanentes (doc §1.3, ordre 1).
  register(Ws.errorsQueue, (body) => broadcast(errorHandlers, body as WsError))
  register(Ws.lobbyQueue, (body) => broadcast(lobbyHandlers, body as LobbyState))
  register(Ws.roomsQueue, (body) => broadcast(roomHandlers, body as RoomList))
  register(Ws.roomsTopic, (body) => broadcast(roomHandlers, body as RoomList))

  return client
}

// --- API publique ------------------------------------------------------------

export interface GameSocketApi {
  /** Pseudo courant = identité STOMP (en-tête CONNECT). */
  pseudo: Ref<string>
  status: Ref<ConnectionState>
  statusDetail: Ref<string | null>
  isConnected: ComputedRef<boolean>

  setPseudo(value: string): boolean
  connect(): void
  disconnect(): void

  /** Intention de jeu ; renvoie le `clientRequestId` ou `null` si le canal est fermé. */
  sendAction(gameId: string, command: Omit<GameCommand, 'clientRequestId'>): string | null
  /** Demande l'état complet courant (après (re)connexion ou trou de séquence). */
  requestResync(gameId: string): void
  createRoom(request: CreateRoomRequest): boolean
  joinRoom(request: JoinRoomRequest): boolean
  leaveRoom(request?: LeaveRoomRequest): boolean
  requestRooms(): boolean
  ping(message?: string): boolean

  /** S'abonne aux topics d'une partie (état, notifications, journal) puis demande un `resync`. */
  watchGame(gameId: string): Unsubscribe
  /** S'abonne à l'état d'un salon précis. */
  watchRoom(code: string): Unsubscribe
  forgetRoom(code: string): void

  onGameState(handler: Handler<GameStateMessage>): Unsubscribe
  /** Journal de diagnostic d'une partie (feature 6.5, `/topic/game/{id}/log`). */
  onGameLog(handler: Handler<GameLogMessage>): Unsubscribe
  onGameNotice(handler: Handler<GameNotice>): Unsubscribe
  onWsError(handler: Handler<WsError>): Unsubscribe
  onLobbyState(handler: Handler<LobbyState>): Unsubscribe
  onRoomList(handler: Handler<RoomList>): Unsubscribe
  onPong(handler: Handler<PongMessage>): Unsubscribe
  onReconnected(handler: Handler<number>): Unsubscribe
}

function setPseudo(value: string): boolean {
  const trimmed = value.trim()
  if (!isValidPseudo(trimmed)) return false
  if (trimmed === pseudo.value) return true

  pseudo.value = trimmed
  storePseudo(trimmed)
  // L'identité vit dans le frame CONNECT : changer de pseudo impose une reconnexion.
  if (client?.active) {
    disconnect()
    connect()
  }
  return true
}

function connect(): void {
  storePseudo(pseudo.value)
  const instance = ensureClient()
  // L'en-tête CONNECT est figé à la création du client : on le resynchronise ici.
  instance.connect()
}

function disconnect(): void {
  const instance = client
  client = null
  disposers.clear()
  pendingOnConnect.length = 0
  instance?.disconnect()
  status.value = 'disconnected'
  statusDetail.value = null
}

function publish(destination: string, payload?: Record<string, unknown>): boolean {
  const instance = ensureClient()
  return instance.publish(destination, payload)
}

function whenConnected(action: () => void): void {
  if (status.value === 'connected') action()
  else pendingOnConnect.push(action)
}

function newRequestId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().slice(0, 8)
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

function sendAction(gameId: string, command: Omit<GameCommand, 'clientRequestId'>): string | null {
  const clientRequestId = newRequestId()
  const sent = publish(Ws.gameAction(gameId), compact({ ...command, clientRequestId }))
  return sent ? clientRequestId : null
}

function requestResync(gameId: string): void {
  whenConnected(() => publish(Ws.gameResync(gameId), {}))
}

function createRoom(request: CreateRoomRequest): boolean {
  return publish(Ws.lobbyCreate, compact({ roomName: request.roomName, deckCardIds: request.deckCardIds }))
}

function joinRoom(request: JoinRoomRequest): boolean {
  return publish(Ws.lobbyJoin, compact({ roomCode: request.roomCode, deckCardIds: request.deckCardIds }))
}

function leaveRoom(request: LeaveRoomRequest = {}): boolean {
  return publish(Ws.lobbyLeave, compact({ roomCode: request.roomCode }))
}

function requestRooms(): boolean {
  return publish(Ws.lobbyList)
}

function ping(message = 'hello'): boolean {
  return publish(Ws.ping, { message })
}

function watchGame(gameId: string): Unsubscribe {
  const target = pseudo.value
  const offState = register(Ws.gameStateTopic(gameId, target), (body) => {
    const message = body as GameStateMessage
    if (message?.type === 'STATE') broadcast(stateHandlers, message)
  })
  const offNotice = register(Ws.gameTopic(gameId), (body) => broadcast(noticeHandlers, body as GameNotice))
  const offLog = register(Ws.gameLogTopic(gameId), (body) => {
    const message = body as GameLogMessage
    if (message?.type === 'LOG') broadcast(gameLogHandlers, message)
  })

  // Le STATE initial peut précéder l'abonnement (doc §4.2 / §11.1) → resync.
  requestResync(gameId)

  return () => {
    offState()
    offNotice()
    offLog()
  }
}

/** Canal de test `SEND /app/ping` → `SUBSCRIBE /topic/pong` (doc §3.1). */
function onPong(handler: Handler<PongMessage>): Unsubscribe {
  const isFirst = pongHandlers.size === 0
  pongHandlers.add(handler)
  if (isFirst) register(Ws.pong, (body) => broadcast(pongHandlers, body as PongMessage))
  return () => pongHandlers.delete(handler)
}

function watchRoom(code: string): Unsubscribe {
  return register(Ws.lobbyTopic(code), (body) => broadcast(lobbyHandlers, body as LobbyState))
}

function forgetRoom(code: string): void {
  unregister(Ws.lobbyTopic(code))
}

const isConnected = computed(() => status.value === 'connected')

const api: GameSocketApi = {
  pseudo,
  status,
  statusDetail,
  isConnected,
  setPseudo,
  connect,
  disconnect,
  sendAction,
  requestResync,
  createRoom,
  joinRoom,
  leaveRoom,
  requestRooms,
  ping,
  watchGame,
  watchRoom,
  forgetRoom,
  onGameState: (handler) => addHandler(stateHandlers, handler),
  onGameNotice: (handler) => addHandler(noticeHandlers, handler),
  onWsError: (handler) => addHandler(errorHandlers, handler),
  onLobbyState: (handler) => addHandler(lobbyHandlers, handler),
  onRoomList: (handler) => addHandler(roomHandlers, handler),
  onPong,
  onGameLog: (handler) => addHandler(gameLogHandlers, handler),
  onReconnected: (handler) => addHandler(reconnectedHandlers, handler),
}

/** Point d'entrée unique vers le canal STOMP (état partagé par toute l'application). */
export function useGameSocket(): GameSocketApi {
  return api
}

/** Remet à zéro le canal (tests unitaires uniquement). */
export function __resetGameSocketForTests(): void {
  disconnect()
  stateHandlers.clear()
  gameLogHandlers.clear()
  noticeHandlers.clear()
  errorHandlers.clear()
  lobbyHandlers.clear()
  roomHandlers.clear()
  pongHandlers.clear()
  reconnectedHandlers.clear()
  registrations.clear()
  pendingOnConnect.length = 0
}
