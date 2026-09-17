/**
 * Mini-Feature 10B — « réactivité des clics » : aucun premier clic ne doit être
 * avalé par un calque invisible ni par un verrou Pinia coincé.
 *
 * Deux familles de causes, deux familles de tests :
 *
 *  1. **Verrou `waitingForServer`** (store `game`) : une intention dont l'accusé
 *     (STATE/ERROR avec `clientRequestId`) ne revient jamais ne doit plus figer
 *     le plateau — purge à la reconnexion, expiration avec resync automatique.
 *     Couvre aussi la sélection Legend/Eddies effacée à tort par `pruneSelection`
 *     (le 2ᵉ clic resélectionnait au lieu de retourner/incliner).
 *
 *  2. **Calques décoratifs** (composants) : les éléments transparents superposés
 *     aux zones cliquables portent `pointer-events-none` ; le bouton d'inspection
 *     🔍 (invisible hors survol) n'intercepte plus le clic du coin de la carte.
 *
 * Le socket est simulé par substitution de module (`vi.mock`) : on pilote les
 * messages `STATE`/`ERROR` à la main pour rejouer les scénarios de perte d'accusé.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'

import CardComponent from '@/components/CardComponent.vue'
import TargetingOverlay from '@/components/TargetingOverlay.vue'
import { PENDING_ACK_TIMEOUT_MS, useGameStore } from '@/stores/game'
import { useUiStore } from '@/stores/ui'
import type { GameCard } from '@/types/card'
import type { GameState, GameStateMessage } from '@/types/game'
import type { CardInstance, PlayerState, WsError } from '@/types/game'

// --- Faux socket (substitution de module) ------------------------------------

/**
 * `vi.hoisted` : les fabriques `vi.mock` sont hissées au-dessus des imports,
 * le harnais doit donc être construit dans ce bloc (pas de valeur importée).
 */
const harness = vi.hoisted(() => {
  type Handler = (payload: never) => void

  const captured = {
    state: new Set<Handler>(),
    log: new Set<Handler>(),
    notice: new Set<Handler>(),
    error: new Set<Handler>(),
    reconnected: new Set<Handler>(),
  }

  function track(set: Set<Handler>) {
    return (handler: Handler) => {
      set.add(handler)
      return () => set.delete(handler)
    }
  }

  const api = {
    sendAction: vi.fn(),
    requestResync: vi.fn(),
    watchGame: vi.fn(() => () => undefined),
    onGameState: track(captured.state),
    onGameLog: track(captured.log),
    onGameNotice: track(captured.notice),
    onWsError: track(captured.error),
    onReconnected: track(captured.reconnected),
  }

  return { api, captured }
})

vi.mock('@/composables/useGameSocket', () => ({
  useGameSocket: () => harness.api,
}))

// --- Fabriques d'état ---------------------------------------------------------

function instance(partial: Partial<CardInstance> & { instanceId: string }): CardInstance {
  return {
    cardId: partial.instanceId,
    name: `Carte ${partial.instanceId}`,
    type: 'unit',
    color: 'red',
    cost: 0,
    powerBonus: 0,
    damage: 0,
    keywords: [],
    abilities: [],
    ownerId: 'me',
    zone: 'HAND',
    faceDown: false,
    exhausted: false,
    summoningSickness: false,
    ...partial,
  }
}

function playerState(partial: Partial<PlayerState> & { playerId: string }): PlayerState {
  return {
    name: partial.playerId,
    connected: true,
    deckCount: 30,
    hand: [],
    field: [],
    trash: [],
    eddiesArea: [],
    legendsArea: [],
    gigs: [],
    fixerDice: ['d20', 'd12', 'd10', 'd8', 'd6', 'd4'],
    gigCount: 0,
    streetCred: 0,
    eddies: 0,
    availableEddies: 0,
    costDiscount: 0,
    hasSoldThisTurn: false,
    ...partial,
  }
}

function gameState(partial: Partial<GameState> = {}): GameState {
  return {
    gameId: 'game-1',
    phase: 'MAIN',
    gameOver: false,
    yourPlayerId: 'me',
    turn: { number: 1, activePlayerId: 'me' },
    players: [playerState({ playerId: 'me' }), playerState({ playerId: 'opponent' })],
    log: [],
    sequence: 1,
    createdAt: new Date().toISOString(),
    ...partial,
  }
}

// --- Suite : verrou « serveur… » ----------------------------------------------

describe('Réactivité des clics — verrou waitingForServer (store game)', () => {
  let pinia: Pinia
  let game: ReturnType<typeof useGameStore>
  let sequence = 0

  /** Pousse une `STATE` complète dans le store (séquence strictement croissante). */
  function pushState(partial: Partial<GameState> = {}, clientRequestId?: string): void {
    sequence += 1
    const message: GameStateMessage = {
      type: 'STATE',
      gameId: 'game-1',
      state: gameState({ sequence, ...partial }),
      ...(clientRequestId ? { clientRequestId } : {}),
    }
    for (const handler of [...harness.captured.state]) {
      ;(handler as (payload: GameStateMessage) => void)(message)
    }
  }

  function pushError(payload: Omit<WsError, 'type'>): void {
    const message: WsError = { type: 'ERROR', ...payload }
    for (const handler of [...harness.captured.error]) {
      ;(handler as (payload: WsError) => void)(message)
    }
  }

  beforeEach(() => {
    let requestCounter = 0
    harness.api.sendAction.mockImplementation(() => `req-${(requestCounter += 1)}`)
    harness.api.requestResync.mockClear()
    for (const set of Object.values(harness.captured)) set.clear()

    pinia = createPinia()
    setActivePinia(pinia)
    sequence = 0
    game = useGameStore()
    game.attach('game-1')
    pushState() // première STATE : séquence 1, tour de « me », phase MAIN
  })

  afterEach(() => {
    game.detach()
  })

  it('l’accusé de l’action libère le verrou (comportement nominal)', () => {
    expect(game.waitingForServer).toBe(false)
    expect(game.endTurn()).toBe(true)
    expect(game.waitingForServer).toBe(true)

    pushState({ turn: { number: 2, activePlayerId: 'opponent' } }, 'req-1')

    expect(game.waitingForServer).toBe(false)
    expect(game.pendingRequestIds).toEqual([])
  })

  it('un accusé perdu ne fige plus le plateau : expiration + resync automatique', () => {
    vi.useFakeTimers()
    try {
      expect(game.endTurn()).toBe(true)
      expect(game.waitingForServer).toBe(true)

      // Aucun accusé ne revient (message perdu) : le verrou saute tout seul.
      vi.advanceTimersByTime(PENDING_ACK_TIMEOUT_MS + 1)

      expect(game.waitingForServer).toBe(false)
      expect(harness.api.requestResync).toHaveBeenCalledWith('game-1')
      const ui = useUiStore()
      expect(ui.toasts.some((toast) => toast.kind === 'warn' && toast.message.includes('resynchronisation'))).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('l’expiration n’oublie pas les autres intentions encore valides', () => {
    vi.useFakeTimers()
    try {
      expect(game.endTurn()).toBe(true) // req-1
      expect(game.waitingForServer).toBe(true)

      // L'accusé de req-1 revient normalement : aucune parasite à l'expiration.
      pushState({ turn: { number: 2, activePlayerId: 'opponent' } }, 'req-1')
      vi.advanceTimersByTime(PENDING_ACK_TIMEOUT_MS * 4)

      expect(game.waitingForServer).toBe(false)
      const ui = useUiStore()
      expect(ui.toasts.some((toast) => toast.message.includes('resynchronisation'))).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('une erreur serveur avec clientRequestId libère immédiatement le verrou', () => {
    expect(game.endTurn()).toBe(true)
    expect(game.waitingForServer).toBe(true)

    pushError({ code: 'ILLEGAL_ACTION', message: 'Action refusée', gameId: 'game-1', clientRequestId: 'req-1' })

    expect(game.waitingForServer).toBe(false)
    expect(game.pendingRequestIds).toEqual([])
  })

  it('la reconnexion purge les intentions en vol avant le resync', () => {
    expect(game.endTurn()).toBe(true)
    expect(game.waitingForServer).toBe(true)

    for (const handler of [...harness.captured.reconnected]) {
      ;(handler as (attempt: number) => void)(2)
    }

    expect(game.waitingForServer).toBe(false)
    expect(harness.api.requestResync).toHaveBeenCalledWith('game-1')
  })

  it('un resync sans clientRequestId ne bloque pas non plus (garde-fou actif)', () => {
    vi.useFakeTimers()
    try {
      expect(game.endTurn()).toBe(true)
      // Trou de séquence : le serveur renvoie la STATE complète avec requestId nul,
      // exactement le cas qui figeait l'interface avant le garde-fou.
      sequence += 3
      pushState({ turn: { number: 2, activePlayerId: 'opponent' } })
      expect(game.waitingForServer).toBe(true) // toujours en attente de l'accusé…

      vi.advanceTimersByTime(PENDING_ACK_TIMEOUT_MS + 1)
      expect(game.waitingForServer).toBe(false) // …mais plus pour toujours.
    } finally {
      vi.useRealTimers()
    }
  })

  it('detach() annule les minuteurs : plus d’expiration fantôme après la partie', () => {
    vi.useFakeTimers()
    try {
      expect(game.endTurn()).toBe(true)
      game.detach()
      harness.api.requestResync.mockClear()
      vi.advanceTimersByTime(PENDING_ACK_TIMEOUT_MS * 4)
      expect(harness.api.requestResync).not.toHaveBeenCalled()
    } finally {
      vi.useRealTimers()
    }
  })
})

// --- Suite : sélection Legend / Eddies ----------------------------------------

describe('Réactivité des clics — sélection Legends/Eddies survit aux STATE', () => {
  let pinia: Pinia
  let game: ReturnType<typeof useGameStore>
  let sequence = 0

  const legend = instance({
    instanceId: 'leg-1',
    name: 'Légende cachée',
    type: 'legend',
    zone: 'LEGENDS_AREA',
    faceDown: true,
  })
  const eddieCard = instance({ instanceId: 'edd-1', name: 'Carte vendue', zone: 'EDDIES_AREA', faceDown: true })
  const handCard = instance({ instanceId: 'hand-1', name: 'Solo en main', zone: 'HAND' })

  function players(hand: CardInstance[] = [handCard]): PlayerState[] {
    return [
      playerState({ playerId: 'me', hand, legendsArea: [legend], eddiesArea: [eddieCard], availableEddies: 9 }),
      playerState({ playerId: 'opponent' }),
    ]
  }

  function pushState(partial: Partial<GameState> = {}): void {
    sequence += 1
    const message: GameStateMessage = {
      type: 'STATE',
      gameId: 'game-1',
      state: gameState({ sequence, players: players(), ...partial }),
    }
    for (const handler of [...harness.captured.state]) {
      ;(handler as (payload: GameStateMessage) => void)(message)
    }
  }

  beforeEach(() => {
    let requestCounter = 0
    harness.api.sendAction.mockImplementation(() => `req-${(requestCounter += 1)}`)
    harness.api.requestResync.mockClear()
    for (const set of Object.values(harness.captured)) set.clear()

    pinia = createPinia()
    setActivePinia(pinia)
    sequence = 0
    game = useGameStore()
    game.attach('game-1')
    pushState()
  })

  afterEach(() => {
    game.detach()
  })

  it('le clic sur une Legend reste sélectionné après une STATE sans rapport', () => {
    game.selectCard('leg-1')
    expect(game.selectedInstanceId).toBe('leg-1')

    // Une STATE quelconque (action adverse, journal…) ne doit pas voler la sélection :
    // sinon le second clic (« retourner ») retombait sur « sélectionner ».
    pushState({ turn: { number: 1, activePlayerId: 'me' } })

    expect(game.selectedInstanceId).toBe('leg-1')
    expect(game.selectedCard?.instanceId).toBe('leg-1')
  })

  it('le clic sur une ressource Eddies reste sélectionné après une STATE sans rapport', () => {
    game.selectCard('edd-1')
    pushState({ log: [{ index: 1, type: 'TURN_STARTED', description: ' bruit de fond' }] })

    expect(game.selectedInstanceId).toBe('edd-1')
    expect(game.selectedCard?.instanceId).toBe('edd-1')
  })

  it('une carte réellement disparue de mes zones reste désélectionnée', () => {
    game.selectCard('hand-1')
    // La carte quitte la main (jouée/vendue) : la sélection doit être nettoyée.
    pushState({ players: players([]) })

    expect(game.selectedInstanceId).toBeNull()
  })
})

// --- Suite : calques décoratifs (composants) ----------------------------------

const DEFINITION: GameCard = {
  id: 'card-1',
  name: 'Solo',
  subtitle: null,
  type: 'unit',
  color: 'red',
  ram: 1,
  cost: 1,
  power: 5,
  streetCred: null,
  tags: [],
  keywords: [],
  text: '',
  abilities: [],
  imageUrl: 'https://img.test/solo.png',
  setCode: 'TST',
  collectorNumber: '001',
  rarity: null,
}

describe('Réactivité des clics — CardComponent', () => {
  it('le premier clic sur la carte émet immédiatement la sélection', async () => {
    const card = instance({ instanceId: 'unit-1', name: 'Solo', zone: 'FIELD' })
    const wrapper = mount(CardComponent, { props: { card, definition: DEFINITION } })

    await wrapper.get('article').trigger('click')

    const emitted = wrapper.emitted('click')
    expect(emitted).toHaveLength(1)
    expect(emitted?.[0]).toEqual([card])
  })

  it('le bouton 🔍 invisible n’intercepte plus le clic (pointer-events pilotés)', () => {
    const card = instance({ instanceId: 'unit-2', name: 'Solo', zone: 'FIELD' })
    const wrapper = mount(CardComponent, { props: { card, definition: DEFINITION } })

    const inspect = wrapper.get('button[aria-label^="Inspecter"]')
    // Invisible au repos → exclu du hit-test ; le clic traverse jusqu'à la carte.
    expect(inspect.classes()).toContain('pointer-events-none')
    // …mais redevient cliquable exactement quand il devient visible.
    expect(inspect.classes()).toContain('group-hover:pointer-events-auto')
    expect(inspect.classes()).toContain('focus:pointer-events-auto')
  })
})

describe('Réactivité des clics — TargetingOverlay', () => {
  it('voile et réticule sont décoratifs (pointer-events-none), la bannière cliquable', () => {
    const wrapper = mount(TargetingOverlay, {
      props: { kind: 'attack', sourceName: 'Solo', candidates: ['a', 'b'], allowDirect: true },
    })

    const root = wrapper.get('div')
    expect(root.classes()).toContain('pointer-events-none')

    const decorative = wrapper.findAll('[aria-hidden="true"]')
    expect(decorative.length).toBeGreaterThan(0)
    for (const layer of decorative) {
      expect(layer.classes()).toContain('pointer-events-none')
    }

    const banner = wrapper.get('[role="dialog"]').element.parentElement
    expect(banner?.classList.contains('pointer-events-auto')).toBe(true)

    wrapper.unmount()
  })

  it('les boutons Annuler et Attaque directe répondent au premier clic', async () => {
    const wrapper = mount(TargetingOverlay, {
      props: { kind: 'attack', sourceName: 'Solo', candidates: ['a'], allowDirect: true },
    })

    await wrapper.get('[data-targeting-direct]').trigger('click')
    expect(wrapper.emitted('direct')).toHaveLength(1)

    await wrapper.get('[data-targeting-cancel]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)

    wrapper.unmount()
  })
})
