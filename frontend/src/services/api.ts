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
import { clearStoredAuth, getAuthToken } from '@/services/authToken'

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
    /**
     * Infractions détaillées renvoyées par le serveur (`ApiErrorResponse.errors`) :
     * une ligne par règle violée, rédigée en français et affichable telle quelle.
     */
    readonly details: string[] = [],
    /** Message synthétique du serveur, uniquement si le corps suit notre contrat. */
    readonly serverMessage?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/** Corps d'erreur produit par `RestExceptionHandler` côté Spring. */
interface ApiErrorBody {
  status?: number
  code?: string
  message?: string
  errors?: unknown
}

/**
 * Lit un corps d'erreur sans jamais lever : certains serveurs (ou mocks)
 * renvoient un corps vide ou non JSON.
 */
async function readErrorBody(response: Response): Promise<ApiErrorBody | null> {
  try {
    const text = await response.text()
    if (!text.trim()) return null
    return JSON.parse(text) as ApiErrorBody
  } catch {
    return null
  }
}

function detailsOf(body: ApiErrorBody | null): string[] {
  if (!body || !Array.isArray(body.errors)) return []
  return body.errors
    .map((error) => (typeof error === 'string' ? error : String(error)))
    .filter((error) => error.trim().length > 0)
}

export async function request<T>(path: string, init: RequestInit = {}, timeoutMs = 8000): Promise<T> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), timeoutMs)
  const token = getAuthToken()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)

  try {
    const response = await fetch(apiUrl(path), {
      ...init,
      headers,
      signal: controller.signal,
    })

    if (!response.ok) {
      // Un JWT expiré ne doit pas laisser l'interface dans un faux état connecté.
      if (response.status === 401 && !path.startsWith('/api/auth/')) clearStoredAuth()
      const body = await readErrorBody(response)
      throw new ApiError(
        `${init.method ?? 'GET'} ${path} → HTTP ${response.status}`,
        response.status,
        detailsOf(body),
        // Le message par défaut de Spring (« Not Found ») n'a rien d'exploitable :
        // on ne remonte que les corps suivant notre contrat (code métier présent).
        body?.code ? body.message : undefined,
      )
    }

    // `204 No Content` (suppression d'un deck) : aucun corps à décoder.
    if (response.status === 204) return undefined as T

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

// ---------------------------------------------------------------------------
// Decks sauvegardés (Mini-Feature 9C) — routes protégées par JWT
// ---------------------------------------------------------------------------

/** Deck tel que persisté par le backend (`DeckResponse`). */
export interface SavedDeck {
  id: number
  name: string
  userId: number
  /** Identifiants de cartes, ordre et exemplaires compris. */
  cardIds: string[]
  totalCards: number
  createdAt: string | null
  updatedAt: string | null
}

/** Payload de création / mise à jour (`DeckRequest`). */
export interface DeckPayload {
  name: string
  cardIds: string[]
}

/**
 * Normalise une réponse serveur : une entrée qui n'a pas la forme d'un deck
 * (identifiant numérique, nom, liste de cartes) est ignorée plutôt que de
 * casser l'affichage.
 */
function toSavedDeck(raw: unknown): SavedDeck | null {
  if (!raw || typeof raw !== 'object') return null
  const value = raw as Record<string, unknown>
  if (typeof value.id !== 'number' || typeof value.name !== 'string') return null
  const cardIds = Array.isArray(value.cardIds)
    ? value.cardIds.filter((id): id is string => typeof id === 'string')
    : []
  return {
    id: value.id,
    name: value.name,
    userId: typeof value.userId === 'number' ? value.userId : 0,
    cardIds,
    totalCards: typeof value.totalCards === 'number' ? value.totalCards : cardIds.length,
    createdAt: typeof value.createdAt === 'string' ? value.createdAt : null,
    updatedAt: typeof value.updatedAt === 'string' ? value.updatedAt : null,
  }
}

function jsonBody(payload: DeckPayload): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  }
}

/** « Mes decks » : uniquement ceux du compte porteur du JWT. */
export async function fetchDecks(): Promise<SavedDeck[]> {
  const decks = await request<unknown>('/api/decks', { method: 'GET' })
  if (!Array.isArray(decks)) return []
  return decks.map(toSavedDeck).filter((deck): deck is SavedDeck => deck !== null)
}

/** Crée un deck. Le serveur renvoie `400` + `errors` si les règles sont violées. */
export async function createDeck(payload: DeckPayload): Promise<SavedDeck> {
  const created = await request<unknown>('/api/decks', jsonBody(payload))
  const deck = toSavedDeck(created)
  if (!deck) throw new ApiError('Réponse inattendue du serveur lors de la création du deck')
  return deck
}

/** Met à jour un deck existant (mêmes règles de validation côté serveur). */
export async function updateDeck(deckId: number, payload: DeckPayload): Promise<SavedDeck> {
  const updated = await request<unknown>(`/api/decks/${deckId}`, { ...jsonBody(payload), method: 'PUT' })
  const deck = toSavedDeck(updated)
  if (!deck) throw new ApiError('Réponse inattendue du serveur lors de la mise à jour du deck')
  return deck
}

/** Supprime un deck (`204` : corps vide). */
export async function deleteDeck(deckId: number): Promise<void> {
  await request<unknown>(`/api/decks/${deckId}`, { method: 'DELETE' })
}
