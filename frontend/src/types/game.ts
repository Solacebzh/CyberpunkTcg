/**
 * Miroir TypeScript des DTO WebSocket du backend.
 *
 * Référence : `docs/WEBSOCKET-PROTOCOL.md` (§4 lobby, §5 parties, §7 GameStateMessage)
 * et les records Java `com.cyberpunktcg.api.dto.ws.*`.
 *
 * Rappel du contrat : les champs `null` sont **omis** par le serveur
 * (`default-property-inclusion=non_null`), donc tout champ nullable est déclaré
 * optionnel (`?`) ici. Le serveur fait autorité : ces types décrivent ce que le
 * client *reçoit*, jamais une règle recalculée côté client.
 */

import type { CardColor, CardKeyword, CardType } from '@/types/card'

// --- Énumérations serveur (valeurs JSON exactes) -----------------------------

export type Phase = 'DRAW' | 'MAIN' | 'COMBAT' | 'END'
export type Zone = 'DECK' | 'HAND' | 'FIELD' | 'TRASH' | 'EDDIES_AREA' | 'LEGENDS_AREA' | 'REMOVED'
export type RoomStatus = 'WAITING' | 'PLAYING' | 'CLOSED'

/** Actions acceptées par `SEND /app/game/{gameId}/action` (doc §5.1). */
export type GameAction = 'PLAY_CARD' | 'ATTACK' | 'SELL_CARD' | 'END_TURN' | 'CONCEDE'

export type GameEventType =
  | 'TURN_STARTED'
  | 'TURN_ENDED'
  | 'PHASE_CHANGED'
  | 'CARD_DRAWN'
  | 'CARD_PLAYED'
  | 'CARD_SOLD'
  | 'LEGEND_FLIPPED'
  | 'ATTACK_DECLARED'
  | 'REACTION_WINDOW_OPENED'
  | 'REACTION_WINDOW_CLOSED'
  | 'UNIT_DEFEATED'
  | 'GIG_STOLEN'
  | 'GIG_ROLLED'
  | 'EFFECT_RESOLVED'
  | 'GAME_WON'

export type GameNoticeType = 'GAME_STARTED' | 'GAME_OVER' | 'PLAYER_DISCONNECTED' | 'PLAYER_RECONNECTED'

export type WsErrorCode =
  | 'INVALID_PSEUDO'
  | 'ROOM_NOT_FOUND'
  | 'ROOM_NOT_JOINABLE'
  | 'PSEUDO_TAKEN'
  | 'ALREADY_IN_ROOM'
  | 'GAME_IN_PROGRESS'
  | 'DECK_INVALID'
  | 'ILLEGAL_ACTION'
  | 'BAD_REQUEST'
  | 'GAME_NOT_FOUND'
  | 'ACTION_REJECTED'
  | 'INTERNAL_ERROR'

// --- État de partie ---------------------------------------------------------

/** Exemplaire de carte (`CardInstanceDTO`, doc §7.3). `cardId === 'hidden'` ⇒ carte masquée. */
export interface CardInstance {
  instanceId: string
  cardId: string
  name: string
  type: CardType
  color: CardColor
  baseCost?: number
  cost: number
  power?: number
  powerBonus: number
  damage: number
  streetCredThreshold?: number
  keywords: CardKeyword[]
  abilities: string[]
  ownerId: string
  zone: Zone
  faceDown: boolean
  exhausted: boolean
  summoningSickness: boolean
  attachedTo?: string
  attachments?: string[]
}

export interface TurnState {
  number: number
  activePlayerId: string
}

/** Fenêtre de réaction QUICK ouverte pour le défenseur (`null` hors attaque). */
export interface ReactionWindow {
  kind: string
  defendingPlayerId: string
  attackerInstanceId: string
}

export interface GameLogEntry {
  index: number
  type: GameEventType
  playerId?: string
  description: string
}

export interface PlayerState {
  playerId: string
  name: string
  connected: boolean
  deckCount: number
  hand: CardInstance[]
  field: CardInstance[]
  trash: CardInstance[]
  eddiesArea: CardInstance[]
  legendsArea: CardInstance[]
  /** Valeurs des dés Gig possédés (ex. `[2, 6]`). */
  gigs: number[]
  /** Dés de la Fixer Area pas encore lancés (`d4`…`d20`). */
  fixerDice: string[]
  gigCount: number
  streetCred: number
  eddies: number
  availableEddies: number
  costDiscount: number
  hasSoldThisTurn: boolean
}

export interface GameState {
  gameId: string
  phase: Phase
  gameOver: boolean
  winnerId?: string
  endReason?: string
  /** Pseudo du destinataire : permet de repérer « moi » parmi les deux sièges. */
  yourPlayerId: string
  turn: TurnState
  reactionWindow?: ReactionWindow | null
  /** Siège 0 = hôte, siège 1 = invité. */
  players: PlayerState[]
  log: GameLogEntry[]
  sequence: number
  createdAt: string
}

/** Enveloppe `GameStateMessage` diffusée sur `/topic/game/{gameId}/{pseudo}`. */
export interface GameStateMessage {
  type: 'STATE'
  gameId: string
  clientRequestId?: string
  state: GameState
  /** Événements produits par la dernière action (animations). */
  newEvents?: GameLogEntry[]
}

/** Notification publique `/topic/game/{gameId}`. */
export interface GameNotice {
  type: GameNoticeType
  gameId: string
  playerId?: string
  winnerId?: string
  endReason?: string
  reconnectDeadInSeconds?: number
}

/** Réponse publique de `SEND /app/ping` (canal de test, doc §3.1). */
export interface PongMessage {
  type: 'pong'
  echo: string
  serverTime: string
}

/** Erreur privée `/user/queue/errors`. */
export interface WsError {
  type: 'ERROR'
  code: WsErrorCode
  message: string
  destination?: string
  gameId?: string
  clientRequestId?: string
}

// --- Lobby ------------------------------------------------------------------

export interface RoomPlayer {
  pseudo: string
  seat: number
  deckCardCount: number
}

/** `LOBBY_STATE` sur `/topic/lobby/{code}` et `/user/queue/lobby`. */
export interface LobbyState {
  type: 'LOBBY_STATE'
  code: string
  name: string
  status: RoomStatus
  hostPseudo: string
  players: RoomPlayer[]
  gameId?: string
  createdAt: string
}

export interface RoomSummary {
  code: string
  name: string
  hostPseudo: string
  playerCount: number
}

/** `ROOMS` sur `/topic/rooms` et `/user/queue/rooms`. */
export interface RoomList {
  type: 'ROOMS'
  rooms: RoomSummary[]
}

// --- Commandes client → serveur --------------------------------------------

/** Corps de `SEND /app/game/{gameId}/action` (`GameCommandDTO`). */
export interface GameCommand {
  action: GameAction
  instanceId?: string | null
  targetInstanceId?: string | null
  targetPlayerId?: string | null
  chosen?: string | null
  revealedLegendIds?: string[] | null
  cardIds?: string[] | null
  dice?: string[] | null
  clientRequestId?: string | null
}

export interface CreateRoomRequest {
  roomName?: string | null
  deckCardIds?: string[] | null
}

export interface JoinRoomRequest {
  roomCode: string
  deckCardIds?: string[] | null
}

export interface LeaveRoomRequest {
  roomCode?: string | null
}

// --- Constantes et libellés UI ---------------------------------------------

/** `GameConstants.GIGS_TO_WIN` côté serveur. */
export const GIGS_TO_WIN = 7
/** `GameConstants.SALES_PER_TURN`. */
export const SALES_PER_TURN = 1
/** `GameConstants.STARTING_HAND_SIZE`. */
export const STARTING_HAND_SIZE = 6
/** Valeur `cardId` des cartes masquées par le serveur. */
export const HIDDEN_CARD_ID = 'hidden'

export const PHASE_ORDER: Phase[] = ['DRAW', 'MAIN', 'COMBAT', 'END']

export const PHASE_LABELS: Record<Phase, string> = {
  DRAW: 'Pioche',
  MAIN: 'Principale',
  COMBAT: 'Combat',
  END: 'Fin',
}

export const PHASE_HINTS: Record<Phase, string> = {
  DRAW: 'Pioche et lancer de Gig résolus automatiquement',
  MAIN: 'Joue des cartes, vends, déclare tes attaques',
  COMBAT: 'Résolution des attaques et réactions QUICK',
  END: 'Effets de fin de tour, puis passage au joueur suivant',
}

export const KEYWORD_LABELS: Record<CardKeyword, string> = {
  go_solo: 'Go solo',
  blocker: 'Blocker',
  quick: 'Quick',
  flip: 'Flip',
  play: 'Play',
  attack: 'Attack',
}

export const KEYWORD_HINTS: Record<CardKeyword, string> = {
  go_solo: 'Peut attaquer le tour où elle est jouée',
  blocker: 'Doit être attaquée en priorité et interdit le vol direct de Gig',
  quick: 'Jouable pendant la fenêtre de réaction adverse',
  flip: 'Se révèle depuis la Legends Area (effet FLIP)',
  play: 'Effet déclenché quand la carte est jouée',
  attack: 'Effet déclenché quand la carte attaque',
}

export const ZONE_LABELS: Record<Zone, string> = {
  DECK: 'Pioche',
  HAND: 'Main',
  FIELD: 'Field',
  TRASH: 'Défausse',
  EDDIES_AREA: 'Eddies',
  LEGENDS_AREA: 'Legends',
  REMOVED: 'Hors jeu',
}

export const EVENT_LABELS: Record<GameEventType, string> = {
  TURN_STARTED: 'Début de tour',
  TURN_ENDED: 'Fin de tour',
  PHASE_CHANGED: 'Phase',
  CARD_DRAWN: 'Pioche',
  CARD_PLAYED: 'Carte jouée',
  CARD_SOLD: 'Vente',
  LEGEND_FLIPPED: 'Legend révélée',
  ATTACK_DECLARED: 'Attaque',
  REACTION_WINDOW_OPENED: 'Réaction ouverte',
  REACTION_WINDOW_CLOSED: 'Réaction fermée',
  UNIT_DEFEATED: 'Unit vaincue',
  GIG_STOLEN: 'Gig volé',
  GIG_ROLLED: 'Gig lancé',
  EFFECT_RESOLVED: 'Effet',
  GAME_WON: 'Victoire',
}

/** Destinations STOMP — strictement alignées sur `WsDestinations.java` (doc §2). */
export const Ws = {
  ping: '/app/ping',
  pong: '/topic/pong',
  lobbyCreate: '/app/lobby.create',
  lobbyJoin: '/app/lobby.join',
  lobbyLeave: '/app/lobby.leave',
  lobbyList: '/app/lobby.list',
  roomsTopic: '/topic/rooms',
  roomsQueue: '/user/queue/rooms',
  lobbyQueue: '/user/queue/lobby',
  errorsQueue: '/user/queue/errors',
  lobbyTopic: (code: string) => `/topic/lobby/${code}`,
  gameTopic: (gameId: string) => `/topic/game/${gameId}`,
  gameStateTopic: (gameId: string, pseudo: string) => `/topic/game/${gameId}/${pseudo}`,
  gameAction: (gameId: string) => `/app/game/${gameId}/action`,
  gameResync: (gameId: string) => `/app/game/${gameId}/resync`,
} as const

/** Motif de pseudo imposé par le serveur (doc §1.2). */
export const PSEUDO_PATTERN = /^[A-Za-z0-9_\-À-ÿ]{2,20}$/

export function isValidPseudo(value: string): boolean {
  return PSEUDO_PATTERN.test(value.trim())
}

export function isHiddenCard(card: CardInstance): boolean {
  return card.cardId === HIDDEN_CARD_ID
}
