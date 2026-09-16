/**
 * Types des endpoints de debug (feature 6.5).
 *
 * Ces routes (`/api/debug/...`) ne sont actives que sous les profils Spring
 * `test` et `dev` : en production elles répondent 404, le panneau de debug
 * affiche alors l'erreur sans casser la partie. Voir `docs/DEBUG-GUIDE.md`.
 */
import type { CardInstance, GameActionLogEntry, GameLogEntry, PendingAttack } from '@/types/game'

/** État complet et **non masqué** d'un joueur (`GET /api/debug/game/{id}/player/{pid}`). */
export interface DebugPlayerState {
  playerId: string
  name: string
  eddies: number
  availableEddies: number
  costDiscount: number
  hasSoldThisTurn: boolean
  gigs: number[]
  /** Type du dé de chaque Gig (aligné sur `gigs`). */
  gigDice?: string[]
  /** Identifiant stable de chaque dé Gig actif (Mini-Feature 6 : vol choisi). */
  gigDieIds?: string[]
  gigCount: number
  streetCred: number
  fixerDice: string[]
  legendsReady: number
  legendsSpent: number
  ramCeilings: Record<string, number>
  hasLegendCeiling: boolean
  deckCount: number
  hand: CardInstance[]
  deck: CardInstance[]
  field: CardInstance[]
  trash: CardInstance[]
  eddiesArea: CardInstance[]
  legendsArea: CardInstance[]
}

/** Fenêtre de réaction ouverte (debug). */
export interface DebugReactionWindow {
  kind: string
  defendingPlayerId: string
  attackerInstanceId: string
}

/** `GET /api/debug/game/{gameId}` : tout l'état, sans masque. */
export interface DebugGameState {
  gameId: string
  turnNumber: number
  phase: string
  /** Sous-étape de la phase DRAW interactive (Mini-Feature 5), absente hors DRAW. */
  drawStep?: string | null
  activePlayerId: string
  gameOver: boolean
  winnerId?: string | null
  endReason?: string | null
  seed: number
  createdAt: string
  reactionWindow?: DebugReactionWindow | null
  /** Attaque en cours de résolution (Mini-Feature 6), absente hors combat. */
  pendingAttack?: PendingAttack | null
  players: DebugPlayerState[]
  log: GameLogEntry[]
  gameLog: GameActionLogEntry[]
}

/** Phases forçables via `POST /api/debug/game/{gameId}/force-phase`. */
export type DebugPhase = 'DRAW' | 'MAIN' | 'COMBAT' | 'END'
