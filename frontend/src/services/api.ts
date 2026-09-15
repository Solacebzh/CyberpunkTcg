/**
 * Accès à l'API REST du backend.
 *
 * Les URLs sont *relatives* par défaut (`/api/...`) : en développement, le proxy Vite
 * les redirige vers Spring Boot, et en production le frontend est servi derrière
 * le même domaine que l'API. Renseigner `VITE_API_BASE_URL` n'est utile que pour
 * pointer vers un backend distant.
 */

import type { CardColor, CardType, GameCard } from '@/types/card'
import type { DebugGameState, DebugPhase, DebugPlayerState } from '@/types/debug'

export interface HealthResponse {
  status: string
  service: string
  version: string
  database: string
  timestamp: string
}

export interface CardFilters {
  type?: CardType
  color?: CardColor
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')

export function apiUrl(path: string): string {
  const normalized = path.startsWith('/') ? path : `/${path}`
  return `${API_BASE_URL}${normalized}`
}

/** Erreur réseau/HTTP homogène pour l'affichage dans l'UI. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function request<T>(path: string, init: RequestInit = {}, timeoutMs = 8000): Promise<T> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)

  try {
    const response = await fetch(apiUrl(path), {
      headers: { Accept: 'application/json', ...(init.headers ?? {}) },
      signal: controller.signal,
      ...init,
    })

    if (!response.ok) {
      throw new ApiError(`${init.method ?? 'GET'} ${path} → HTTP ${response.status}`, response.status)
    }

    return (await response.json()) as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError(`Délai dépassé (${timeoutMs} ms) en appelant ${path}`)
    }
    throw new ApiError(error instanceof Error ? error.message : `Appel ${path} impossible`)
  } finally {
    window.clearTimeout(timer)
  }
}

/** Sonde de santé du backend (et de sa base de données). */
export function fetchHealth(): Promise<HealthResponse> {
  return request<HealthResponse>('/api/health', { method: 'GET' }, 5000)
}

/**
 * État complet **non masqué** d'une partie (debug). Route active uniquement sous
 * les profils Spring `test`/`dev` : ailleurs, l'appel renvoie 404.
 *
 * @param logs nombre d'entrées du journal de diagnostic (défaut serveur : 20)
 */
export function fetchDebugGameState(gameId: string, logs = 50): Promise<DebugGameState> {
  return request<DebugGameState>(
    `/api/debug/game/${encodeURIComponent(gameId)}?logs=${logs}`,
    { method: 'GET' },
  )
}

/** Vue détaillée d'un joueur en debug (main et pioche visibles). */
export function fetchDebugPlayerState(gameId: string, playerId: string): Promise<DebugPlayerState> {
  return request<DebugPlayerState>(
    `/api/debug/game/${encodeURIComponent(gameId)}/player/${encodeURIComponent(playerId)}`,
    { method: 'GET' },
  )
}

/** Force la phase courante (test d'une règle sans rejouer la partie). */
export function forceDebugPhase(
  gameId: string,
  phase: DebugPhase,
  playerId?: string | null,
): Promise<DebugGameState> {
  return request<DebugGameState>(`/api/debug/game/${encodeURIComponent(gameId)}/force-phase`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ phase, playerId: playerId ?? null }),
  })
}

/** Catalogue de cartes, éventuellement filtré par type et/ou couleur. */
export function fetchCards(filters: CardFilters = {}): Promise<GameCard[]> {
  const params = new URLSearchParams()
  if (filters.type) params.set('type', filters.type)
  if (filters.color) params.set('color', filters.color)
  const query = params.size > 0 ? `?${params.toString()}` : ''
  return request<GameCard[]>(`/api/cards${query}`, { method: 'GET' })
}
