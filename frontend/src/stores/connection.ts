/**
 * Store de connexion : santé de l'API REST + état du canal STOMP partagé.
 *
 * Depuis la feature 05, le client STOMP est **unique** pour toute la page :
 * ce store lit l'état exposé par `useGameSocket()` (le même canal que le lobby
 * et la partie) au lieu d'ouvrir sa propre connexion. Le badge d'accueil reste
 * donc un simple indicateur, et le bouton « Quitter » est neutralisé en partie.
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { useGameSocket } from '@/composables/useGameSocket'
import { fetchHealth, type HealthResponse } from '@/services/api'
import type { ConnectionState } from '@/services/socket'
import { useUiStore } from '@/stores/ui'

export type ApiState = 'unknown' | 'checking' | 'up' | 'down'

export interface LogEntry {
  at: string
  kind: 'info' | 'success' | 'error'
  message: string
}

const MAX_LOG_ENTRIES = 30

export const useConnectionStore = defineStore('connection', () => {
  const socket = useGameSocket()
  const ui = useUiStore()

  // --- État ---
  const apiState = ref<ApiState>('unknown')
  const health = ref<HealthResponse | null>(null)
  const apiError = ref<string | null>(null)
  const log = ref<LogEntry[]>([])

  /** Partie en cours sur le canal partagé : on évite de le fermer par erreur. */
  const channelInUse = ref(false)

  let pongWired = false

  // --- Dérivés ---
  const wsState = computed<ConnectionState>(() => socket.status.value)
  const wsDetail = computed<string | null>(() => socket.statusDetail.value)
  const isApiUp = computed(() => apiState.value === 'up')
  const isWsConnected = computed(() => socket.isConnected.value)
  const isWsBusy = computed(() => socket.status.value === 'connecting')
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

  /** Signale qu'un écran (partie) a besoin du canal en permanence. */
  function markChannelInUse(inUse: boolean): void {
    channelInUse.value = inUse
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

  // --- WebSocket STOMP (canal partagé) ---
  function wirePong(): void {
    if (pongWired) return
    pongWired = true
    socket.onPong((pong) => {
      pushLog('success', `pong reçu du serveur — écho « ${pong?.echo ?? '?'} » à ${pong?.serverTime ?? '?'}`)
    })
  }

  function connect(): void {
    wirePong()
    socket.connect()
    pushLog('info', `Ouverture du canal STOMP (pseudo ${socket.pseudo.value})`)
  }

  function disconnect(): void {
    if (channelInUse.value) {
      ui.warn('Canal utilisé par une partie en cours : abandonne la partie avant de le fermer')
      return
    }
    socket.disconnect()
    pushLog('info', 'Déconnexion demandée par le joueur')
  }

  /** Envoie une intention `/app/ping` et vérifie que le serveur répond. */
  function ping(): void {
    wirePong()
    if (!socket.isConnected.value) {
      pushLog('error', 'Impossible d’envoyer /app/ping : canal non connecté')
      return
    }
    socket.ping(`hello depuis ${socket.pseudo.value}`)
    pushLog('info', 'Ping envoyé sur /app/ping')
  }

  return {
    // état
    apiState,
    health,
    apiError,
    log,
    channelInUse,
    // dérivés
    wsState,
    wsDetail,
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
    markChannelInUse,
  }
})
