/**
 * Store du lobby : identité, salons, démarrage de partie.
 *
 * Flux (doc §4 + §10) :
 *   `connect()` → abonnement `/user/queue/errors`, `/user/queue/lobby`,
 *   `/topic/rooms`, `/user/queue/rooms` → `createRoom()`/`joinRoom()` →
 *   `LOBBY_STATE` reçu → si `status=PLAYING`, on récupère le `gameId` et
 *   l'écran de jeu prend le relais (`gameStore.attach()`).
 *
 * Aucune règle de jeu ici : ce store ne transporte que l'état des salons.
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { useGameSocket, type Unsubscribe } from '@/composables/useGameSocket'
import { useDeckStore } from '@/stores/deck'
import { useUiStore } from '@/stores/ui'
import type { LobbyState, RoomList, RoomSummary, WsError } from '@/types/game'

export type LobbyStatus = 'idle' | 'connecting' | 'ready' | 'waiting' | 'starting' | 'in-game'

const SESSION_KEY = 'cyberpunk-tcg.session.v1'
const LOBBY_ERROR_CODES = new Set([
  'INVALID_PSEUDO',
  'ROOM_NOT_FOUND',
  'ROOM_NOT_JOINABLE',
  'PSEUDO_TAKEN',
  'ALREADY_IN_ROOM',
  'GAME_IN_PROGRESS',
  'DECK_INVALID',
  // Mini-Feature 9D : erreurs spécifiques à la sélection d'un deck sauvegardé.
  'NO_DECK_SELECTED',
  'DECK_NOT_OWNED',
])

interface StoredSession {
  pseudo?: string
  roomCode?: string
  gameId?: string
}

function loadSession(): StoredSession {
  if (typeof window === 'undefined') return {}
  try {
    const raw = window.localStorage.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw) as StoredSession) : {}
  } catch {
    return {}
  }
}

export const useLobbyStore = defineStore('lobby', () => {
  const socket = useGameSocket()
  const ui = useUiStore()
  const decks = useDeckStore()

  const stored = loadSession()

  // --- État ---
  const status = ref<LobbyStatus>('idle')
  const room = ref<LobbyState | null>(null)
  const rooms = ref<RoomSummary[]>([])
  const gameId = ref<string | null>(stored.gameId ?? null)
  const error = ref<string | null>(null)
  const busy = ref(false)
  const roomNameDraft = ref('')
  const roomCodeDraft = ref('')
  /**
   * Mini-Feature 9D : identifiant du deck sauvegardé (`null` tant que le
   * joueur n'a rien choisi). Le salon et la partie ne sont accessibles
   * qu'après cette sélection ; tant que `selectedDeckId` est nul, les
   * boutons « Créer/Rejoindre » restent grisés côté UI.
   */
  const selectedDeckId = ref<number | null>(null)

  let wired = false
  let stopRoom: Unsubscribe | null = null

  // --- Dérivés ---
  const pseudo = computed(() => socket.pseudo.value)
  const isConnected = computed(() => socket.isConnected.value)
  const isHost = computed(() => room.value?.hostPseudo === pseudo.value)
  const mySeat = computed(() => room.value?.players.find((player) => player.pseudo === pseudo.value)?.seat ?? null)
  const opponent = computed(() => room.value?.players.find((player) => player.pseudo !== pseudo.value) ?? null)
  const isWaiting = computed(() => status.value === 'waiting')
  const isInGame = computed(() => status.value === 'in-game' && gameId.value !== null)
  /**
   * `true` si le joueur a sélectionné un de ses decks sauvegardés et peut
   * donc créer ou rejoindre un salon (cf. exigence 9D : pas de partie sans
   * deck). C'est ce booléen que la vue utilise pour griser le bouton.
   */
  const hasSelectedDeck = computed(() => selectedDeckId.value !== null)
  /** Deck sélectionné, exposé pour affichage (« Deck de Val · 43 cartes »). */
  const selectedDeck = computed(() => {
    const id = selectedDeckId.value
    if (id === null) return null
    return decks.savedDecks.find((saved) => saved.id === id) ?? null
  })

  // --- Persistance de session (reprise après rechargement, doc §8) ---
  function persistSession(): void {
    if (typeof window === 'undefined') return
    const payload: StoredSession = { pseudo: pseudo.value }
    if (room.value) payload.roomCode = room.value.code
    if (gameId.value) payload.gameId = gameId.value
    window.localStorage.setItem(SESSION_KEY, JSON.stringify(payload))
  }

  function forgetSession(): void {
    if (typeof window !== 'undefined') window.localStorage.removeItem(SESSION_KEY)
  }

  // --- Réception ---
  function applyLobbyState(payload: LobbyState): void {
    if (!payload || payload.type !== 'LOBBY_STATE') return

    if (payload.status === 'CLOSED') {
      if (room.value?.code === payload.code) leaveLocalRoom()
      socket.requestRooms()
      return
    }

    const mine =
      payload.players.some((player) => player.pseudo === pseudo.value) || room.value?.code === payload.code
    if (!mine) {
      socket.requestRooms()
      return
    }

    room.value = payload
    error.value = null

    if (payload.status === 'PLAYING' && payload.gameId) {
      gameId.value = payload.gameId
      status.value = 'in-game'
      busy.value = false
      persistSession()
    } else {
      status.value = 'waiting'
      stopRoom?.()
      stopRoom = socket.watchRoom(payload.code)
    }
    socket.requestRooms()
  }

  function applyRoomList(payload: RoomList): void {
    if (!payload || payload.type !== 'ROOMS') return
    rooms.value = payload.rooms ?? []
  }

  function applyError(payload: WsError): void {
    const destination = payload.destination ?? ''
    const isLobbyError = LOBBY_ERROR_CODES.has(payload.code) || destination.startsWith('/app/lobby')
    if (payload.code === 'GAME_NOT_FOUND') {
      // Partie terminée/oubliée côté serveur : on repart du lobby.
      gameId.value = null
      leaveLocalRoom()
      forgetSession()
      return
    }
    if (!isLobbyError) return

    error.value = payload.message
    busy.value = false
    if (payload.code !== 'INVALID_PSEUDO') status.value = room.value ? 'waiting' : 'ready'
    ui.error(payload.message)
  }

  // --- Câblage (idempotent) ---
  function init(): void {
    if (wired) return
    wired = true

    socket.onLobbyState(applyLobbyState)
    socket.onRoomList(applyRoomList)
    socket.onWsError(applyError)
    socket.onReconnected(() => socket.requestRooms())
  }

  // --- Actions ---
  function setPseudo(value: string): boolean {
    const ok = socket.setPseudo(value)
    if (!ok) {
      error.value = 'Pseudo invalide : 2 à 20 caractères (lettres, chiffres, _ et -)'
      return false
    }
    error.value = null
    persistSession()
    return true
  }

  function connect(): void {
    init()
    status.value = 'connecting'
    socket.connect()
    if (socket.isConnected.value) status.value = room.value ? 'waiting' : 'ready'
    socket.requestRooms()
  }

  function createRoom(name?: string): boolean {
    init()
    if (!ensureConnected()) return false
    if (selectedDeckId.value === null) {
      error.value = 'Sélectionne un deck sauvegardé avant de créer un salon'
      return false
    }

    busy.value = true
    error.value = null
    status.value = 'waiting'
    const roomName = (name ?? roomNameDraft.value).trim()
    const sent = socket.createRoom({ roomName: roomName || null, deckId: selectedDeckId.value })
    if (!sent) {
      busy.value = false
      status.value = 'ready'
      error.value = 'Canal temps réel indisponible'
      return false
    }
    return true
  }

  function joinRoom(code?: string): boolean {
    init()
    if (!ensureConnected()) return false
    if (selectedDeckId.value === null) {
      error.value = 'Sélectionne un deck sauvegardé avant de rejoindre un salon'
      return false
    }

    const roomCode = (code ?? roomCodeDraft.value).trim().toUpperCase()
    if (!roomCode) {
      error.value = 'Saisis le code du salon à rejoindre'
      return false
    }

    busy.value = true
    error.value = null
    status.value = 'starting'
    const sent = socket.joinRoom({ roomCode, deckId: selectedDeckId.value })
    if (!sent) {
      busy.value = false
      status.value = 'ready'
      error.value = 'Canal temps réel indisponible'
      return false
    }
    return true
  }

  /**
   * Sélectionne un deck sauvegardé (`null` = retire la sélection). À partir
   * de là, les boutons « Créer/Rejoindre » sont activés côté UI et le
   * payload STOMP portera ce `deckId`.
   */
  function selectDeck(deckId: number | null): void {
    selectedDeckId.value = deckId
    if (deckId !== null) error.value = null
  }

  function leaveRoom(): void {
    if (room.value) socket.leaveRoom({ roomCode: room.value.code })
    leaveLocalRoom()
    socket.requestRooms()
  }

  function leaveLocalRoom(): void {
    if (room.value) socket.forgetRoom(room.value.code)
    stopRoom?.()
    stopRoom = null
    room.value = null
    gameId.value = null
    status.value = socket.isConnected.value ? 'ready' : 'idle'
    busy.value = false
    forgetSession()
  }

  function refreshRooms(): void {
    init()
    if (!ensureConnected()) return
    socket.requestRooms()
  }

  /** Reprise d'une partie après rechargement de page (doc §8). */
  function resumeStoredGame(): string | null {
    const session = loadSession()
    if (!session.gameId) return null
    gameId.value = session.gameId
    status.value = 'in-game'
    return session.gameId
  }

  function ensureConnected(): boolean {
    if (socket.isConnected.value) return true
    socket.connect()
    error.value = 'Connexion au serveur en cours…'
    return false
  }

  /** Abandonne la partie en cours puis revient au lobby. */
  function forfeit(): void {
    if (gameId.value) socket.sendAction(gameId.value, { action: 'CONCEDE' })
    leaveLocalRoom()
  }

  return {
    // état
    status,
    room,
    rooms,
    gameId,
    error,
    busy,
    roomNameDraft,
    roomCodeDraft,
    selectedDeckId,
    // dérivés
    pseudo,
    isConnected,
    isHost,
    mySeat,
    opponent,
    isWaiting,
    isInGame,
    hasSelectedDeck,
    selectedDeck,
    // actions
    init,
    setPseudo,
    connect,
    createRoom,
    joinRoom,
    leaveRoom,
    refreshRooms,
    leaveLocalRoom,
    resumeStoredGame,
    forfeit,
    selectDeck,
  }
})
