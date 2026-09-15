/**
 * Store de la partie : état serveur, sélection, ciblage, intentions.
 *
 * Principe directeur (doc §0 et §11.2) : **le serveur fait autorité**. Chaque
 * `STATE` reçue *remplace* l'état local (jamais de patch), et les actions ne
 * font qu'envoyer une intention sur `/app/game/{gameId}/action`. Les quelques
 * règles reproduites ici (`canPlayCard`, `canAttackWith`, cibles valides…)
 * servent uniquement à griser l'UI : le serveur reste seul juge.
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { useGameSocket, type Unsubscribe } from '@/composables/useGameSocket'
import { useUiStore } from '@/stores/ui'
import type { CardKeyword } from '@/types/card'
import {
  GIGS_TO_WIN,
  type CardInstance,
  type GameActionLogEntry,
  type GameLogEntry,
  type GameLogMessage,
  type GameNotice,
  type GameState,
  type GameStateMessage,
  type PlayerState,
  type WsError,
} from '@/types/game'

export type TargetingKind = 'attack' | 'gear'

export interface TargetingRequest {
  kind: TargetingKind
  sourceInstanceId: string
  sourceName: string
  /** Vol direct de Gig autorisé (attaque sans cible). */
  allowDirect: boolean
  /** Cibles valides (instanceId). */
  candidates: string[]
}

/** Différences entre deux `STATE` : base des animations GSAP. */
export interface StateChanges {
  drawn: string[]
  /** L'adversaire a pioché (sa main est masquée : animation générique). */
  opponentDrew: boolean
  played: string[]
  defeated: string[]
  flipped: string[]
  attackerInstanceId: string | null
  gigGainers: string[]
  phaseChanged: boolean
  turnChanged: boolean
  appliedAt: number
}

/** Verdict d'ergonomie : `null` = action possible, sinon la raison du refus. */
export type Affordance = string | null

const EMPTY_CHANGES: StateChanges = {
  drawn: [],
  opponentDrew: false,
  played: [],
  defeated: [],
  flipped: [],
  attackerInstanceId: null,
  gigGainers: [],
  phaseChanged: false,
  turnChanged: false,
  appliedAt: 0,
}

function idsOf(cards: CardInstance[]): Set<string> {
  return new Set(cards.map((card) => card.instanceId))
}

function hasKeyword(card: CardInstance, keyword: CardKeyword): boolean {
  return card.keywords.includes(keyword)
}

export const useGameStore = defineStore('game', () => {
  const socket = useGameSocket()
  const ui = useUiStore()

  // --- État ---
  const gameId = ref<string | null>(null)
  const state = ref<GameState | null>(null)
  const changes = ref<StateChanges>({ ...EMPTY_CHANGES })
  const lastEvents = ref<GameLogEntry[]>([])
  /** Journal de diagnostic (feature 6.5) : chaque action, y compris refusée. */
  const debugLog = ref<GameActionLogEntry[]>([])
  const lastSequence = ref(0)
  const pendingRequestIds = ref<string[]>([])
  const selectedInstanceId = ref<string | null>(null)
  const targeting = ref<TargetingRequest | null>(null)
  const notice = ref<GameNotice | null>(null)
  const disconnection = ref<{ playerId: string; secondsLeft: number } | null>(null)
  const loading = ref(false)

  let stopWatching: Unsubscribe | null = null
  const unsubscribers: Unsubscribe[] = []
  let countdownTimer: number | null = null

  // --- Lectures de base ---
  const myPlayerId = computed(() => state.value?.yourPlayerId ?? null)
  const me = computed<PlayerState | null>(() => {
    const current = state.value
    if (!current) return null
    return current.players.find((player) => player.playerId === current.yourPlayerId) ?? current.players[0] ?? null
  })
  const opponent = computed<PlayerState | null>(() => {
    const current = state.value
    if (!current) return null
    return current.players.find((player) => player.playerId !== current.yourPlayerId) ?? null
  })
  const phase = computed(() => state.value?.phase ?? null)
  const turnNumber = computed(() => state.value?.turn.number ?? 0)
  const activePlayerId = computed(() => state.value?.turn.activePlayerId ?? null)
  const isMyTurn = computed(() => !!myPlayerId.value && activePlayerId.value === myPlayerId.value)
  const isGameOver = computed(() => state.value?.gameOver ?? false)
  const winnerId = computed(() => state.value?.winnerId ?? null)
  const endReason = computed(() => state.value?.endReason ?? null)
  const iWon = computed(() => !!winnerId.value && winnerId.value === myPlayerId.value)
  const reactionWindow = computed(() => state.value?.reactionWindow ?? null)
  /** Fenêtre QUICK ouverte pour moi : je peux répondre hors de mon tour. */
  const iAmReacting = computed(
    () => !!reactionWindow.value && reactionWindow.value.defendingPlayerId === myPlayerId.value,
  )
  const log = computed<GameLogEntry[]>(() => state.value?.log ?? [])
  const waitingForServer = computed(() => pendingRequestIds.value.length > 0)
  const opponentBlockerReady = computed(
    () => opponent.value?.field.some((card) => card.type === 'unit' && hasKeyword(card, 'blocker') && !card.exhausted) ?? false,
  )

  const myFieldUnits = computed(() => me.value?.field.filter((card) => card.type === 'unit' && !card.attachedTo) ?? [])
  const opponentFieldUnits = computed(
    () => opponent.value?.field.filter((card) => card.type === 'unit' && !card.attachedTo) ?? [],
  )
  const selectedCard = computed<CardInstance | null>(() => {
    const id = selectedInstanceId.value
    if (!id || !me.value) return null
    return (
      me.value.hand.find((card) => card.instanceId === id) ??
      me.value.field.find((card) => card.instanceId === id) ??
      me.value.legendsArea.find((card) => card.instanceId === id) ??
      me.value.eddiesArea.find((card) => card.instanceId === id) ??
      null
    )
  })

  const canActNow = computed(
    () => !!state.value && !isGameOver.value && (isMyTurn.value || iAmReacting.value) && isActionPhase.value,
  )
  const isActionPhase = computed(() => phase.value === 'MAIN' || phase.value === 'COMBAT')
  const canEndTurn = computed(() => !!state.value && !isGameOver.value && isMyTurn.value && !waitingForServer.value)
  const canSell = computed(
    () => !!me.value && !isGameOver.value && isMyTurn.value && phase.value === 'MAIN' && !me.value.hasSoldThisTurn,
  )
  const gigsToWin = GIGS_TO_WIN

  /**
   * Mini-Feature 4 (R4) — générer des Eddies : miroir des gardes serveur de
   * `SpendResourceCommand` (doc §5.1 / RULE-ENGINE §4). `null` = la carte
   * (Legend de la Legends Area ou carte vendue de l'Eddies Area) peut être
   * inclinée pour +1 Eddie.
   */
  function canSpendCard(card: CardInstance): Affordance {
    if (!state.value) return 'État de partie indisponible'
    if (isGameOver.value) return 'Partie terminée'
    if (!isMyTurn.value) return `Ce n’est pas ton tour (${activePlayerId.value ?? '?'} joue)`
    if (phase.value !== 'MAIN') return 'On incline une ressource qu’en phase Principale'
    if (card.zone !== 'LEGENDS_AREA' && card.zone !== 'EDDIES_AREA') {
      return 'Seule une Legend ou une carte de la zone Eddies peut être inclinée'
    }
    if (card.exhausted) return 'Cette carte est déjà inclinée (elle reprend au début de ton prochain tour)'
    return null
  }

  // --- Ergonomie des actions (miroir des gardes serveur, doc §5.1 / RULE-ENGINE §4) ---
  function affordability(card: CardInstance): Affordance {
    const player = me.value
    if (!player) return 'État de partie indisponible'
    if (card.streetCredThreshold != null && player.streetCred < card.streetCredThreshold) {
      return `Street Cred insuffisant : ${player.streetCred} pour ${card.streetCredThreshold}`
    }
    const toPay = Math.max(0, card.cost - player.costDiscount)
    if (toPay > player.availableEddies) {
      return `Eddies insuffisants : ${player.availableEddies} pour un coût de ${toPay}`
    }
    return null
  }

  function canPlayCard(card: CardInstance): Affordance {
    if (!state.value) return 'État de partie indisponible'
    if (isGameOver.value) return 'Partie terminée'
    if (!isActionPhase.value) return 'On ne joue des cartes qu’en phase Principale ou Combat'

    if (iAmReacting.value) {
      if (!hasKeyword(card, 'quick')) return 'En réaction, seules les cartes QUICK peuvent être jouées'
    } else if (!isMyTurn.value) {
      return `Ce n’est pas ton tour (${activePlayerId.value ?? '?'} joue)`
    }

    if (card.type === 'legend') {
      if (card.zone !== 'LEGENDS_AREA' || !card.faceDown) return 'Seule une Legend face cachée peut être retournée'
      return affordability(card)
    }
    if (card.zone !== 'HAND') return 'Cette carte n’est pas jouable depuis cette zone'
    if (card.type === 'gear' && myFieldUnits.value.length === 0) return 'Aucune Unit alliée à équiper'
    return affordability(card)
  }

  function canAttackWith(card: CardInstance): Affordance {
    if (!state.value) return 'État de partie indisponible'
    if (isGameOver.value) return 'Partie terminée'
    if (!isMyTurn.value) return 'Ce n’est pas ton tour'
    if (!isActionPhase.value) return 'On n’attaque qu’en phase Principale ou Combat'
    if (card.type !== 'unit') return 'Seule une Unit peut attaquer'
    if (card.exhausted) return 'Cette Unit est déjà épuisée'
    if (card.summoningSickness && !hasKeyword(card, 'go_solo')) return 'Mal d’invocation : attente du prochain tour'
    return null
  }

  /** Cibles légales d'une attaque : interception BLOCKER comprise. */
  function validAttackTargets(attacker: CardInstance): string[] {
    if (canAttackWith(attacker) !== null) return []
    const rivals = opponentFieldUnits.value
    if (opponentBlockerReady.value) {
      return rivals.filter((card) => hasKeyword(card, 'blocker') && !card.exhausted).map((card) => card.instanceId)
    }
    return rivals.map((card) => card.instanceId)
  }

  function canStealGig(attacker: CardInstance): boolean {
    if (canAttackWith(attacker) !== null) return false
    if (opponentBlockerReady.value) return false
    return (opponent.value?.gigCount ?? 0) > 0
  }

  function canEquipGear(gear: CardInstance): boolean {
    return canPlayCard(gear) === null && myFieldUnits.value.length > 0
  }

  /** Unités alliées équipables par un Gear. */
  function validGearHosts(): string[] {
    return myFieldUnits.value.map((card) => card.instanceId)
  }

  // --- Réception d'état ---
  function computeChanges(previous: GameState | null, next: GameState, events: GameLogEntry[]): StateChanges {
    const result: StateChanges = { ...EMPTY_CHANGES, appliedAt: Date.now() }
    const viewer = next.yourPlayerId
    const myNext = next.players.find((player) => player.playerId === viewer)
    const myPrev = previous?.players.find((player) => player.playerId === viewer)

    if (myNext && myPrev) {
      const before = idsOf(myPrev.hand)
      result.drawn = myNext.hand.filter((card) => !before.has(card.instanceId)).map((card) => card.instanceId)
    }

    const prevField = new Map<string, CardInstance>()
    for (const player of previous?.players ?? []) {
      for (const card of player.field) prevField.set(card.instanceId, card)
    }
    for (const player of next.players) {
      for (const card of player.field) {
        if (!prevField.has(card.instanceId)) result.played.push(card.instanceId)
      }
    }
    const nextField = new Set<string>()
    for (const player of next.players) {
      for (const card of player.field) nextField.add(card.instanceId)
    }
    for (const instanceId of prevField.keys()) {
      if (!nextField.has(instanceId)) result.defeated.push(instanceId)
    }
    for (const player of previous?.players ?? []) {
      const legend = next.players.find((p) => p.playerId === player.playerId)
      if (!legend) continue
      for (const card of player.legendsArea) {
        const now = legend.legendsArea.find((c) => c.instanceId === card.instanceId)
        if (card.faceDown && now && !now.faceDown) result.flipped.push(card.instanceId)
      }
    }

    result.opponentDrew = events.some((event) => event.type === 'CARD_DRAWN' && event.playerId !== viewer)

    const attacked = events.some((event) => event.type === 'ATTACK_DECLARED')
    if (attacked) result.attackerInstanceId = next.reactionWindow?.attackerInstanceId ?? null

    for (const player of next.players) {
      const before = previous?.players.find((p) => p.playerId === player.playerId)?.gigCount ?? 0
      if (player.gigCount > before) result.gigGainers.push(player.playerId)
    }

    result.phaseChanged = !!previous && previous.phase !== next.phase
    result.turnChanged = !!previous && previous.turn.activePlayerId !== next.turn.activePlayerId
    return result
  }

  function diffEvents(previous: GameState | null, next: GameState): GameLogEntry[] {
    if (!previous) return []
    return next.log.filter((entry) => entry.index >= previous.log.length)
  }

  function pruneSelection(): void {
    const id = selectedInstanceId.value
    if (!id || !me.value) return
    const stillThere =
      me.value.hand.some((card) => card.instanceId === id) || me.value.field.some((card) => card.instanceId === id)
    if (!stillThere) selectedInstanceId.value = null
  }

  function applyStateMessage(message: GameStateMessage): void {
    if (!message || message.type !== 'STATE') return
    if (gameId.value && message.gameId !== gameId.value) return

    const next = message.state
    if (!next) return

    // États périmés ignorés ; trou de séquence → resync (doc §7.5).
    if (lastSequence.value > 0 && next.sequence < lastSequence.value) return
    const missed = lastSequence.value > 0 && next.sequence > lastSequence.value + 1

    const previous = state.value
    const events = message.newEvents?.length ? message.newEvents : diffEvents(previous, next)

    state.value = next
    mergeDebugLog(next.gameLog)
    lastSequence.value = next.sequence
    lastEvents.value = events
    changes.value = computeChanges(previous, next, events)
    loading.value = false

    if (message.clientRequestId) {
      pendingRequestIds.value = pendingRequestIds.value.filter((id) => id !== message.clientRequestId)
    }
    pruneSelection()
    if (targeting.value && !targetingCandidatesStillValid(targeting.value)) targeting.value = null
    if (next.gameOver) stopCountdown()
    if (missed && gameId.value) socket.requestResync(gameId.value)
  }

  /**
   * Fusionne des entrées du journal de diagnostic (borné à 200 entrées, comme
   * le serveur) : `LOG` en temps réel et `gameLog` embarqué dans les états.
   */
  function mergeDebugLog(entries?: GameActionLogEntry[] | null): void {
    if (!entries?.length) return
    const byIndex = new Map(debugLog.value.map((entry) => [entry.index, entry]))
    for (const entry of entries) {
      if (entry && typeof entry.index === 'number') byIndex.set(entry.index, entry)
    }
    debugLog.value = [...byIndex.values()].sort((a, b) => a.index - b.index).slice(-200)
  }

  function handleDebugLog(message: GameLogMessage): void {
    if (!message || message.type !== 'LOG') return
    if (gameId.value && message.gameId !== gameId.value) return
    mergeDebugLog(message.entries)
  }

  /** Le ciblage en cours a-t-il encore un sens après réception d'un nouvel état ? */
  function targetingCandidatesStillValid(request: TargetingRequest): boolean {
    const source = findInstance(request.sourceInstanceId)
    if (!source) return false
    return request.kind === 'attack' ? canAttackWith(source) === null : canPlayCard(source) === null
  }

  function findInstance(instanceId: string): CardInstance | null {
    const current = state.value
    if (!current) return null
    for (const player of current.players) {
      for (const zone of [player.hand, player.field, player.trash, player.legendsArea, player.eddiesArea]) {
        const found = zone.find((card) => card.instanceId === instanceId)
        if (found) return found
      }
    }
    return null
  }

  // --- Notifications publiques ---
  function handleNotice(payload: GameNotice): void {
    if (!payload || (gameId.value && payload.gameId !== gameId.value)) return
    notice.value = payload

    switch (payload.type) {
      case 'GAME_STARTED':
        ui.info('La partie démarre')
        break
      case 'GAME_OVER':
        stopCountdown()
        ui.info(payload.endReason ?? 'Partie terminée')
        break
      case 'PLAYER_DISCONNECTED':
        startCountdown(payload.playerId ?? '?', payload.reconnectDeadInSeconds ?? 120)
        ui.warn(`${payload.playerId ?? 'Un joueur'} s’est déconnecté`)
        break
      case 'PLAYER_RECONNECTED':
        stopCountdown()
        ui.success(`${payload.playerId ?? 'Le joueur'} est de retour`)
        break
    }
  }

  function startCountdown(playerId: string, seconds: number): void {
    stopCountdown()
    disconnection.value = { playerId, secondsLeft: seconds }
    countdownTimer = window.setInterval(() => {
      const current = disconnection.value
      if (!current) return stopCountdown()
      if (current.secondsLeft <= 1) return stopCountdown()
      disconnection.value = { ...current, secondsLeft: current.secondsLeft - 1 }
    }, 1_000)
  }

  function stopCountdown(): void {
    if (countdownTimer !== null) {
      window.clearInterval(countdownTimer)
      countdownTimer = null
    }
    disconnection.value = null
  }

  function handleError(payload: WsError): void {
    if (gameId.value && payload.gameId && payload.gameId !== gameId.value) return
    if (payload.clientRequestId) {
      pendingRequestIds.value = pendingRequestIds.value.filter((id) => id !== payload.clientRequestId)
    }
    if (payload.code === 'GAME_NOT_FOUND') {
      detach()
      ui.error('Partie introuvable côté serveur')
      return
    }
    ui.error(payload.message)
  }

  // --- Envoi d'intentions ---
  function dispatch(command: Parameters<typeof socket.sendAction>[1]): boolean {
    if (!gameId.value) {
      ui.error('Aucune partie en cours')
      return false
    }
    const requestId = socket.sendAction(gameId.value, command)
    if (!requestId) {
      ui.error('Canal temps réel indisponible — reconnexion en cours')
      return false
    }
    pendingRequestIds.value = [...pendingRequestIds.value, requestId]
    return true
  }

  function playCard(instanceId: string, targetInstanceId?: string | null): boolean {
    const card = findInstance(instanceId)
    const reason = card ? canPlayCard(card) : 'Carte introuvable'
    if (reason) {
      ui.warn(reason)
      return false
    }
    if (card?.type === 'gear' && !targetInstanceId) {
      beginEquip(instanceId)
      return false
    }
    const sent = dispatch({ action: 'PLAY_CARD', instanceId, targetInstanceId: targetInstanceId ?? null })
    if (sent) clearSelection()
    return sent
  }

  function attack(attackerInstanceId: string, targetInstanceId?: string | null): boolean {
    const attacker = findInstance(attackerInstanceId)
    const reason = attacker ? canAttackWith(attacker) : 'Attaquant introuvable'
    if (reason) {
      ui.warn(reason)
      return false
    }
    const sent = dispatch({ action: 'ATTACK', instanceId: attackerInstanceId, targetInstanceId: targetInstanceId ?? null })
    if (sent) clearSelection()
    return sent
  }

  function sellCard(instanceId: string): boolean {
    const card = findInstance(instanceId)
    if (!card || card.zone !== 'HAND') {
      ui.warn('La vente ne concerne qu’une carte de ta main')
      return false
    }
    if (!canSell.value) {
      ui.warn(me.value?.hasSoldThisTurn ? 'Une seule vente par tour (déjà effectuée)' : 'Vente possible uniquement en phase Principale, à ton tour')
      return false
    }
    const sent = dispatch({ action: 'SELL_CARD', instanceId })
    if (sent) clearSelection()
    return sent
  }

  /** Mini-Feature 4 (R4) : incliner une ressource (Legend ou carte Eddies) pour +1 Eddie. */
  function spendResource(instanceId: string): boolean {
    const card = findInstance(instanceId)
    const reason = card ? canSpendCard(card) : 'Carte introuvable'
    if (reason) {
      ui.warn(reason)
      return false
    }
    const sent = dispatch({ action: 'SPEND_RESOURCE', instanceId })
    if (sent) clearSelection()
    return sent
  }

  function endTurn(): boolean {
    if (!canEndTurn.value) {
      ui.warn(isMyTurn.value ? 'Fin de tour indisponible pour le moment' : 'Ce n’est pas ton tour')
      return false
    }
    const sent = dispatch({ action: 'END_TURN' })
    if (sent) clearSelection()
    return sent
  }

  function concede(): boolean {
    return dispatch({ action: 'CONCEDE' })
  }

  // --- Sélection et ciblage ---
  function selectCard(instanceId: string | null): void {
    selectedInstanceId.value = instanceId
    if (!instanceId) targeting.value = null
  }

  function clearSelection(): void {
    selectedInstanceId.value = null
  }

  function beginAttack(attackerInstanceId: string): void {
    const attacker = findInstance(attackerInstanceId)
    if (!attacker) return
    const candidates = validAttackTargets(attacker)
    if (candidates.length === 0 && !canStealGig(attacker)) {
      ui.warn('Aucune cible disponible pour cette Unit')
      return
    }
    selectCard(attackerInstanceId)
    targeting.value = {
      kind: 'attack',
      sourceInstanceId: attackerInstanceId,
      sourceName: attacker.name,
      allowDirect: canStealGig(attacker),
      candidates,
    }
  }

  function beginEquip(gearInstanceId: string): void {
    const gear = findInstance(gearInstanceId)
    if (!gear) return
    const hosts = validGearHosts()
    if (hosts.length === 0) {
      ui.warn('Aucune Unit alliée à équiper')
      return
    }
    selectCard(gearInstanceId)
    targeting.value = {
      kind: 'gear',
      sourceInstanceId: gearInstanceId,
      sourceName: gear.name,
      allowDirect: false,
      candidates: hosts,
    }
  }

  function cancelTargeting(): void {
    targeting.value = null
  }

  function chooseTarget(targetInstanceId: string): boolean {
    const request = targeting.value
    if (!request) return false
    if (!request.candidates.includes(targetInstanceId)) {
      ui.warn('Cible invalide')
      return false
    }
    targeting.value = null
    return request.kind === 'attack'
      ? attack(request.sourceInstanceId, targetInstanceId)
      : playCard(request.sourceInstanceId, targetInstanceId)
  }

  /** Attaque sans cible : vol direct de Gig. */
  function stealGig(): boolean {
    const request = targeting.value
    if (!request || request.kind !== 'attack' || !request.allowDirect) return false
    targeting.value = null
    return attack(request.sourceInstanceId, null)
  }

  // --- Cycle de vie ---
  function attach(id: string): void {
    if (gameId.value === id && stopWatching) {
      socket.requestResync(id)
      return
    }
    detach()

    gameId.value = id
    state.value = null
    lastSequence.value = 0
    loading.value = true

    // Abonnements applicatifs AVANT les abonnements STOMP (doc §11.1).
    unsubscribers.push(socket.onGameState(applyStateMessage))
    unsubscribers.push(socket.onGameLog(handleDebugLog))
    unsubscribers.push(socket.onGameNotice(handleNotice))
    unsubscribers.push(socket.onWsError(handleError))
    unsubscribers.push(socket.onReconnected(() => socket.requestResync(id)))
    stopWatching = socket.watchGame(id)
  }

  function detach(): void {
    for (const unsubscribe of unsubscribers.splice(0)) unsubscribe()
    stopWatching?.()
    stopWatching = null
    stopCountdown()
    gameId.value = null
    state.value = null
    changes.value = { ...EMPTY_CHANGES }
    lastEvents.value = []
    debugLog.value = []
    lastSequence.value = 0
    pendingRequestIds.value = []
    selectedInstanceId.value = null
    targeting.value = null
    notice.value = null
    loading.value = false
  }

  return {
    // état
    gameId,
    state,
    changes,
    lastEvents,
    lastSequence,
    pendingRequestIds,
    selectedInstanceId,
    targeting,
    notice,
    disconnection,
    loading,
    // dérivés
    myPlayerId,
    me,
    opponent,
    phase,
    turnNumber,
    activePlayerId,
    isMyTurn,
    isActionPhase,
    isGameOver,
    winnerId,
    endReason,
    iWon,
    iAmReacting,
    reactionWindow,
    log,
    debugLog,
    waitingForServer,
    opponentBlockerReady,
    myFieldUnits,
    opponentFieldUnits,
    selectedCard,
    canActNow,
    canEndTurn,
    canSell,
    gigsToWin,
    // ergonomie
    canPlayCard,
    canSpendCard,
    canAttackWith,
    canEquipGear,
    canStealGig,
    validAttackTargets,
    validGearHosts,
    findInstance,
    // actions
    attach,
    detach,
    selectCard,
    clearSelection,
    playCard,
    attack,
    sellCard,
    spendResource,
    endTurn,
    concede,
    beginAttack,
    beginEquip,
    cancelTargeting,
    chooseTarget,
    stealGig,
  }
})
