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
/**
 * Sous-étapes de la phase `DRAW` interactive (Mini-Feature 5, `DrawStep.java`).
 * Seules `AWAITING_DRAW` (cliquer sur la pioche) et `AWAITING_DIE_SELECT`
 * (choisir un dé Gig) attendent une action du joueur actif ; les autres sont
 * transitoires côté serveur.
 */
export type DrawStep = 'DRAW_START' | 'AWAITING_DRAW' | 'AWAITING_DIE_SELECT' | 'ROLLING_DIE' | 'DRAW_COMPLETE'
/**
 * Sous-étapes de résolution d'une attaque (Mini-Feature 6, `CombatStep.java`).
 * `AWAITING_BLOCK` = fenêtre « Utiliser Blocker ? » côté défenseur ;
 * `AWAITING_STEAL_CHOICE` = modale « Choisissez M dé(s) Gig à voler » côté attaquant.
 */
export type CombatStep = 'AWAITING_BLOCK' | 'AWAITING_STEAL_CHOICE'

export type Zone = 'DECK' | 'HAND' | 'FIELD' | 'TRASH' | 'EDDIES_AREA' | 'LEGENDS_AREA' | 'REMOVED'
export type RoomStatus = 'WAITING' | 'PLAYING' | 'CLOSED'

/** Actions acceptées par `SEND /app/game/{gameId}/action` (doc §5.1). */
export type GameAction =
  | 'PLAY_CARD'
  | 'ATTACK'
  | 'SELL_CARD'
  /** Mini-Feature 4 (R4) : incliner une Legend ou une carte de l'Eddies Area pour +1 Eddie. */
  | 'SPEND_RESOURCE'
  /** Aliases historiques de `SPEND_RESOURCE` (cible unique dans une zone précise). */
  | 'SPEND_LEGEND'
  | 'SPEND_EDDIES'
  /** Mini-Feature 5 : clic sur la pioche (phase `DRAW`, étape `AWAITING_DRAW`). */
  | 'DRAW_CARD'
  /** Mini-Feature 5 : choix du dé Gig (`dice: ['d6']`, étape `AWAITING_DIE_SELECT`). */
  | 'SELECT_DIE'
  /**
   * Mini-Feature 6 : choix des dés Gigs volés après une attaque directe non
   * bloquée — exactement `M` identifiants dans `dice` (plafond strict).
   */
  | 'STEAL_GIG'
  /**
   * Mini-Feature 6 : le défenseur intercepte avec un ou plusieurs `{Blocker}`
   * prêts (`cardIds`, ordre significatif — le DERNIER encaisse les dégâts).
   */
  | 'USE_BLOCKER'
  /** Mini-Feature 6 : le défenseur renonce à intercepter. */
  | 'DECLINE_BLOCK'
  | 'END_TURN'
  | 'CONCEDE'

export type GameEventType =
  | 'TURN_STARTED'
  | 'TURN_ENDED'
  | 'PHASE_CHANGED'
  | 'CARD_DRAWN'
  | 'CARD_PLAYED'
  | 'CARD_SOLD'
  | 'LEGEND_FLIPPED'
  | 'ATTACK_DECLARED'
  /** Mini-Feature 6 : un `{Blocker}` dépensé redirige l'attaque vers lui. */
  | 'ATTACK_BLOCKED'
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
  /** Mini-Feature 9D : aucun `deckId` envoyé à la création/rejointe. */
  | 'NO_DECK_SELECTED'
  /** Mini-Feature 9D : le deck n'existe pas ou n'appartient pas au joueur. */
  | 'DECK_NOT_OWNED'
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
  /** Sous-étape de la phase `DRAW` interactive ; absent hors `DRAW`. */
  drawStep?: DrawStep | null
}

/** Fenêtre de réaction QUICK ouverte pour le défenseur (`null` hors attaque). */
export interface ReactionWindow {
  kind: string
  defendingPlayerId: string
  attackerInstanceId: string
}

/**
 * Attaque en cours de résolution (`PendingAttackDTO`, Mini-Feature 6) — `null`
 * quand aucun combat n'attend de décision de joueur.
 *
 * `quota` est le quota théorique `N = (power / 10) + 1` (0 si power ≤ 0) et
 * `stealableCount` le **plafond strict** `M = min(N, dés Gigs actifs du
 * défenseur)` : ce sont les `M` dés que l'attaquant doit choisir.
 */
export interface PendingAttack {
  attackerPlayerId: string
  defendingPlayerId: string
  attackerInstanceId: string
  /** `null` pour une attaque directe vers la Gig Area (vol de dés). */
  targetInstanceId?: string | null
  step: CombatStep
  quota: number
  stealableCount: number
  /** Blockers déjà dépensés pour cette attaque (blocage multiple). */
  blockerInstanceIds?: string[]
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
  /**
   * Type du dé de chaque Gig, aligné sur `gigs` (ex. `['d4', 'd8']`, `'?'` pour
   * un Gig obtenu hors lancer). Optionnel : anciens serveurs / mocks.
   */
  gigDice?: string[]
  /**
   * Identifiant stable de chaque dé Gig actif, aligné sur `gigs` (Mini-Feature 6) :
   * c'est l'identifiant envoyé dans `STEAL_GIG` pour choisir les dés volés.
   */
  gigDieIds?: string[]
  /**
   * Dés de la Fixer Area pas encore lancés (`d4`…`d20`) : ils ne sont
   * **jamais** volables (plafond strict, Mini-Feature 6).
   */
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
  /** Attaque en cours de résolution (Mini-Feature 6) ; absent = combat résolu. */
  pendingAttack?: PendingAttack | null
  /** Siège 0 = hôte, siège 1 = invité. */
  players: PlayerState[]
  log: GameLogEntry[]
  /**
   * Dernières entrées du journal de diagnostic (feature 6.5), pour amorcer le
   * panneau de debug quand on rejoint une partie en cours.
   */
  gameLog?: GameActionLogEntry[]
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
  /**
   * Mini-Feature 9D : identifiant du deck sauvegardé sélectionné par le joueur
   * (clé vers `decks.id`). `null` tant qu'aucun deck n'a été choisi.
   */
  deckId?: number | null
  /**
   * Taille effective du deck (mise à jour à la sélection, valeur indicative).
   * Conservé pour l'affichage historique côté UI ; le backend n'envoie plus
   * ce champ, qui devient facultatif.
   */
  deckCardCount?: number
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
  /**
   * Identifiant du deck sauvegardé (Mini-Feature 9D). Le client le tire de
   * `GET /api/decks` et l'envoie dans le payload STOMP ; le backend vérifie
   * qu'il existe et appartient au joueur avant de rejoindre la partie.
   * Requis : envoyer un payload sans `deckId` aboutit à un rejet `NO_DECK_SELECTED`.
   */
  deckId?: number | null
}

export interface JoinRoomRequest {
  roomCode: string
  /** Voir {@link CreateRoomRequest#deckId} — requis pour rejoindre un salon. */
  deckId?: number | null
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
  DRAW: 'Cartes redressées ; pioche ta carte puis choisis ton dé Gig',
  MAIN: 'Joue des cartes, vends, déclare tes attaques',
  COMBAT: 'Résolution des attaques et réactions QUICK',
  END: 'Effets de fin de tour, puis passage au joueur suivant',
}

/** Libellés des sous-étapes de la phase DRAW (Mini-Feature 5), côté joueur actif. */
export const DRAW_STEP_LABELS: Record<DrawStep, string> = {
  DRAW_START: 'Votre tour commence !',
  AWAITING_DRAW: 'PIOCHER VOTRE CARTE',
  AWAITING_DIE_SELECT: 'CHOISIS UN DÉ',
  ROLLING_DIE: 'Lancer du dé…',
  DRAW_COMPLETE: 'Phase de pioche terminée',
}

export const DRAW_STEP_HINTS: Record<DrawStep, string> = {
  DRAW_START: 'Cartes redressées, Eddies à 0. Cliquez pour piocher.',
  AWAITING_DRAW: 'Votre tour commence ! Cliquez sur votre pioche pour prendre la carte du dessus.',
  AWAITING_DIE_SELECT: 'Prenez un dé de votre Fixer Area : n’importe lequel sauf le d20, toujours lancé en dernier.',
  ROLLING_DIE: 'Le serveur lance le dé choisi…',
  DRAW_COMPLETE: 'Passage à la phase Principale.',
}

/** Le d20 n'est sélectionnable que lorsqu'il est le dernier dé de la Fixer Area. */
export const LAST_FIXER_DIE = 'd20'

/** `GameConstants.POWER_PER_EXTRA_GIG` : un Gig volé en plus par tranche de 10 de puissance. */
export const POWER_PER_EXTRA_GIG = 10

/**
 * Un dé Gig **actif** (déjà lancé) de la Gig Area : les seuls volables
 * (Mini-Feature 6). `id` vient de `PlayerState.gigDieIds` et sert de cible à
 * l'action `STEAL_GIG`.
 */
export interface GigDieView {
  id: string
  /** Type du dé (`d4`…`d20`), `'?'` pour un Gig injecté hors lancer. */
  die: string
  value: number
}

/**
 * Puissance effective d'une carte pour l'affichage (`power` + `powerBonus`,
 * approche de `GameState.totalPowerFor` qui ajoute aussi les Gears équipés).
 * Le serveur reste seul juge du combat et du quota.
 */
export function effectivePower(card: CardInstance | null | undefined): number {
  if (!card) return 0
  return Math.max(0, (card.power ?? 0) + (card.powerBonus ?? 0))
}

/**
 * Quota théorique de Gigs volés : `N = power <= 0 ? 0 : (power / 10) + 1`
 * (miroir de `RuleEngine.calculateQuota`, pour l'affichage — le serveur fait foi).
 */
export function stealQuota(power: number): number {
  if (!Number.isFinite(power) || power <= 0) return 0
  return Math.floor(power / POWER_PER_EXTRA_GIG) + 1
}

/**
 * Plafond strict : `M = min(N, dés Gigs actifs du défenseur)` (miroir de
 * `RuleEngine.calculateActualStealable`). On ne crée jamais de dé et on ne vole
 * jamais dans la Fixer Area.
 */
export function stealableDiceCount(power: number, activeDiceCount: number): number {
  return Math.max(0, Math.min(stealQuota(power), Math.max(0, activeDiceCount)))
}

/**
 * Dés Gigs actifs d'un joueur, alignés (valeurs + types + identifiants).
 * Les serveurs/mock anciens qui n'envoient pas `gigDieIds` reçoivent un
 * identifiant de repli indexé (le serveur reste seul juge).
 */
export function activeGigsOf(player: PlayerState | null | undefined): GigDieView[] {
  if (!player) return []
  const dice = player.gigDice ?? []
  const ids = player.gigDieIds ?? []
  return player.gigs.map((value, index) => ({
    id: ids[index] ?? `gig-${index}`,
    die: dice[index] ?? '?',
    value,
  }))
}

/** Libellés des étapes de combat (Mini-Feature 6), côté joueur concerné. */
export const COMBAT_STEP_LABELS: Record<CombatStep, string> = {
  AWAITING_BLOCK: 'UTILISER BLOCKER ?',
  AWAITING_STEAL_CHOICE: 'CHOISIS LES DÉS À VOLER',
}

export const COMBAT_STEP_HINTS: Record<CombatStep, string> = {
  AWAITING_BLOCK:
    'Ton adversaire attaque : dépense un ou plusieurs Blockers prêts pour rediriger l’attaque (seul le dernier Blocker choisi encaisse les dégâts), ou renonce.',
  AWAITING_STEAL_CHOICE:
    'Choisis exactement M dés Gigs actifs à voler chez ton adversaire — chaque dé conserve son type et sa valeur.',
}

/**
 * Miroir de `Player.selectableFixerDice()` : tous les dés restants sauf le d20,
 * ou `['d20']` quand il est le dernier. Sert uniquement à griser l'UI — le
 * serveur reste seul juge.
 */
export function selectableFixerDice(fixerDice: readonly string[]): string[] {
  const others = fixerDice.filter((die) => die !== LAST_FIXER_DIE)
  if (others.length > 0) return others
  return fixerDice.includes(LAST_FIXER_DIE) ? [LAST_FIXER_DIE] : []
}

export const KEYWORD_LABELS: Record<CardKeyword, string> = {
  go_solo: 'Go solo',
  blocker: 'Blocker',
  quick: 'Quick',
  flip: 'Flip',
  play: 'Play',
  attack: 'Attack',
  haste: 'Haste',
}

export const KEYWORD_HINTS: Record<CardKeyword, string> = {
  go_solo: 'Peut attaquer le tour où elle est jouée',
  blocker:
    'Le défenseur peut la dépenser pour rediriger une attaque vers elle (blocage multiple : seul le dernier Blocker encaisse)',
  quick: 'Jouable pendant la fenêtre de réaction adverse',
  flip: 'Se révèle depuis la Legends Area (effet FLIP)',
  play: 'Effet déclenché quand la carte est jouée',
  attack: 'Effet déclenché quand la carte attaque',
  haste: 'Ignore le mal d’invocation (peut attaquer le tour où elle est jouée)',
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
  ATTACK_BLOCKED: 'Blocage',
  REACTION_WINDOW_OPENED: 'Réaction ouverte',
  REACTION_WINDOW_CLOSED: 'Réaction fermée',
  UNIT_DEFEATED: 'Unit vaincue',
  GIG_STOLEN: 'Gig volé',
  GIG_ROLLED: 'Gig lancé',
  EFFECT_RESOLVED: 'Effet',
  GAME_WON: 'Victoire',
}

/** Verdict d'une entrée du journal de diagnostic (feature 6.5). */
export type GameActionResult = 'SUCCESS' | 'FAILED' | 'ILLEGAL' | 'INFO'

/**
 * Entrée du **journal de diagnostic** `/topic/game/{gameId}/log`.
 *
 * Différente de {@link GameLogEntry} (journal public des `GameEvent`) : elle
 * porte le verdict (succès / refusé), la phase, le tour et un contexte chiffré.
 * Seul le panneau de debug l'affiche.
 */
export interface GameActionLogEntry {
  index: number
  timestamp: string
  turnNumber: number
  phase: string
  playerId?: string | null
  actionType: string
  description: string
  result: GameActionResult
  details?: Record<string, unknown>
}

/** Enveloppe diffusée sur `/topic/game/{gameId}/log` (`type: "LOG"`). */
export interface GameLogMessage {
  type: 'LOG'
  gameId: string
  entries: GameActionLogEntry[]
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
  /**
   * Journal de diagnostic d'une partie (feature 6.5). Attention : ce chemin
   * partage le préfixe des états personnels ; le discriminant est `type`
   * (`LOG` ici, `STATE` pour les états).
   */
  gameLogTopic: (gameId: string) => `/topic/game/${gameId}/log`,
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
