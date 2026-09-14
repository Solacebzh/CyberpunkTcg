/**
 * Store Pinia de la connexion : santé de l'API REST + canal STOMP temps réel.
 *
 * Sert de référence pour les futurs stores (`gameStore`, `deckStore`…) :
 * état exposé en lecture seule, actions explicites, aucune logique de jeu ici.
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchHealth, type HealthResponse } from '@/services/api'
import { GameSocket, type ConnectionState } from '@/services/socket'

export type ApiState = 'unknown' | 'checking' | 'up' | 'down'

export interface LogEntry {
  at: string
  kind: 'info' | 'success' | 'error'
  message: string
}

const MAX_LOG_ENTRIES = 30

export const useConnectionStore = defineStore('connection', () => {
  // --- État ---
  const apiState = ref<ApiState>('unknown')
  const health = ref<HealthResponse | null>(null)
  const apiError = ref<string | null>(null)

  const wsState = ref<ConnectionState>('disconnected')
  const wsDetail = ref<string | null>(null)

  const log = ref<LogEntry[]>([])

  let socket: GameSocket | null = null
  let unsubscribePong: (() => void) | null = null

  // --- Dérivés ---
  const isApiUp = computed(() => apiState.value === 'up')
  const isWsConnected = computed(() => wsState.value === 'connected')
  const isWsBusy = computed(() => wsState.value === 'connecting')
  const apiVersion = computed(() => health.value?.version ?? '—')
  const databaseUp = computed(() => health.value?.database === 'UP')

  // --- Utilitaires ---
  function pushLog(kind: LogEntry['kind'], message: string): void {
    log.value.unshift({ at: new Date().toLocaleTimeString('fr-FR'), kind, message })
    if (log.value.length > MAX_LOG_ENTRIES) log.value.length = MAX_LOG_ENTRIES
  }

  function clearLog(): void {
    log.value = []
  }

  // --- API REST ---
  async function checkApi(): Promise<void> {
    apiState.value = 'checking'
    apiError.value = null

    try {
      health.value = await fetchHealth()
      apiState.value = 'up'
      pushLog(
        'success',
        `API ${health.value.service} v${health.value.version} en ligne (base : ${health.value.database})`,
      )
    } catch (error) {
      apiState.value = 'down'
      apiError.value = error instanceof Error ? error.message : 'API injoignable'
      health.value = null
      pushLog('error', `API injoignable — ${apiError.value}`)
    }
  }

  // --- WebSocket STOMP ---
  function ensureSocket(): GameSocket {
    if (socket) return socket

    socket = new GameSocket({
      onStateChange: (state, detail) => {
        wsState.value = state
        wsDetail.value = detail ?? null
        if (state === 'connected') pushLog('success', 'WebSocket STOMP connecté sur /ws')
        if (state === 'disconnected') pushLog('info', 'WebSocket déconnecté')
        if (state === 'error') pushLog('error', detail ?? 'Erreur WebSocket')
      },
      onError: (message) => pushLog('error', message),
    })

    return socket
  }

  function connect(): void {
    const client = ensureSocket()

    unsubscribePong?.()
    unsubscribePong = client.subscribe<{ type: string; echo: string; serverTime: string }>('/topic/pong', (pong) => {
      pushLog('success', `pong reçu du serveur — écho « ${pong?.echo ?? '?' } » à ${pong?.serverTime ?? '?'}`)
    })

    client.connect()
  }

  function disconnect(): void {
    unsubscribePong?.()
    unsubscribePong = null
    socket?.disconnect()
    wsState.value = 'disconnected'
    pushLog('info', 'Déconnexion demandée par le joueur')
  }

  /** Envoie une intention `/app/ping` et vérifie que le serveur répond. */
  function ping(): void {
    const client = ensureSocket()

    if (!client.connected) {
      pushLog('error', 'Impossible d’envoyer /app/ping : canal non connecté')
      return
    }

    client.publish('/app/ping', { message: `hello depuis ${navigator.platform || 'le client'}` })
    pushLog('info', 'Ping envoyé sur /app/ping')
  }

  return {
    // état
    apiState,
    health,
    apiError,
    wsState,
    wsDetail,
    log,
    // dérivés
    isApiUp,
    isWsConnected,
    isWsBusy,
    apiVersion,
    databaseUp,
    // actions
    checkApi,
    connect,
    disconnect,
    ping,
    clearLog,
  }
})
