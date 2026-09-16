/**
 * Serveur STOMP **simulé** — outil de développement et de test du frontend.
// Aligné sur les règles officielles corrigées (Feature 5.5) : victoire 7 Gigs au début du tour, vente 1/tour, QUICK uniquement, BLOCKER intercepte, FLIP pour Legends, pas de coût Eddies.
 *
 * ⚠️ Ce n'est PAS le moteur de règles : le serveur de référence est le backend
 * Spring (`backend/`, voir docs/RULE-ENGINE.md). Ce module reproduit uniquement
 * le **contrat de transport** décrit dans docs/WEBSOCKET-PROTOCOL.md
 * (destinations, enveloppes, masquage des secrets, séquences, erreurs) et un
 * sous-ensemble de règles suffisant pour exercer l'UI :
 *   pose d'Unit/Program/Gear, retournement de Legend, vente (+1 Eddie),
 *   attaque (comparaison de puissances, interception BLOCKER, vol de Gig),
 *   fin de tour (pioche + lancer de dé Gig + victoire à 7 Gigs).
 *
 * La **présence** est simulée comme le backend (`ws/GamePresenceService`) : la
 * fermeture du socket libère le siège d'un salon WAITING et marque le joueur
 * déconnecté dans une partie en cours (`PLAYER_DISCONNECTED`). Le minuteur de
 * forfait de 120 s n'est pas chronométré ici : appelle `forfeitOfflinePlayer()`
 * pour simuler son expiration.
 *
 * Le module est volontairement **indépendant du transport** :
 * `createSession(send)` renvoie un objet `receive(text)` alimenté soit par une
 * vraie WebSocket (`tools/mock-server.mjs`), soit par une fausse socket en
 * mémoire (`src/__tests__/helpers/stompHarness.ts`).
 *
 * Écrit en TypeScript « strippable » (pas d'enum, pas de parameter properties)
 * pour que Node puisse l'exécuter directement (`node tools/mock-server.mjs`).
 */

// --- Types ------------------------------------------------------------------

export type CardType = 'legend' | 'unit' | 'program' | 'gear'
export type CardColor = 'red' | 'green' | 'blue' | 'yellow'
export type ZoneName = 'DECK' | 'HAND' | 'FIELD' | 'TRASH' | 'EDDIES_AREA' | 'LEGENDS_AREA'
type ZoneKey = 'deck' | 'hand' | 'field' | 'trash' | 'eddiesArea' | 'legendsArea'

export interface MockCard {
  id: string
  name: string
  type: CardType
  color: CardColor
  cost?: number | null
  power?: number | null
  streetCred?: number | null
  keywords?: string[]
  abilities?: string[]
  [key: string]: unknown
}

interface Instance {
  instanceId: string
  cardId: string
  name: string
  type: CardType
  color: CardColor
  baseCost: number | null
  cost: number
  power: number | null
  powerBonus: number
  damage: number
  streetCredThreshold: number | null
  keywords: string[]
  abilities: string[]
  ownerId: string
  zone: ZoneName
  faceDown: boolean
  exhausted: boolean
  summoningSickness: boolean
  attachedTo: string | null
  attachments: string[]
}

interface MockPlayer {
  playerId: string
  name: string
  connected: boolean
  deck: Instance[]
  hand: Instance[]
  field: Instance[]
  trash: Instance[]
  eddiesArea: Instance[]
  legendsArea: Instance[]
  gigs: number[]
  /** Type du dé de chaque Gig, aligné sur `gigs` (Mini-Feature 5). */
  gigDice: string[]
  /**
   * Identifiant stable de chaque dé Gig actif, aligné sur `gigs`/`gigDice`
   * (Mini-Feature 6) : c'est la cible de l'action `STEAL_GIG`.
   */
  gigDieIds: string[]
  fixerDice: string[]
  eddies: number
  costDiscount: number
  hasSoldThisTurn: boolean
}

/** Sous-étapes de la phase DRAW interactive (miroir de `DrawStep.java`). */
type DrawStep = 'DRAW_START' | 'AWAITING_DRAW' | 'AWAITING_DIE_SELECT' | 'ROLLING_DIE' | 'DRAW_COMPLETE'

/** Sous-étapes de résolution d'une attaque (miroir de `CombatStep.java`, Mini-Feature 6). */
type CombatStep = 'AWAITING_BLOCK' | 'AWAITING_STEAL_CHOICE'

/**
 * Attaque suspendue (miroir de `PendingAttackDTO`) : décision de blocage du
 * défenseur, puis choix par l'attaquant des `M` dés Gigs à voler.
 * `quota` = N théorique, `stealableCount` = plafond strict M.
 */
interface PendingAttackState {
  attackerPlayerId: string
  defendingPlayerId: string
  attackerInstanceId: string
  targetInstanceId: string | null
  step: CombatStep
  quota: number
  stealableCount: number
  blockerInstanceIds: string[]
}

interface LogEntry {
  index: number
  type: string
  playerId: string
  description: string
}

/** Entrée du journal de diagnostic (feature 6.5), alignée sur `GameActionLogDTO`. */
interface ActionLogEntry {
  index: number
  timestamp: string
  turnNumber: number
  phase: string
  playerId: string | null
  actionType: string
  description: string
  result: 'SUCCESS' | 'FAILED' | 'ILLEGAL' | 'INFO'
  details: Record<string, unknown>
}

interface MockGame {
  gameId: string
  phase: 'DRAW' | 'MAIN' | 'COMBAT' | 'END'
  gameOver: boolean
  winnerId: string | null
  endReason: string | null
  turn: { number: number; activePlayerId: string; drawStep: DrawStep | null }
  reactionWindow: { kind: string; defendingPlayerId: string; attackerInstanceId: string } | null
  /** Combat en cours de résolution (Mini-Feature 6) ; `null` = attaque résolue. */
  pendingAttack: PendingAttackState | null
  players: MockPlayer[]
  log: LogEntry[]
  /** Journal de diagnostic : chaque action, y compris refusée. */
  gameLog: ActionLogEntry[]
  createdAt: string
}

export interface MockRoomPlayer {
  pseudo: string
  seat: number
  /** Mini-Feature 9D : identifiant du deck sauvegardé sélectionné par le joueur. */
  deckId: number | null
  deck: string[]
}

export interface MockRoom {
  code: string
  name: string
  status: 'WAITING' | 'PLAYING' | 'CLOSED'
  hostPseudo: string
  players: MockRoomPlayer[]
  gameId: string | null
  createdAt: string
}

interface Session {
  id: string
  pseudo: string | null
  subscriptions: Map<string, string>
  send: (frame: string) => void
  closed: boolean
}

export interface MockSessionApi {
  session: Session
  receive(text: string): void
  close(): void
}

export interface MockFrame {
  pseudo: string | null
  command: string
  headers: Record<string, string>
  body: string
}

export interface MockServerOptions {
  cards?: MockCard[]
  seed?: number
  now?: () => string
}

/** Carte localisée (zone + propriétaire) — retour de `findInstance`. */
type Located = { player: MockPlayer; zone: ZoneKey; card: Instance }

interface ActionPayload {
  action?: string
  instanceId?: string | null
  targetInstanceId?: string | null
  clientRequestId?: string | null
  /** `SELECT_DIE` : dé choisi en première position (`['d6']`).
   *  `STEAL_GIG` : identifiants des M dés Gigs volés (`gigDieIds`). */
  dice?: string[] | null
  chosen?: string | null
  /**
   * `USE_BLOCKER` : identifiants des Blockers dépensés, **ordre significatif**
   * (le dernier de la liste encaisse les dégâts du combat).
   */
  cardIds?: string[] | null
  [key: string]: unknown
}

// --- Constantes -------------------------------------------------------------

const NUL = '\0'
const GIGS_TO_WIN = 7
const STARTING_HAND = 6
const REQUIRED_LEGENDS = 3
const REQUIRED_NON_LEGENDS = 10
const DIE_FACES: Record<string, number> = { d4: 4, d6: 6, d8: 8, d10: 10, d12: 12, d20: 20 }
/** Le d20 se lance toujours en dernier (règle officielle § START PHASE). */
const LAST_DIE = 'd20'
const ROOM_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
/** `GameConstants.POWER_PER_EXTRA_GIG` : +1 Gig volé par tranche de 10 de puissance. */
const POWER_PER_EXTRA_GIG = 10
/**
 * Zones internes → zones du **protocole** (`ZoneName`, alignées sur `ZoneDTO` Java).
 *
 * ⚠️ Ce vocabulaire est celui du transport et ne change pas : il est consommé tel quel
 * par `frontend/src/types/game.ts`. Depuis la mini-feature « Layout exact du Playmat »,
 * l'écran de jeu nomme les zones *à l'affichage* (`data-zone="FIXER|GIGS|FIELD|LEGENDS|
 * EDDIES|TRASH|DECK"`, voir `frontend/src/types/playmat.ts`) mais continue de lire les
 * mêmes champs serveur :
 *
 * | Zone jouée (serveur) | Zone playmat (affichage) |
 * | --- | --- |
 * | `field` / `FIELD` | `FIELD` (+ Gears attachés via `attachedTo`) |
 * | `legendsArea` / `LEGENDS_AREA` | `LEGENDS` (3 slots) |
 * | `eddiesArea` / `EDDIES_AREA` | `EDDIES` (cartes vendues face cachée) |
 * | `trash` / `TRASH` | `TRASH` |
 * | `deck` / `DECK` (`deckCount`) | `DECK` |
 * | `hand` / `HAND` | `HAND` (hors tapis) |
 * | `gigs` + `fixerDice` | `GIGS` (compteurs du haut) + `FIXER` (colonne des dés) |
 */
const ZONE_NAMES: Record<ZoneKey, ZoneName> = {
  deck: 'DECK',
  hand: 'HAND',
  field: 'FIELD',
  trash: 'TRASH',
  eddiesArea: 'EDDIES_AREA',
  legendsArea: 'LEGENDS_AREA',
}
const ZONE_KEYS: ZoneKey[] = ['hand', 'field', 'trash', 'legendsArea', 'eddiesArea', 'deck']

class RuleError extends Error {
  code: string

  constructor(message: string, code = 'ILLEGAL_ACTION') {
    super(message)
    this.code = code
    this.name = 'RuleError'
  }
}

/**
 * Quota théorique de Gigs volés (miroir de `RuleEngine.calculateQuota`) :
 * `N = power <= 0 ? 0 : (power / 10) + 1`.
 */
function stealQuota(power: number): number {
  if (!Number.isFinite(power) || power <= 0) return 0
  return Math.floor(power / POWER_PER_EXTRA_GIG) + 1
}

/**
 * **Plafond strict** (miroir de `RuleEngine.calculateActualStealable`) :
 * `M = min(N, dés Gigs actifs du défenseur)`. Les dés non lancés de la Fixer Area
 * ne comptent jamais — on ne crée pas de dé, on ne vole que des dés actifs.
 */
function stealableCount(power: number, activeGigs: number): number {
  return Math.max(0, Math.min(stealQuota(power), Math.max(0, activeGigs)))
}

/** `{Blocker}` prêts du joueur : les seuls capables d'intercepter une attaque. */
function readyBlockers(player: MockPlayer): Instance[] {
  return player.field.filter(
    (card) => card.type === 'unit' && !card.attachedTo && card.keywords.includes('blocker') && !card.exhausted,
  )
}

/** `{Go Solo}`, `{Adrenaline}` et `haste` ignorent le mal d'invocation. */
function ignoresSummoningSickness(card: Instance): boolean {
  return card.keywords.includes('go_solo') || card.keywords.includes('haste')
}

let gigDieCounter = 0

/** Identifiant stable d'un dé Gig actif (cible de `STEAL_GIG`). */
function nextGigDieId(): string {
  gigDieCounter += 1
  return `gig-${gigDieCounter.toString(16).padStart(4, '0')}`
}

/** PRNG déterministe (tests reproductibles). */
function createRandom(seed = 42): () => number {
  let state = seed >>> 0 || 1
  return () => {
    state ^= state << 13
    state ^= state >>> 17
    state ^= state << 5
    state >>>= 0
    return state / 0xffffffff
  }
}

// --- STOMP : sérialisation ---------------------------------------------------

export function serializeFrame(command: string, headers: Record<string, unknown> = {}, body = ''): string {
  const lines = [command]
  for (const [key, value] of Object.entries(headers)) {
    if (value !== undefined && value !== null) lines.push(`${key}:${String(value)}`)
  }
  return `${lines.join('\n')}\n\n${body}${NUL}`
}

/** Découpe un buffer en frames STOMP (séparées par NUL). */
export function splitFrames(text: string): string[] {
  return text.split(NUL).filter((chunk) => chunk.trim().length > 0)
}

export function parseFrame(raw: string): { command: string; headers: Record<string, string>; body: string } {
  const separator = raw.indexOf('\n\n')
  const head = separator >= 0 ? raw.slice(0, separator) : raw
  const body = separator >= 0 ? raw.slice(separator + 2) : ''
  const lines = head.split('\n')
  const command = lines.shift() ?? ''
  const headers: Record<string, string> = {}
  for (const line of lines) {
    const index = line.indexOf(':')
    if (index > 0) headers[line.slice(0, index)] = line.slice(index + 1)
  }
  return { command, headers, body }
}

/** Retire les clés nulles : le backend omet les champs null (doc §0.6). */
function prune(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(prune)
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {}
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      if (item === null || item === undefined) continue
      out[key] = prune(item)
    }
    return out
  }
  return value
}

function json(payload: unknown): string {
  return JSON.stringify(prune(payload))
}

// --- Serveur ----------------------------------------------------------------

export class MockGameServer {
  readonly cards: MockCard[]
  readonly cardsById: Map<string, MockCard>
  readonly sessions = new Set<Session>()
  readonly rooms = new Map<string, MockRoom>()
  readonly games = new Map<string, MockGame>()
  /** Journal des frames reçues, dans l'ordre (assertions de protocole). */
  readonly received: MockFrame[] = []
  /**
   * Decks sauvegardés mockés (Mini-Feature 9D) : pour chaque pseudo (=
   * username), une map `deckId → liste de cartes`. Le frontend pré-remplit
   * cette carte dans ses tests via {@link registerSavedDeck} ; sans entrée,
   * le serveur tolère encore l'ancien format `deckCardIds` pour les tests
   * qui n'utilisent pas le nouveau contrat de decks persistés.
   */
  readonly savedDecks = new Map<string, Map<number, string[]>>()
  /** Compteur auto-incrémenté pour générer des identifiants de decks de test. */
  private savedDeckCounter = 0

  private readonly now: () => string
  private readonly random: () => number
  private readonly sequences = new Map<string, number>()
  /** Dernier index de journal diffusé par partie (diffusion incrémentale). */
  private readonly lastLogIndex = new Map<string, number>()

  private sessionCount = 0

  constructor(options: MockServerOptions = {}) {
    this.cards = options.cards ?? []
    this.cardsById = new Map(this.cards.map((card) => [card.id, card]))
    this.now = options.now ?? (() => new Date().toISOString())
    this.random = createRandom(options.seed ?? 42)
  }

  /**
   * Enregistre un deck sauvegardé pour un pseudo (= compte JWT). Renvoie
   * l'identifiant de deck généré, à passer dans le payload STOMP
   * (`deckId`). Équivalent mocké de `POST /api/decks`.
   */
  registerSavedDeck(pseudo: string, cardIds: string[]): number {
    this.savedDeckCounter += 1
    const deckId = this.savedDeckCounter
    const registry = this.savedDecks.get(pseudo) ?? new Map<number, string[]>()
    registry.set(deckId, [...cardIds])
    this.savedDecks.set(pseudo, registry)
    return deckId
  }

  /** Tirage déterministe exposé pour la construction des decks. */
  randomFloat(): number {
    return this.random()
  }

  // --- Transport ---

  createSession(send: (frame: string) => void): MockSessionApi {
    this.sessionCount += 1
    const session: Session = {
      id: `session-${this.sessionCount}`,
      pseudo: null,
      subscriptions: new Map(),
      send,
      closed: false,
    }
    this.sessions.add(session)

    return {
      session,
      receive: (text: string) => this.onTransportText(session, text),
      close: () => this.releaseSession(session),
    }
  }

  onTransportText(session: Session, text: string): void {
    for (const raw of splitFrames(text)) {
      const frame = parseFrame(raw)
      this.received.push({ pseudo: session.pseudo, ...frame })
      try {
        this.dispatch(session, frame)
      } catch (error) {
        this.sendFrame(session, 'ERROR', { message: errorMessage(error), version: '1.2' })
      }
    }
  }

  dispatch(session: Session, frame: { command: string; headers: Record<string, string>; body: string }): void {
    switch (frame.command) {
      case 'CONNECT':
      case 'STOMP':
        this.onConnect(session, frame)
        return
      case 'SUBSCRIBE':
        if (frame.headers.id) session.subscriptions.set(frame.headers.id, frame.headers.destination ?? '')
        return
      case 'UNSUBSCRIBE':
        session.subscriptions.delete(frame.headers.id ?? '')
        return
      case 'SEND':
        this.onSend(session, frame)
        return
      case 'DISCONNECT':
        if (frame.headers.receipt) {
          this.sendFrame(session, 'RECEIPT', { 'receipt-id': frame.headers.receipt })
        }
        this.releaseSession(session)
        return
      default:
        return
    }
  }

  onConnect(session: Session, frame: { headers: Record<string, string> }): void {
    const pseudo = (frame.headers.pseudo ?? '').trim()
    if (!/^[A-Za-z0-9_\-À-ÿ]{2,20}$/.test(pseudo)) {
      this.sendFrame(session, 'ERROR', {
        message: "Pseudo invalide ou absent (en-tête 'pseudo' requis sur CONNECT)",
        version: '1.2',
      })
      session.closed = true
      this.sessions.delete(session)
      return
    }
    session.pseudo = pseudo
    this.sendFrame(session, 'CONNECTED', { version: '1.2', 'heart-beat': '0,0', server: 'mock-stomp/1.0' })
    this.onPseudoConnected(pseudo)
  }

  onSend(session: Session, frame: { headers: Record<string, string>; body: string }): void {
    if (!session.pseudo) {
      this.sendFrame(session, 'ERROR', { message: 'Session non authentifiée', version: '1.2' })
      return
    }
    const destination = frame.headers.destination ?? ''
    const payload = (frame.body ? JSON.parse(frame.body) : {}) as Record<string, unknown>

    if (destination === '/app/ping') {
      this.broadcast('/topic/pong', {
        type: 'pong',
        echo: payload.message ?? 'ping',
        serverTime: this.now(),
      })
      return
    }
    if (destination === '/app/lobby.create') return this.createRoom(session, payload)
    if (destination === '/app/lobby.join') return this.joinRoom(session, payload)
    if (destination === '/app/lobby.leave') return this.leaveRoom(session, payload)
    if (destination === '/app/lobby.list') return this.sendRoomList(session)

    const action = /^\/app\/game\/([^/]+)\/action$/.exec(destination)
    if (action) return this.onAction(session, action[1] as string, payload as ActionPayload)

    const resync = /^\/app\/game\/([^/]+)\/resync$/.exec(destination)
    if (resync) {
      this.sendStateTo(resync[1] as string, session.pseudo, null, [])
      return
    }

    this.error(session.pseudo, {
      code: 'BAD_REQUEST',
      message: `Destination inconnue : ${destination}`,
      destination,
    })
  }

  sendFrame(session: Session, command: string, headers: Record<string, unknown>, body = ''): void {
    if (session.closed) return
    session.send(serializeFrame(command, headers, body))
  }

  /** MESSAGE vers toutes les sessions abonnées à une destination. */
  broadcast(destination: string, payload: unknown): void {
    for (const session of this.sessions) {
      if ([...session.subscriptions.values()].includes(destination)) this.deliver(session, destination, payload)
    }
  }

  /** MESSAGE vers les sessions d'un pseudo donné (files `/user/queue/...`). */
  sendToPseudo(pseudo: string, destination: string, payload: unknown): void {
    for (const session of this.sessions) {
      if (session.pseudo !== pseudo) continue
      if ([...session.subscriptions.values()].includes(destination)) this.deliver(session, destination, payload)
    }
  }

  deliver(session: Session, destination: string, payload: unknown): void {
    const subscription = [...session.subscriptions.entries()].find(([, value]) => value === destination)?.[0]
    this.sendFrame(
      session,
      'MESSAGE',
      {
        subscription,
        'message-id': `mock-${Date.now()}-${Math.floor(this.random() * 1e6)}`,
        destination,
        'content-type': 'application/json',
      },
      json(payload),
    )
  }

  error(
    pseudo: string,
    details: { code: string; message: string; destination?: string | null; gameId?: string | null; clientRequestId?: string | null },
  ): void {
    this.sendToPseudo(pseudo, '/user/queue/errors', {
      type: 'ERROR',
      code: details.code,
      message: details.message,
      destination: details.destination ?? null,
      gameId: details.gameId ?? null,
      clientRequestId: details.clientRequestId ?? null,
    })
  }

  // --- Lobby ---

  newRoomCode(): string {
    let code = ''
    for (let i = 0; i < 6; i += 1) {
      code += ROOM_ALPHABET[Math.floor(this.random() * ROOM_ALPHABET.length)]
    }
    return code
  }

  /** Même contrat que `DefaultDeckService` : 3 Legends + ≥ 10 autres, sans doublon. */
  resolveDeck(requested?: string[] | null): string[] {
    if (!requested || requested.length === 0) return this.defaultDeck()
    const ids = requested.filter((id) => typeof id === 'string' && id.length > 0)
    if (new Set(ids).size !== ids.length) throw new RuleError('Le deck contient des cartes en double', 'DECK_INVALID')
    const missing = ids.find((id) => !this.cardsById.has(id))
    if (missing) throw new RuleError(`Carte inconnue dans le deck : ${missing}`, 'DECK_INVALID')

    const legends = ids.filter((id) => this.cardsById.get(id)?.type === 'legend').length
    if (legends !== REQUIRED_LEGENDS) {
      throw new RuleError(`Le deck doit contenir exactement ${REQUIRED_LEGENDS} Legends (reçu ${legends})`, 'DECK_INVALID')
    }
    if (ids.length - legends < REQUIRED_NON_LEGENDS) {
      throw new RuleError(`Le deck doit contenir au moins ${REQUIRED_NON_LEGENDS} cartes non-Legend`, 'DECK_INVALID')
    }
    return ids
  }

  /**
   * Mini-Feature 9D : résout le deck d'un joueur à partir du payload STOMP
   * (`deckId` ou, en repli, `deckCardIds` legacy). Refuse la jonction si
   * le joueur n'a pas sélectionné de deck, ou si le deck ne lui appartient
   * pas (mocké : `savedDecks.get(pseudo)?.get(deckId)`).
   */
  resolvePlayerDeck(pseudo: string, payload: Record<string, unknown>): string[] {
    const rawDeckId = payload.deckId
    if (rawDeckId === undefined || rawDeckId === null) {
      // Aucun `deckId` envoyé : repli sur l'ancien format `deckCardIds`,
      // utilisé par les tests qui n'ont pas (encore) migré.
      return this.resolveDeck(payload.deckCardIds as string[] | null)
    }
    if (typeof rawDeckId !== 'number' || !Number.isFinite(rawDeckId)) {
      throw new RuleError('Identifiant de deck invalide', 'NO_DECK_SELECTED')
    }
    const registry = this.savedDecks.get(pseudo)
    const deck = registry?.get(rawDeckId)
    if (!deck) {
      // Miroir du `DECK_NOT_OWNED` côté backend — on ne révèle pas
      // l'existence du deck d'un autre compte.
      throw new RuleError(`Ce deck n'existe pas ou ne t'appartient pas : ${rawDeckId}`, 'DECK_NOT_OWNED')
    }
    return this.resolveDeck(deck)
  }

  defaultDeck(): string[] {
    const legends = this.cards.filter((card) => card.type === 'legend').sort(byName).slice(0, REQUIRED_LEGENDS)
    const units = this.cards.filter((card) => card.type === 'unit').sort(byName).slice(0, REQUIRED_NON_LEGENDS)
    return [...legends, ...units].map((card) => card.id)
  }

  createRoom(session: Session, payload: Record<string, unknown>): void {
    const pseudo = session.pseudo as string
    if (this.roomOf(pseudo)) {
      this.error(pseudo, { code: 'ALREADY_IN_ROOM', message: 'Tu occupes déjà un siège', destination: '/app/lobby.create' })
      return
    }

    let deck: string[]
    try {
      deck = this.resolvePlayerDeck(pseudo, payload)
    } catch (invalid) {
      this.error(pseudo, {
        code: invalid instanceof RuleError ? invalid.code : 'DECK_INVALID',
        message: errorMessage(invalid),
        destination: '/app/lobby.create',
      })
      return
    }

    const name = typeof payload.roomName === 'string' ? payload.roomName.trim() : ''
    const deckId = typeof payload.deckId === 'number' ? (payload.deckId as number) : null
    const room: MockRoom = {
      code: this.newRoomCode(),
      name: name || `Salon de ${pseudo}`,
      status: 'WAITING',
      hostPseudo: pseudo,
      players: [{ pseudo, seat: 0, deckId, deck }],
      gameId: null,
      createdAt: this.now(),
    }
    this.rooms.set(room.code, room)
    this.publishRoom(room)
    this.broadcastRoomList()
  }

  joinRoom(session: Session, payload: Record<string, unknown>): void {
    const pseudo = session.pseudo as string
    const code = String(payload.roomCode ?? '').trim().toUpperCase()
    const room = this.rooms.get(code)
    if (!room) {
      this.error(pseudo, { code: 'ROOM_NOT_FOUND', message: `Salon introuvable : ${code}`, destination: '/app/lobby.join' })
      return
    }
    if (room.status !== 'WAITING' || room.players.length >= 2) {
      this.error(pseudo, { code: 'ROOM_NOT_JOINABLE', message: 'Ce salon n’est pas rejoignable', destination: '/app/lobby.join' })
      return
    }
    if (room.players.some((player) => player.pseudo === pseudo)) {
      this.error(pseudo, { code: 'PSEUDO_TAKEN', message: 'Ce pseudo est déjà assis dans ce salon', destination: '/app/lobby.join' })
      return
    }

    let deck: string[]
    try {
      deck = this.resolvePlayerDeck(pseudo, payload)
    } catch (invalid) {
      this.error(pseudo, {
        code: invalid instanceof RuleError ? invalid.code : 'DECK_INVALID',
        message: errorMessage(invalid),
        destination: '/app/lobby.join',
      })
      return
    }

    room.players.push({ pseudo, seat: 1, deckId: typeof payload.deckId === 'number' ? (payload.deckId as number) : null, deck })
    const host = room.players[0] as MockRoomPlayer
    const game = this.createGame(host.pseudo, pseudo, host.deck, deck)
    room.status = 'PLAYING'
    room.gameId = game.gameId
    this.publishRoom(room)
    this.broadcastRoomList()
    this.broadcast(`/topic/game/${game.gameId}`, { type: 'GAME_STARTED', gameId: game.gameId })
    this.pushLog(game)
    this.pushStates(game.gameId, null, [])
  }

  leaveRoom(session: Session, payload: Record<string, unknown>): void {
    const pseudo = session.pseudo as string
    const room = payload.roomCode
      ? this.rooms.get(String(payload.roomCode).toUpperCase())
      : this.roomOf(pseudo)
    if (!room) {
      this.error(pseudo, { code: 'ROOM_NOT_FOUND', message: 'Aucun salon à quitter', destination: '/app/lobby.leave' })
      return
    }
    if (room.status === 'PLAYING') {
      this.error(pseudo, {
        code: 'GAME_IN_PROGRESS',
        message: 'Partie en cours : abandonne via CONCEDE',
        destination: '/app/lobby.leave',
      })
      return
    }

    this.removeFromWaitingRoom(room, pseudo)
    this.broadcastRoomList()
  }

  roomOf(pseudo: string): MockRoom | null {
    return [...this.rooms.values()].find((room) => room.players.some((player) => player.pseudo === pseudo)) ?? null
  }

  /** Fermeture du socket ou trame DISCONNECT : libère la présence du pseudo. */
  releaseSession(session: Session): void {
    session.closed = true
    this.sessions.delete(session)
    const pseudo = session.pseudo
    if (!pseudo) return
    // Même pseudo encore connecté ailleurs (autre onglet) : on ne touche à rien.
    if ([...this.sessions].some((other) => other.pseudo === pseudo)) return
    this.onPseudoDisconnected(pseudo)
  }

  /** Miroir de `GamePresenceService.onDisconnected` côté backend. */
  onPseudoDisconnected(pseudo: string): void {
    const room = this.roomOf(pseudo)
    if (!room) return
    if (room.status === 'WAITING') {
      this.removeFromWaitingRoom(room, pseudo)
      this.broadcastRoomList()
      return
    }
    if (room.status !== 'PLAYING' || !room.gameId) return
    const game = this.games.get(room.gameId)
    const player = game?.players.find((candidate) => candidate.playerId === pseudo)
    if (!game || !player || game.gameOver) return
    player.connected = false
    this.broadcast(`/topic/game/${game.gameId}`, {
      type: 'PLAYER_DISCONNECTED',
      gameId: game.gameId,
      playerId: player.playerId,
      reconnectDeadInSeconds: 120,
    })
    this.pushStates(game.gameId, null, [])
  }

  /** Miroir de `GamePresenceService.onConnected` : annule la déconnexion. */
  onPseudoConnected(pseudo: string): void {
    const room = this.roomOf(pseudo)
    if (!room) return
    if (room.status === 'WAITING') {
      this.publishRoom(room)
      return
    }
    if (room.status !== 'PLAYING' || !room.gameId) return
    const game = this.games.get(room.gameId)
    const player = game?.players.find((candidate) => candidate.playerId === pseudo)
    if (!game || !player || game.gameOver) return
    const wasOffline = !player.connected
    player.connected = true
    if (wasOffline) {
      this.broadcast(`/topic/game/${game.gameId}`, {
        type: 'PLAYER_RECONNECTED',
        gameId: game.gameId,
        playerId: player.playerId,
      })
    }
    this.pushStates(game.gameId, null, [])
  }

  /** Retire un joueur d'un salon en attente (départ volontaire ou socket fermé). */
  removeFromWaitingRoom(room: MockRoom, pseudo: string): void {
    if (room.hostPseudo === pseudo) {
      room.status = 'CLOSED'
      this.rooms.delete(room.code)
      this.sendToPseudo(pseudo, '/user/queue/lobby', roomView(room))
      return
    }
    room.players = room.players.filter((player) => player.pseudo !== pseudo)
    this.publishRoom(room)
  }

  /**
   * Simule l'expiration du minuteur de forfait (120 s dans le backend, non
   * chronométré ici) : la partie se termine sur un abandon du joueur absent.
   */
  forfeitOfflinePlayer(gameId: string, pseudo: string): void {
    const game = this.games.get(gameId)
    if (!game || game.gameOver) return
    const rival = game.players.find((player) => player.playerId !== pseudo)
    if (!rival) return
    game.winnerId = rival.playerId
    game.endReason = `Forfait déconnexion de ${pseudo}`
    game.gameOver = true
    appendEvent(game, 'GAME_WON', rival.playerId, game.endReason)
    const event = game.log[game.log.length - 1] as LogEntry
    this.pushStates(gameId, null, [event])
    this.broadcast(`/topic/game/${gameId}`, { type: 'GAME_OVER', gameId, winnerId: rival.playerId, endReason: game.endReason })
    const room = [...this.rooms.values()].find((candidate) => candidate.gameId === gameId)
    if (room) {
      room.status = 'CLOSED'
      this.rooms.delete(room.code)
    }
    this.broadcastRoomList()
  }

  publishRoom(room: MockRoom): void {
    const view = roomView(room)
    const pseudos = new Set([room.hostPseudo, ...room.players.map((player) => player.pseudo)])
    for (const pseudo of pseudos) this.sendToPseudo(pseudo, '/user/queue/lobby', view)
    this.broadcast(`/topic/lobby/${room.code}`, view)
  }

  roomSummaries(): Array<{ code: string; name: string; hostPseudo: string; playerCount: number }> {
    return [...this.rooms.values()]
      .filter((room) => room.status === 'WAITING')
      .map((room) => ({
        code: room.code,
        name: room.name,
        hostPseudo: room.hostPseudo,
        playerCount: room.players.length,
      }))
  }

  broadcastRoomList(): void {
    const payload = { type: 'ROOMS', rooms: this.roomSummaries() }
    this.broadcast('/topic/rooms', payload)
    for (const pseudo of new Set([...this.sessions].map((session) => session.pseudo).filter((item): item is string => !!item))) {
      this.sendToPseudo(pseudo, '/user/queue/rooms', payload)
    }
  }

  sendRoomList(session: Session): void {
    this.sendToPseudo(session.pseudo as string, '/user/queue/rooms', { type: 'ROOMS', rooms: this.roomSummaries() })
  }

  // --- Partie ---

  createGame(host: string, guest: string, deckHost: string[], deckGuest: string[]): MockGame {
    const gameId = `game-${this.games.size + 1}-${Math.floor(this.random() * 1e6).toString(16)}`
    const game: MockGame = {
      gameId,
      // Mini-Feature 5.1 : la partie démarre en phase DRAW (sous-étape AWAITING_DRAW)
      // pour le premier joueur (host) — il doit piocher puis lancer son dé Gig avant
      // d'entrer en phase MAIN, exactement comme tous les tours.
      phase: 'DRAW',
      gameOver: false,
      winnerId: null,
      endReason: null,
      turn: { number: 1, activePlayerId: host, drawStep: 'AWAITING_DRAW' },
      reactionWindow: null,
      pendingAttack: null,
      players: [buildPlayer(host, deckHost, this), buildPlayer(guest, deckGuest, this)],
      log: [],
      gameLog: [],
      createdAt: this.now(),
    }
    this.games.set(gameId, game)
    this.sequences.set(gameId, 0)
    appendEvent(game, 'TURN_STARTED', host, `début de la partie (tour 1, ${host} commence)`)
    // Mini-Feature 5.1 : le premier joueur ouvre son tour 1 en phase DRAW (AWAITING_DRAW).
    // Contrairement à EndTurnCommand, on ne redresse PAS ses cartes : son malus de
    // mise en place (R1.4, ses 2 Legends les plus à gauche inclinées) demeure.
    game.phase = 'DRAW'
    game.turn.drawStep = 'AWAITING_DRAW'
    appendEvent(game, 'PHASE_CHANGED', host, 'phase Draw')
    // Malus du premier joueur (R1.4) : ses 2 Legends les plus à gauche sont inclinées.
    const hostPlayer = game.players[0] as MockPlayer
    let exhausted = 0
    for (const legend of hostPlayer.legendsArea) {
      if (exhausted >= 2) break
      legend.exhausted = true
      exhausted += 1
    }
    appendActionLog(game, {
      playerId: null,
      actionType: 'GAME_START',
      description: `Nouvelle partie : Joueur ${host} commence en phase DRAW (premier joueur, sous-étape AWAITING_DRAW)`,
      result: 'INFO',
      details: { starter: host, rival: guest, firstPlayerMalus: true },
    })
    appendActionLog(game, {
      playerId: null,
      actionType: 'SETUP',
      description: `Mise en place : 6 cartes en main et 3 Legends par joueur (${host} : 2 Legends inclinées, ${guest} : 0)`,
      result: 'INFO',
      details: { handSize: 6, legends: 3, firstPlayer: host, firstPlayerLegendsSpent: 2 },
    })
    appendActionLog(game, {
      playerId: host,
      actionType: 'DRAW_STEP',
      description: `Phase DRAW : Joueur ${host} doit cliquer sur sa pioche (AWAITING_DRAW)`,
      result: 'INFO',
      details: { drawStep: 'AWAITING_DRAW' },
    })
    return game
  }

  player(game: MockGame, pseudo: string): MockPlayer {
    const found = game.players.find((player) => player.playerId === pseudo)
    if (!found) throw new RuleError(`Joueur inconnu : ${pseudo}`)
    return found
  }

  opponent(game: MockGame, pseudo: string): MockPlayer {
    const found = game.players.find((player) => player.playerId !== pseudo)
    if (!found) throw new RuleError(`Adversaire introuvable pour ${pseudo}`)
    return found
  }

  findInstance(game: MockGame, instanceId?: string | null): { player: MockPlayer; zone: ZoneKey; card: Instance } | null {
    if (!instanceId) return null
    for (const player of game.players) {
      for (const zone of ZONE_KEYS) {
        const card = player[zone].find((item) => item.instanceId === instanceId)
        if (card) return { player, zone, card }
      }
    }
    return null
  }

  onAction(session: Session, gameId: string, payload: ActionPayload): void {
    const pseudo = session.pseudo as string
    const destination = `/app/game/${gameId}/action`
    const game = this.games.get(gameId)
    if (!game) {
      this.error(pseudo, { code: 'GAME_NOT_FOUND', message: 'Partie inconnue', destination, gameId })
      return
    }
    const requestId = payload.clientRequestId ?? null
    const before = this.findInstance(game, payload.instanceId)

    try {
      const events = this.applyCommand(game, pseudo, payload)
      appendActionLog(game, {
        playerId: pseudo,
        actionType: String(payload.action ?? '').toUpperCase(),
        description: describeMockAction(pseudo, payload, before, game, events),
        result: 'SUCCESS',
        details: { events: events.map((event) => event.type) },
      })
      this.pushStates(gameId, requestId, events)
      this.pushLog(game)
      if (game.gameOver) {
        this.broadcast(`/topic/game/${gameId}`, {
          type: 'GAME_OVER',
          gameId,
          winnerId: game.winnerId,
          endReason: game.endReason,
        })
      }
    } catch (ruleError) {
      const reason = errorMessage(ruleError)
      appendActionLog(game, {
        playerId: pseudo,
        actionType: String(payload.action ?? '').toUpperCase(),
        description: `Joueur ${pseudo} : ${describeIntent(payload, before)} → REFUSÉ (${reason})`,
        result: 'ILLEGAL',
        details: { reason },
      })
      this.pushLog(game)
      this.error(pseudo, {
        code: ruleError instanceof RuleError ? ruleError.code : 'ILLEGAL_ACTION',
        message: reason,
        destination,
        gameId,
        clientRequestId: requestId,
      })
    }
  }

  /**
   * Diffuse les nouvelles entrées du journal de diagnostic sur
   * `/topic/game/{gameId}/log` (feature 6.5). La diffusion est incrémentale,
   * comme côté serveur Java (`GameBroadcaster.pushLog`).
   */
  pushLog(game: MockGame): void {
    const from = this.lastLogIndex.get(game.gameId) ?? 0
    const fresh = game.gameLog.filter((entry) => entry.index > from)
    if (!fresh.length) return
    this.lastLogIndex.set(game.gameId, fresh[fresh.length - 1].index)
    this.broadcast(`/topic/game/${game.gameId}/log`, {
      type: 'LOG',
      gameId: game.gameId,
      entries: fresh,
    })
  }


  applyCommand(game: MockGame, pseudo: string, payload: ActionPayload): LogEntry[] {
    if (game.gameOver) throw new RuleError('La partie est terminée')
    const mark = game.log.length
    const action = String(payload.action ?? '').toUpperCase()

    switch (action) {
      case 'PLAY_CARD':
        this.playCard(game, pseudo, payload)
        break
      case 'ATTACK':
        this.attack(game, pseudo, payload)
        break
      case 'SELL_CARD':
        this.sellCard(game, pseudo, payload)
        break
      case 'SPEND_RESOURCE':
      case 'SPEND_LEGEND':
      case 'SPEND_EDDIES':
        // Mini-Feature 4 (R4) : même règle unifiée, cibles dans les deux zones.
        this.spendResource(game, pseudo, payload)
        break
      case 'END_TURN':
        this.endTurn(game, pseudo)
        break
      case 'DRAW_CARD':
        // Mini-Feature 5 : clic sur la pioche pendant AWAITING_DRAW.
        this.drawCard(game, pseudo)
        break
      case 'SELECT_DIE':
        // Mini-Feature 5 : choix du dé Gig pendant AWAITING_DIE_SELECT.
        this.selectDie(game, pseudo, payload)
        break
      case 'USE_BLOCKER':
      case 'BLOCK':
        // Mini-Feature 6 : le défenseur intercepte avec 1..n Blockers prêts.
        this.useBlocker(game, pseudo, payload)
        break
      case 'DECLINE_BLOCK':
        // Mini-Feature 6 : le défenseur renonce à intercepter.
        this.declineBlock(game, pseudo)
        break
      case 'STEAL_GIG':
        // Mini-Feature 6 : l'attaquant choisit les M dés Gigs actifs à voler.
        this.stealGig(game, pseudo, payload)
        break
      case 'CONCEDE': {
        const rival = this.opponent(game, pseudo)
        game.winnerId = rival.playerId
        game.endReason = `Abandon de ${pseudo}`
        game.gameOver = true
        appendEvent(game, 'GAME_WON', rival.playerId, game.endReason)
        break
      }
      default:
        throw new RuleError(`Action inconnue : ${action}`)
    }
    return game.log.slice(mark)
  }

  playCard(game: MockGame, pseudo: string, payload: ActionPayload): void {
    const player = this.player(game, pseudo)
    const found = this.findInstance(game, payload.instanceId)
    if (!found || found.player.playerId !== pseudo) throw new RuleError('Carte introuvable')
    const card = found.card

    const reacting = game.reactionWindow?.defendingPlayerId === pseudo
    if (!reacting) {
      requireActive(game, pseudo)
      if (game.phase !== 'MAIN' && game.phase !== 'COMBAT') {
        throw new RuleError('On ne joue des cartes qu’en phase Main ou Combat')
      }
    } else if (!card.keywords.includes('quick')) {
      throw new RuleError('En réaction, seules les cartes QUICK peuvent être jouées')
    }

    if (card.streetCredThreshold != null && streetCred(player) < card.streetCredThreshold) {
      throw new RuleError(`Street Cred insuffisant : ${streetCred(player)} pour ${card.streetCredThreshold}`)
    }

    if (card.type === 'legend') {
      if (card.zone !== 'LEGENDS_AREA' || !card.faceDown) {
        throw new RuleError('Seule une Legend face cachée peut être retournée')
      }
      card.faceDown = false
      appendEvent(game, 'LEGEND_FLIPPED', pseudo, `Legend retournée (${card.name})`)
      return
    }

    if (card.zone !== 'HAND') throw new RuleError(`Cette carte n’est pas jouable depuis ${card.zone}`)
    const toPay = Math.max(0, card.cost - player.costDiscount)
    if (player.eddies < toPay) throw new RuleError(`Eddies insuffisants : ${player.eddies} pour un coût de ${toPay}`)

    if (card.type === 'gear') {
      const host = this.findInstance(game, payload.targetInstanceId)
      if (!host || host.zone !== 'field' || host.player.playerId !== pseudo || host.card.type !== 'unit') {
        throw new RuleError('Un Gear doit être attaché à une Unit alliée du Field')
      }
      player.eddies -= toPay
      move(player, 'hand', 'field', card)
      card.attachedTo = host.card.instanceId
      host.card.attachments.push(card.instanceId)
      appendEvent(game, 'CARD_PLAYED', pseudo, `${card.name} équipé sur ${host.card.name}`)
      return
    }

    player.eddies -= toPay
    if (card.type === 'program') {
      move(player, 'hand', 'trash', card)
      appendEvent(game, 'CARD_PLAYED', pseudo, `Program résolu (${card.name})`)
      appendEvent(game, 'EFFECT_RESOLVED', pseudo, `effet de ${card.name}`)
      return
    }

    move(player, 'hand', 'field', card)
    card.summoningSickness = !ignoresSummoningSickness(card)
    appendEvent(game, 'CARD_PLAYED', pseudo, `${card.name} posé sur le Field`)
  }

  /**
   * `ATTACK` (Mini-Feature 6) : déclare l'attaque puis suspend sa résolution.
   *
   * Cibles légales — règles officielles § ATTACKING :
   * - une Unit rivale **dépensée** (« Ready Units can't be attacked ») ;
   * - ou la Gig Area adverse (attaque directe → vol de dés plafonné).
   *
   * Si le défenseur contrôle un `{Blocker}` prêt, l'attaque passe en
   * `AWAITING_BLOCK` : c'est **lui** qui décide d'intercepter (blocage multiple
   * autorisé, seul le dernier Blocker encaisse) ou de renoncer. Sinon elle est
   * résolue immédiatement ({@link MockGameServer.resolveAttack}).
   */
  attack(game: MockGame, pseudo: string, payload: ActionPayload): void {
    const rival = this.opponent(game, pseudo)
    requireActive(game, pseudo)
    if (game.phase !== 'MAIN' && game.phase !== 'COMBAT') throw new RuleError('On n’attaque qu’en phase Main ou Combat')
    if (game.pendingAttack) throw new RuleError('Une attaque est déjà en cours de résolution')

    const found = this.findInstance(game, payload.instanceId)
    if (!found || found.zone !== 'field' || found.player.playerId !== pseudo) {
      throw new RuleError('Attaquant introuvable sur le Field')
    }
    const attacker = found.card
    if (attacker.type !== 'unit') throw new RuleError('Seule une Unit peut attaquer')
    if (attacker.exhausted) throw new RuleError('Cette Unit est déjà épuisée')
    if (attacker.summoningSickness && !ignoresSummoningSickness(attacker)) {
      throw new RuleError('Cette Unit vient d’être jouée (mal d’invocation)')
    }

    const direct = !payload.targetInstanceId
    let target: Instance | null = null
    if (!direct) {
      const located = this.findInstance(game, payload.targetInstanceId)
      if (!located || located.zone !== 'field' || located.player.playerId !== rival.playerId || located.card.type !== 'unit') {
        throw new RuleError('On n’attaque qu’une Unit rivale du Field')
      }
      if (!located.card.exhausted) {
        throw new RuleError(`Cette Unit rivale est prête : on n’attaque qu’une Unit dépensée (inclinée)`)
      }
      target = located.card
    }

    if (game.phase === 'MAIN') {
      game.phase = 'COMBAT'
      appendEvent(game, 'PHASE_CHANGED', pseudo, 'phase Combat')
    }

    // Déclarer une attaque incline l'attaquant (règle officielle § ATTACKING).
    attacker.exhausted = true
    appendEvent(
      game,
      'ATTACK_DECLARED',
      pseudo,
      direct ? `attaque directe vers la Gig Area (${attacker.name})` : `attaque déclarée (${attacker.name})`,
    )

    game.reactionWindow = {
      kind: 'ATTACK',
      defendingPlayerId: rival.playerId,
      attackerInstanceId: attacker.instanceId,
    }
    appendEvent(game, 'REACTION_WINDOW_OPENED', rival.playerId, 'fenêtre de réaction ouverte (QUICK uniquement)')

    const blockers = readyBlockers(rival)
    if (blockers.length > 0) {
      game.pendingAttack = {
        attackerPlayerId: pseudo,
        defendingPlayerId: rival.playerId,
        attackerInstanceId: attacker.instanceId,
        targetInstanceId: direct ? null : (target as Instance).instanceId,
        step: 'AWAITING_BLOCK',
        quota: 0,
        stealableCount: 0,
        blockerInstanceIds: [],
      }
      appendActionLog(game, {
        playerId: rival.playerId,
        actionType: 'BLOCKER_PROMPT',
        description:
          `Utiliser Blocker ? Joueur ${rival.playerId} peut intercepter avec ${blockers.length}` +
          ` Blocker(s) prêt(s) : ${blockers.map((card) => card.name).join(', ')}` +
          ' (blocage multiple autorisé — seul le dernier Blocker encaisse les dégâts)',
        result: 'INFO',
        details: {
          blockers: blockers.map((card) => card.name),
          blockerInstanceIds: blockers.map((card) => card.instanceId),
          attacker: attacker.name,
          direct,
        },
      })
      return
    }

    this.resolveAttack(game, 'aucun Blocker prêt')
  }

  /**
   * Résout l'attaque courante : combat contre la cible déclarée, ou ouverture du
   * choix des dés à voler pour une attaque directe (plafond strict M ≥ 1) / vol nul
   * (M = 0 : l'attaque réussit mais ne rapporte aucun Gig).
   */
  resolveAttack(game: MockGame, reason: string): void {
    const pending = game.pendingAttack
    if (!pending) return
    const located = this.findInstance(game, pending.attackerInstanceId)
    if (!located || located.zone !== 'field') {
      appendEvent(game, 'ATTACK_DECLARED', pending.attackerPlayerId, `attaque sans effet (${reason})`)
      this.clearCombat(game)
      return
    }
    const attackerPlayer = located.player
    const attacker = located.card
    const defender = game.players.find((player) => player.playerId === pending.defendingPlayerId) as MockPlayer

    if (pending.targetInstanceId) {
      const target = this.findInstance(game, pending.targetInstanceId)
      if (!target || target.zone !== 'field') {
        appendEvent(game, 'ATTACK_DECLARED', pending.attackerPlayerId, `attaque sans effet (la cible a quitté le Field)`)
        this.clearCombat(game)
        return
      }
      this.clearCombat(game)
      this.fight(game, attackerPlayer, attacker, defender, target.card)
      return
    }

    const power = totalPower(attackerPlayer, attacker)
    const quota = stealQuota(power)
    const stealable = stealableCount(power, defender.gigs.length)

    if (stealable === 0) {
      // Plafond strict : puissance ≤ 0 ou aucun dé Gig actif chez le défenseur.
      // L'attaque reste réussie (Unité inclinée, phase Combat), rien n'est volé.
      appendEvent(
        game,
        'ATTACK_DECLARED',
        pending.attackerPlayerId,
        `attaque directe sans vol (quota ${quota}, ${defender.gigs.length} dé(s) Gig actif(s) chez le défenseur)`,
      )
      appendActionLog(game, {
        playerId: pending.attackerPlayerId,
        actionType: 'GIG_STOLEN',
        description:
          `Attaque directe sans vol de Gig : quota N = ${quota} (power ${power}) plafonné à M = 0` +
          ` — le défenseur n'a ${defender.gigs.length} dé(s) Gig actif(s)` +
          ` (les ${defender.fixerDice.length} dés non lancés de sa Fixer Area ne sont jamais volés)`,
        result: 'FAILED',
        details: {
          power,
          quota,
          stealable: 0,
          activeGigs: defender.gigs.length,
          fixerDice: [...defender.fixerDice],
          reason,
        },
      })
      this.clearCombat(game)
      return
    }

    pending.step = 'AWAITING_STEAL_CHOICE'
    pending.quota = quota
    pending.stealableCount = stealable
    appendActionLog(game, {
      playerId: pending.attackerPlayerId,
      actionType: 'GIG_STEAL_CHOICE',
      description:
        `Vol de Gigs : quota N = ${quota} (power ${power}), plafond strict M = ${stealable}` +
        ` dé(s) à choisir parmi les ${defender.gigs.length} dés Gigs actifs du défenseur`,
      result: 'INFO',
      details: {
        power,
        quota,
        stealable,
        activeGigs: defender.gigs.length,
        dieIds: defender.gigDieIds.map((id, index) => `${id}:${defender.gigDice[index] ?? '?'}=${defender.gigs[index]}`),
      },
    })
  }

  /**
   * `USE_BLOCKER` (Mini-Feature 6) : le défenseur intercepte avec un ou plusieurs
   * `{Blocker}` prêts. Tous sont inclinés et **seul le dernier** de la liste
   * encaisse les dégâts du combat ; une attaque redirigée ne vole jamais de Gig.
   */
  useBlocker(game: MockGame, pseudo: string, payload: ActionPayload): void {
    const pending = this.requireCombatStep(game, 'AWAITING_BLOCK')
    if (pending.defendingPlayerId !== pseudo) {
      throw new RuleError(`Seul le défenseur (${pending.defendingPlayerId}) peut bloquer cette attaque`)
    }
    const ids = (payload.cardIds ?? payload.dice ?? []).map((id) => String(id))
    if (ids.length === 0) throw new RuleError('USE_BLOCKER exige au moins un Blocker dans ’cardIds’')
    if (new Set(ids).size !== ids.length) throw new RuleError('Blocker désigné en double')

    const defender = this.player(game, pseudo)
    const blockers: Instance[] = []
    for (const id of ids) {
      const card = defender.field.find((item) => item.instanceId === id)
      if (!card || card.type !== 'unit' || card.attachedTo) {
        throw new RuleError(`Blocker introuvable sur le Field : ${id}`)
      }
      if (!card.keywords.includes('blocker')) throw new RuleError(`${card.name} n’a pas le mot-clé BLOCKER`)
      if (card.exhausted) throw new RuleError(`${card.name} n’est pas prêt (déjà incliné)`)
      blockers.push(card)
    }

    for (const blocker of blockers) {
      blocker.exhausted = true
      pending.blockerInstanceIds.push(blocker.instanceId)
      appendEvent(game, 'ATTACK_BLOCKED', pseudo, `Blocker ${blocker.name} intercepte l'attaque (redirection)`)
    }
    appendActionLog(game, {
      playerId: pseudo,
      actionType: 'USE_BLOCKER',
      description:
        `Joueur ${pseudo} bloque avec ${blockers.map((card) => card.name).join(' puis ')}` +
        ` (Unités inclinées, attaque redirigée — ${blockers[blockers.length - 1].name} encaisse les dégâts)`,
      result: 'SUCCESS',
      details: {
        blockers: blockers.map((card) => card.instanceId),
        attacker: pending.attackerInstanceId,
        redirectedFrom: pending.targetInstanceId ? 'UNIT' : 'GIG_AREA',
      },
    })

    const attacker = this.findInstance(game, pending.attackerInstanceId)
    const last = blockers[blockers.length - 1] as Instance
    this.clearCombat(game)
    if (!attacker || attacker.zone !== 'field') {
      appendEvent(game, 'ATTACK_DECLARED', pending.attackerPlayerId, 'attaque sans effet (attaquant disparu)')
      return
    }
    this.fight(game, attacker.player, attacker.card, defender, last)
  }

  /** `DECLINE_BLOCK` : renoncement explicite, l'attaque suit son cours. */
  declineBlock(game: MockGame, pseudo: string): void {
    const pending = this.requireCombatStep(game, 'AWAITING_BLOCK')
    if (pending.defendingPlayerId !== pseudo) {
      throw new RuleError(`Seul le défenseur (${pending.defendingPlayerId}) peut renoncer au blocage`)
    }
    appendActionLog(game, {
      playerId: pseudo,
      actionType: 'DECLINE_BLOCK',
      description: `Joueur ${pseudo} renonce à bloquer : l'attaque suit son cours`,
      result: 'INFO',
      details: { attacker: pending.attackerInstanceId, direct: !pending.targetInstanceId },
    })
    this.resolveAttack(game, 'blocage refusé')
  }

  /**
   * `STEAL_GIG` (Mini-Feature 6) : l'attaquant choisit **exactement M** dés Gigs
   * actifs du défenseur. Chaque dé transféré conserve son type et sa valeur
   * (un d8 montrant 5 reste un d8 montrant 5) et son identifiant.
   */
  stealGig(game: MockGame, pseudo: string, payload: ActionPayload): void {
    const pending = this.requireCombatStep(game, 'AWAITING_STEAL_CHOICE')
    if (pending.attackerPlayerId !== pseudo) {
      throw new RuleError(`Seul l’attaquant (${pending.attackerPlayerId}) choisit les dés Gigs volés`)
    }
    const ids = (payload.dice ?? payload.cardIds ?? (payload.chosen ? String(payload.chosen).split(',') : []))
      .map((id) => String(id).trim())
      .filter((id) => id.length > 0)
    if (ids.length !== pending.stealableCount) {
      throw new RuleError(
        `Tu dois choisir exactement ${pending.stealableCount} dé(s) Gig (plafond strict) — reçu ${ids.length}`,
      )
    }
    if (new Set(ids).size !== ids.length) throw new RuleError('Dé Gig choisi en double')
    this.applySteal(game, pseudo, ids, `choix de l’attaquant (M = ${pending.stealableCount})`)
  }

  /**
   * Transfère les dés désignés du défenseur vers l'attaquant (types, valeurs et
   * identifiants préservés), puis referme le combat.
   */
  applySteal(game: MockGame, attackerId: string, dieIds: string[], reason: string): void {
    const pending = this.requireCombatStep(game, 'AWAITING_STEAL_CHOICE')
    const attacker = this.player(game, attackerId)
    const defender = game.players.find((player) => player.playerId === pending.defendingPlayerId) as MockPlayer

    const indexes: number[] = []
    for (const id of dieIds) {
      const index = defender.gigDieIds.indexOf(id)
      if (index < 0) throw new RuleError(`Le dé ${id} n’est pas un dé Gig actif du défenseur`)
      if (indexes.includes(index)) throw new RuleError('Dé Gig choisi en double')
      indexes.push(index)
    }

    // Retraits en indices décroissants : l'alignement gigs/gigDice/gigDieIds tient.
    const removed: Array<{ id: string; die: string; value: number }> = []
    for (const index of [...indexes].sort((a, b) => b - a)) {
      removed.push({
        value: defender.gigs.splice(index, 1)[0] as number,
        die: defender.gigDice.splice(index, 1)[0] ?? '?',
        id: defender.gigDieIds.splice(index, 1)[0] as string,
      })
    }

    // Transfert dans l'ordre demandé par l'attaquant : type, valeur et identifiant
    // du dé sont conservés tels quels (un d8 montrant 5 reste un d8 montrant 5).
    const transferred: string[] = []
    for (const dieId of dieIds) {
      const entry = removed.find((item) => item.id === dieId) as { id: string; die: string; value: number }
      attacker.gigs.push(entry.value)
      attacker.gigDice.push(entry.die)
      attacker.gigDieIds.push(entry.id)
      transferred.push(`${entry.die} → ${entry.value}`)
      appendEvent(
        game,
        'GIG_STOLEN',
        attackerId,
        `vol d’un Gig ${entry.die} de valeur ${entry.value} (total ${attacker.gigs.length})`,
      )
    }

    appendActionLog(game, {
      playerId: attackerId,
      actionType: 'GIG_STOLEN',
      description:
        `Vol de Gigs : Joueur ${attackerId} vole ${transferred.length} dé(s) (${transferred.join(', ')})` +
        ` à Joueur ${defender.playerId} — ${reason}`,
      result: 'SUCCESS',
      details: {
        quota: pending.quota,
        stealable: pending.stealableCount,
        dice: transferred,
        dieIds,
        defenderGigs: defender.gigs.length,
        attackerGigs: attacker.gigs.length,
      },
    })
    this.clearCombat(game)
  }

  /**
   * Fin de tour avec un combat en suspens : renoncement implicite au blocage,
   * puis vol automatique des M dés de plus haute valeur (tri stable).
   */
  autoResolveCombat(game: MockGame): void {
    const pending = game.pendingAttack
    if (!pending) return
    if (pending.step === 'AWAITING_BLOCK') {
      appendActionLog(game, {
        playerId: pending.defendingPlayerId,
        actionType: 'DECLINE_BLOCK',
        description: `Fin de tour : Joueur ${pending.defendingPlayerId} n’a pas bloqué (renoncement implicite)`,
        result: 'INFO',
        details: { implicit: true },
      })
      this.resolveAttack(game, 'fin de tour (blocage refusé implicitement)')
    }
    const stealStep = game.pendingAttack
    if (!stealStep || stealStep.step !== 'AWAITING_STEAL_CHOICE') return
    const defender = game.players.find((player) => player.playerId === stealStep.defendingPlayerId)
    if (!defender) {
      this.clearCombat(game)
      return
    }
    const ids = defender.gigs
      .map((value, index) => ({ value, index }))
      .sort((a, b) => b.value - a.value)
      .slice(0, stealStep.stealableCount)
      .map((entry) => defender.gigDieIds[entry.index] as string)
    this.applySteal(game, stealStep.attackerPlayerId, ids, 'fin de tour (vol automatique des M dés de plus haute valeur)')
  }

  /** Combat Unité vs Unité : la puissance la plus haute l'emporte, égalité = les deux tombent. */
  fight(game: MockGame, attackerPlayer: MockPlayer, attacker: Instance, defenderPlayer: MockPlayer, defender: Instance): void {
    const attackPower = totalPower(attackerPlayer, attacker)
    const defensePower = totalPower(defenderPlayer, defender)
    if (attackPower > defensePower) defeat(game, defenderPlayer, defender)
    else if (attackPower < defensePower) defeat(game, attackerPlayer, attacker)
    else {
      defeat(game, defenderPlayer, defender)
      defeat(game, attackerPlayer, attacker)
    }
  }

  /** Vérifie que le combat attendu est bien en cours, à l'étape indiquée. */
  requireCombatStep(game: MockGame, expected: CombatStep): PendingAttackState {
    const pending = game.pendingAttack
    if (!pending) throw new RuleError('Aucune attaque en cours de résolution')
    if (pending.step !== expected) {
      throw new RuleError(
        expected === 'AWAITING_BLOCK'
          ? `Le blocage n’est possible qu’à l’étape AWAITING_BLOCK (étape courante : ${pending.step})`
          : `Le choix des dés volés n’est possible qu’à l’étape AWAITING_STEAL_CHOICE (étape courante : ${pending.step})`,
      )
    }
    return pending
  }

  /** Referme le combat : plus de blocage ni de vol possible. */
  clearCombat(game: MockGame): void {
    game.pendingAttack = null
  }

  sellCard(game: MockGame, pseudo: string, payload: ActionPayload): void {
    const player = this.player(game, pseudo)
    requireActive(game, pseudo)
    if (game.phase !== 'MAIN') throw new RuleError('La vente n’est possible qu’en phase Main')
    if (player.hasSoldThisTurn) throw new RuleError('Une seule vente par tour (déjà effectuée)')

    const found = this.findInstance(game, payload.instanceId)
    if (!found || found.zone !== 'hand' || found.player.playerId !== pseudo) throw new RuleError('Carte de main introuvable')

    // Mini-Feature 3 : la vente CRÉE une ressource, elle ne crédite aucun Eddie.
    move(player, 'hand', 'eddiesArea', found.card)
    found.card.faceDown = true
    found.card.exhausted = false
    player.hasSoldThisTurn = true
    appendEvent(game, 'CARD_SOLD', pseudo, `vente de ${found.card.name} → Eddies Area (ressource face cachée, prête)`)
  }

  /**
   * Mini-Feature 4 (R4) : générer des Eddies — mirroir de `SpendResourceCommand`.
   * Le joueur actif, en phase Main, incline l'une de ses ressources (Legend de la
   * Legends Area ou carte vendue de l'Eddies Area) : `exhausted = true`, +1 Eddie.
   */
  spendResource(game: MockGame, pseudo: string, payload: ActionPayload): void {
    const player = this.player(game, pseudo)
    requireActive(game, pseudo)
    if (game.phase !== 'MAIN') throw new RuleError('On incline une ressource qu’en phase Main')

    const found = this.findInstance(game, payload.instanceId)
    if (!found || found.player.playerId !== pseudo) throw new RuleError('Carte introuvable')
    if (found.zone !== 'legendsArea' && found.zone !== 'eddiesArea') {
      throw new RuleError('Seule une Legend ou une carte de la zone Eddies peut être inclinée')
    }
    if (found.card.exhausted) throw new RuleError('Cette carte est déjà inclinée (Eddie déjà perçu)')

    found.card.exhausted = true
    player.eddies += 1
    appendEvent(game, 'EFFECT_RESOLVED', pseudo, `${found.card.name} inclinée (+1 Eddie)`)
  }

  /**
   * Fin de tour — Mini-Feature 5 : la phase DRAW du joueur entrant est
   * **interactive** (miroir de `EndTurnCommand` + `DrawPhaseHandler`). Le tour
   * s'arrête à l'étape `AWAITING_DRAW` : rien n'est pioché ni lancé tant que le
   * joueur n'a pas envoyé `DRAW_CARD` puis `SELECT_DIE`.
   */
  endTurn(game: MockGame, pseudo: string): void {
    requireActive(game, pseudo)
    if (game.phase === 'DRAW') {
      throw new RuleError(
        game.turn.drawStep === 'AWAITING_DIE_SELECT'
          ? 'Impossible de terminer le tour pendant la phase Draw : choisissez d’abord votre dé Gig'
          : 'Impossible de terminer le tour pendant la phase Draw : piochez d’abord votre carte',
      )
    }
    const outgoing = this.player(game, pseudo)
    const incoming = this.opponent(game, pseudo)

    // Mini-Feature 6 : terminer son tour avec un combat en suspens vaut
    // renoncement implicite (blocage refusé, puis vol automatique des M dés
    // de plus haute valeur) — comme `EndTurnCommand` côté serveur.
    this.autoResolveCombat(game)

    game.phase = 'END'
    appendEvent(game, 'PHASE_CHANGED', pseudo, 'phase End')
    appendEvent(game, 'TURN_ENDED', pseudo, `fin du tour ${game.turn.number}`)

    if (game.reactionWindow) {
      game.reactionWindow = null
      appendEvent(game, 'REACTION_WINDOW_CLOSED', pseudo, 'fenêtre de réaction fermée')
    }

    game.turn.number += 1
    game.turn.activePlayerId = incoming.playerId
    appendEvent(game, 'TURN_STARTED', incoming.playerId, `début du tour ${game.turn.number}`)

    // DRAW_START : le tour commence (état transitoire, comme côté serveur).
    game.phase = 'DRAW'
    game.turn.drawStep = 'DRAW_START'
    appendEvent(game, 'PHASE_CHANGED', incoming.playerId, 'phase Draw')

    if (incoming.gigs.length >= GIGS_TO_WIN) {
      game.turn.drawStep = null
      game.winnerId = incoming.playerId
      game.endReason = `victoire : ${incoming.playerId} commence son tour avec ${incoming.gigs.length} Gigs`
      game.gameOver = true
      appendEvent(game, 'GAME_WON', incoming.playerId, game.endReason)
      return
    }

    // Redressement (Legends, Eddies, Field), Eddies à 0 — puis on ATTEND le clic sur la pioche.
    startTurn(outgoing)
    startTurn(incoming)
    game.turn.drawStep = 'AWAITING_DRAW'
    appendActionLog(game, {
      playerId: incoming.playerId,
      actionType: 'DRAW_STEP',
      description: `Phase DRAW : Joueur ${incoming.playerId} doit cliquer sur sa pioche (AWAITING_DRAW)`,
      result: 'INFO',
      details: { drawStep: 'AWAITING_DRAW' },
    })
  }

  /** Mini-Feature 5 : `DRAW_CARD` — pioche 1 carte, deck vide = défaite immédiate. */
  drawCard(game: MockGame, pseudo: string): void {
    requireActive(game, pseudo)
    requireDrawStep(game, 'AWAITING_DRAW')
    const player = this.player(game, pseudo)
    const rival = this.opponent(game, pseudo)

    const drawn = player.deck.shift()
    if (!drawn) {
      game.turn.drawStep = null
      game.winnerId = rival.playerId
      game.endReason = `Deck épuisé : ${player.playerId} ne peut plus piocher`
      game.gameOver = true
      appendEvent(game, 'GAME_WON', rival.playerId, game.endReason)
      return
    }
    drawn.zone = 'HAND'
    player.hand.push(drawn)
    appendEvent(game, 'CARD_DRAWN', pseudo, 'pioche 1 carte')

    if (selectableDice(player).length === 0) {
      this.completeDrawPhase(game, pseudo)
      return
    }
    game.turn.drawStep = 'AWAITING_DIE_SELECT'
    appendActionLog(game, {
      playerId: pseudo,
      actionType: 'DRAW_STEP',
      description: `Phase DRAW : Joueur ${pseudo} doit choisir son dé Gig (AWAITING_DIE_SELECT)`,
      result: 'INFO',
      details: { drawStep: 'AWAITING_DIE_SELECT', selectable: selectableDice(player) },
    })
  }

  /** Mini-Feature 5 : `SELECT_DIE` — d20 seulement en dernier, le serveur lance. */
  selectDie(game: MockGame, pseudo: string, payload: ActionPayload): void {
    requireActive(game, pseudo)
    requireDrawStep(game, 'AWAITING_DIE_SELECT')
    const player = this.player(game, pseudo)
    const raw = payload.dice?.[0] ?? payload.chosen ?? ''
    const die = String(raw).trim().toLowerCase()
    if (!die) throw new RuleError('SELECT_DIE exige le dé à lancer dans ’dice’')
    if (!(die in DIE_FACES)) throw new RuleError(`Dé Gig inconnu : ${die}`)
    if (!player.fixerDice.includes(die)) throw new RuleError(`Le ${die} n’est plus dans la Fixer Area`)
    if (!selectableDice(player).includes(die)) throw new RuleError('Le d20 se lance toujours en dernier')

    game.turn.drawStep = 'ROLLING_DIE'
    player.fixerDice.splice(player.fixerDice.indexOf(die), 1)
    const value = 1 + Math.floor(this.random() * (DIE_FACES[die] ?? 6))
    player.gigs.push(value)
    player.gigDice.push(die)
    // Mini-Feature 6 : chaque dé Gig actif porte un identifiant stable (cible de STEAL_GIG).
    player.gigDieIds.push(nextGigDieId())
    appendEvent(game, 'GIG_ROLLED', pseudo, `lancer ${die} → ${value} (total ${player.gigs.length} Gigs)`)
    this.completeDrawPhase(game, pseudo)
  }

  /** DRAW_COMPLETE → phase MAIN (automatique). */
  completeDrawPhase(game: MockGame, pseudo: string): void {
    game.turn.drawStep = 'DRAW_COMPLETE'
    game.phase = 'MAIN'
    game.turn.drawStep = null
    appendEvent(game, 'PHASE_CHANGED', pseudo, 'phase Main')
  }

  // --- Diffusion d'état ---

  nextSequence(gameId: string): number {
    const next = (this.sequences.get(gameId) ?? 0) + 1
    this.sequences.set(gameId, next)
    return next
  }

  pushStates(gameId: string, clientRequestId: string | null, events: LogEntry[]): void {
    const game = this.games.get(gameId)
    if (!game) return
    const sequence = this.nextSequence(gameId)
    for (const player of game.players) this.sendState(game, player.playerId, sequence, clientRequestId, events)
  }

  sendStateTo(gameId: string, pseudo: string, clientRequestId: string | null, events: LogEntry[]): void {
    const game = this.games.get(gameId)
    if (!game) {
      this.error(pseudo, { code: 'GAME_NOT_FOUND', message: 'Partie inconnue', gameId })
      return
    }
    // Le resync ne consomme pas de numéro de séquence (doc §7.5).
    this.sendState(game, pseudo, this.sequences.get(gameId) ?? 0, clientRequestId, events)
  }

  sendState(game: MockGame, viewerId: string, sequence: number, clientRequestId: string | null, events: LogEntry[]): void {
    const payload = {
      type: 'STATE',
      gameId: game.gameId,
      clientRequestId,
      state: stateView(game, viewerId, sequence, this.now()),
      newEvents: events.map((event, index) => ({ ...event, index: game.log.length - events.length + index })),
    }
    this.broadcast(`/topic/game/${game.gameId}/${viewerId}`, payload)
  }
}

// --- Helpers de domaine (version simplifiée) --------------------------------

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

function byName(a: MockCard, b: MockCard): number {
  return a.name.localeCompare(b.name) || a.id.localeCompare(b.id)
}

function shuffle<T>(list: T[], random: () => number): T[] {
  const out = [...list]
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(random() * (i + 1))
    ;[out[i], out[j]] = [out[j] as T, out[i] as T]
  }
  return out
}

let instanceCounter = 0

function newInstance(definition: MockCard, ownerId: string, zone: ZoneName, extra: Partial<Instance> = {}): Instance {
  instanceCounter += 1
  return {
    instanceId: `inst-${instanceCounter.toString(16).padStart(6, '0')}`,
    cardId: definition.id,
    name: definition.name,
    type: definition.type,
    color: definition.color,
    baseCost: definition.cost ?? null,
    cost: definition.cost ?? 0,
    power: definition.power ?? null,
    powerBonus: 0,
    damage: 0,
    streetCredThreshold: definition.streetCred ?? null,
    keywords: [...(definition.keywords ?? [])],
    abilities: [...(definition.abilities ?? [])],
    ownerId,
    zone,
    faceDown: false,
    exhausted: false,
    summoningSickness: false,
    attachedTo: null,
    attachments: [],
    ...extra,
  }
}

function buildPlayer(pseudo: string, deckIds: string[], server: MockGameServer): MockPlayer {
  const definitions = deckIds.map((id) => server.cardsById.get(id)).filter((card): card is MockCard => !!card)
  const legends = definitions.filter((card) => card.type === 'legend')
  const rest = shuffle(
    definitions.filter((card) => card.type !== 'legend'),
    () => server.randomFloat(),
  )

  const player: MockPlayer = {
    playerId: pseudo,
    name: pseudo,
    connected: true,
    deck: rest.map((card) => newInstance(card, pseudo, 'DECK')),
    hand: [],
    field: [],
    trash: [],
    eddiesArea: [],
    legendsArea: legends.map((card) => newInstance(card, pseudo, 'LEGENDS_AREA', { faceDown: true })),
    gigs: [],
    gigDice: [],
    gigDieIds: [],
    fixerDice: ['d4', 'd6', 'd8', 'd10', 'd12', 'd20'],
    eddies: 0,
    costDiscount: 0,
    hasSoldThisTurn: false,
  }

  for (let i = 0; i < STARTING_HAND && player.deck.length > 0; i += 1) {
    const card = player.deck.shift() as Instance
    card.zone = 'HAND'
    player.hand.push(card)
  }
  return player
}

/** Début de tour : redressement, fin des mals d'invocation, vente/remise remises à zéro. */
function startTurn(player: MockPlayer): void {
  // R2 : la réserve d'Eddies ne se reporte pas — début de tour = 0.
  player.eddies = 0
  player.hasSoldThisTurn = false
  player.costDiscount = 0
  // R3/R6 : toutes les cartes dépensées (Field, Legends, Eddies) se redressent.
  for (const card of player.field) {
    card.exhausted = false
    card.summoningSickness = false
  }
  for (const card of player.legendsArea) card.exhausted = false
  for (const card of player.eddiesArea) card.exhausted = false
}

function move(player: MockPlayer, from: ZoneKey, to: ZoneKey, card: Instance): void {
  player[from] = player[from].filter((item) => item.instanceId !== card.instanceId)
  card.zone = ZONE_NAMES[to]
  player[to].push(card)
}

function streetCred(player: MockPlayer): number {
  return player.gigs.reduce((total, value) => total + value, 0)
}

function totalPower(player: MockPlayer, card: Instance): number {
  const gears = player.field.filter((item) => item.attachedTo === card.instanceId)
  const power = Math.max(0, (card.power ?? 0) + card.powerBonus - card.damage)
  return gears.reduce(
    (total, gear) => total + Math.max(0, (gear.power ?? 0) + gear.powerBonus - gear.damage),
    power,
  )
}

function defeat(game: MockGame, owner: MockPlayer, card: Instance): void {
  if (card.zone !== 'FIELD') return
  const gears = owner.field.filter((item) => item.attachedTo === card.instanceId)
  for (const gear of gears) {
    move(owner, 'field', 'trash', gear)
    gear.attachedTo = null
  }
  move(owner, 'field', 'trash', card)
  card.attachments = []
  appendEvent(game, 'UNIT_DEFEATED', owner.playerId, `${card.name} est vaincue`)
}

function requireActive(game: MockGame, pseudo: string): void {
  if (game.turn.activePlayerId !== pseudo) throw new RuleError(`Ce n’est pas le tour de ${pseudo}`)
}

/** Mini-Feature 5 : l'action n'est acceptée qu'à l'étape DRAW attendue. */
function requireDrawStep(game: MockGame, expected: DrawStep): void {
  if (game.phase !== 'DRAW') {
    throw new RuleError(`Cette action n’est possible qu’en phase Draw (phase courante : ${game.phase})`)
  }
  if (game.turn.drawStep !== expected) {
    throw new RuleError(
      expected === 'AWAITING_DRAW'
        ? `La pioche n’est attendue qu’à l’étape AWAITING_DRAW (étape courante : ${game.turn.drawStep ?? '—'})`
        : `Le choix du dé n’est attendu qu’à l’étape AWAITING_DIE_SELECT (piochez d’abord) (étape courante : ${game.turn.drawStep ?? '—'})`,
    )
  }
}

/** Miroir de `Player.selectableFixerDice()` : tout sauf le d20, ou le d20 seul en dernier. */
function selectableDice(player: MockPlayer): string[] {
  const others = player.fixerDice.filter((die) => die !== LAST_DIE)
  if (others.length > 0) return others
  return player.fixerDice.includes(LAST_DIE) ? [LAST_DIE] : []
}

function appendEvent(game: MockGame, type: string, playerId: string, description: string): void {
  game.log.push({ index: game.log.length, type, playerId, description })
}

/** Ajoute une entrée au journal de diagnostic (index global, tour et phase courants). */
function appendActionLog(
  game: MockGame,
  entry: Omit<ActionLogEntry, 'index' | 'timestamp' | 'turnNumber' | 'phase'>,
): ActionLogEntry {
  const logged: ActionLogEntry = {
    index: game.gameLog.length + 1,
    timestamp: new Date().toISOString(),
    turnNumber: game.turn.number,
    phase: game.phase,
    ...entry,
  }
  game.gameLog.push(logged)
  return logged
}

/** Intention lisible d'une action, pour les lignes de refus. */
function describeIntent(payload: ActionPayload, before: Located | null): string {
  const name = before?.card.name
  switch (String(payload.action ?? '').toUpperCase()) {
    case 'PLAY_CARD':
      return name ? `joue ${name}` : 'joue une carte'
    case 'ATTACK':
      return name ? `attaque avec ${name}` : 'attaque'
    case 'SELL_CARD':
      return name ? `vend ${name}` : 'vend une carte'
    case 'SPEND_RESOURCE':
    case 'SPEND_LEGEND':
    case 'SPEND_EDDIES':
      return name ? `incline ${name} (+1 Eddie)` : 'incline une ressource'
    case 'END_TURN':
      return 'termine son tour'
    case 'DRAW_CARD':
      return 'pioche sa carte (phase Draw)'
    case 'SELECT_DIE':
      return `choisit le dé ${String(payload.dice?.[0] ?? payload.chosen ?? '?').toLowerCase()}`
    case 'USE_BLOCKER':
    case 'BLOCK':
      return `bloque avec ${String((payload.cardIds ?? payload.dice ?? []).length)} Blocker(s)`
    case 'DECLINE_BLOCK':
      return 'renonce à bloquer'
    case 'STEAL_GIG':
      return `vole ${String((payload.dice ?? payload.cardIds ?? []).length)} dé(s) Gig`
    default:
      return `action ${String(payload.action ?? '?')}`
  }
}

/** Ligne de succès d'une action acceptée (mêmes tournures que le serveur Java). */
function describeMockAction(
  pseudo: string,
  payload: ActionPayload,
  before: Located | null,
  game: MockGame,
  events: LogEntry[],
): string {
  const acted = describeIntent(payload, before)
  const action = String(payload.action ?? '').toUpperCase()
  if (action === 'END_TURN') {
    const phases = events.map((event) => event.description).join(' ; ')
    return `Joueur ${pseudo} termine le tour ${game.turn.number - 1}${phases ? ` (${phases})` : ''}`
  }
  if (action === 'DRAW_CARD') {
    const drawn = events.find((event) => event.type === 'CARD_DRAWN')
    return drawn ? `Phase DRAW : Joueur ${pseudo} pioche 1 carte` : `Phase DRAW : Joueur ${pseudo} ne peut plus piocher (deck vide)`
  }
  if (action === 'SELECT_DIE') {
    const rolled = events.find((event) => event.type === 'GIG_ROLLED')
    return `Lancer de Gig : Joueur ${pseudo} ${rolled ? rolled.description : acted}`
  }
  return `Joueur ${pseudo} ${acted}`
}

// --- Vues sérialisées (alignées sur les DTO Java) ----------------------------

function roomView(room: MockRoom): Record<string, unknown> {
  return {
    type: 'LOBBY_STATE',
    code: room.code,
    name: room.name,
    status: room.status,
    hostPseudo: room.hostPseudo,
    players: room.players.map((player) => ({
      pseudo: player.pseudo,
      seat: player.seat,
      deckId: player.deckId ?? null,
      deckCardCount: player.deck.length,
    })),
    gameId: room.gameId,
    createdAt: room.createdAt,
  }
}

function cardView(card: Instance, viewerId: string): Record<string, unknown> {
  const isOwner = card.ownerId === viewerId
  const secret =
    !isOwner && (card.zone === 'HAND' || (card.faceDown && (card.zone === 'LEGENDS_AREA' || card.zone === 'EDDIES_AREA')))

  if (secret) {
    return {
      instanceId: card.instanceId,
      cardId: 'hidden',
      name: 'Carte masquée',
      type: card.type,
      color: card.color,
      cost: 0,
      powerBonus: 0,
      damage: 0,
      keywords: [],
      abilities: [],
      ownerId: card.ownerId,
      zone: card.zone,
      faceDown: card.faceDown,
      exhausted: card.exhausted,
      summoningSickness: card.summoningSickness,
      attachments: [],
    }
  }

  return {
    instanceId: card.instanceId,
    cardId: card.cardId,
    name: card.name,
    type: card.type,
    color: card.color,
    baseCost: card.baseCost,
    cost: Math.max(0, card.cost),
    power: card.power === null ? null : Math.max(0, card.power + card.powerBonus - card.damage),
    powerBonus: card.powerBonus,
    damage: card.damage,
    streetCredThreshold: card.streetCredThreshold,
    keywords: card.keywords,
    abilities: card.abilities,
    ownerId: card.ownerId,
    zone: card.zone,
    faceDown: card.faceDown,
    exhausted: card.exhausted,
    summoningSickness: card.summoningSickness,
    attachedTo: card.attachedTo,
    attachments: [...card.attachments],
  }
}

function playerView(player: MockPlayer, viewerId: string): Record<string, unknown> {
  return {
    playerId: player.playerId,
    name: player.name,
    connected: player.connected,
    deckCount: player.deck.length,
    hand: player.hand.map((card) => cardView(card, viewerId)),
    field: player.field.map((card) => cardView(card, viewerId)),
    trash: player.trash.map((card) => cardView(card, viewerId)),
    eddiesArea: player.eddiesArea.map((card) => cardView(card, viewerId)),
    legendsArea: player.legendsArea.map((card) => cardView(card, viewerId)),
    gigs: [...player.gigs],
    gigDice: [...player.gigDice],
    gigDieIds: [...player.gigDieIds],
    fixerDice: [...player.fixerDice],
    gigCount: player.gigs.length,
    streetCred: streetCred(player),
    eddies: player.eddies,
    availableEddies: player.eddies,
    costDiscount: player.costDiscount,
    hasSoldThisTurn: player.hasSoldThisTurn,
  }
}

function stateView(game: MockGame, viewerId: string, sequence: number, now: string): Record<string, unknown> {
  return {
    gameId: game.gameId,
    phase: game.phase,
    gameOver: game.gameOver,
    winnerId: game.winnerId,
    endReason: game.endReason,
    yourPlayerId: viewerId,
    // `drawStep` n'est présent qu'en phase DRAW (le serveur Java omet les null).
    turn: game.turn.drawStep
      ? { ...game.turn }
      : { number: game.turn.number, activePlayerId: game.turn.activePlayerId },
    reactionWindow: game.reactionWindow,
    // `pendingAttack` (Mini-Feature 6) : `null` hors combat — `prune()` l'omet,
    // comme le DTO Java annoté `@JsonInclude(NON_NULL)`.
    pendingAttack: game.pendingAttack ? { ...game.pendingAttack } : null,
    players: game.players.map((player) => playerView(player, viewerId)),
    log: game.log.map((entry) => ({ ...entry })),
    gameLog: game.gameLog.slice(-50).map((entry) => ({ ...entry })),
    sequence,
    createdAt: game.createdAt ?? now,
  }
}

export { GIGS_TO_WIN, REQUIRED_LEGENDS, REQUIRED_NON_LEGENDS }
