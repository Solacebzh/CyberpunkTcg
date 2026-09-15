/**
 * Test de flux : Lobby → Partie → Vendre → Jouer → Attaquer → Fin de tour.
 *
 * Ce qui est réellement exercé : les composants (`LobbyView`, `GameView`,
 * `CardComponent`, `TargetingOverlay`, `PlayerArea`), les stores Pinia
 * (`lobby`, `game`, `deck`, `ui`) et le composable `useGameSocket` avec le vrai
 * client `@stomp/stompjs`. Seule l'extrémité réseau est simulée
 * (`devtools/mock-protocol.ts`, contrat de docs/WEBSOCKET-PROTOCOL.md).
 *
 * Les assertions portent donc sur le **contrat côté client** : destinations
 * souscrites, payloads envoyés, remplacement d'état, rendu du plateau.
 * Les règles elles-mêmes sont testées côté backend (`mvn test`).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import GameView from '@/views/GameView.vue'
import LobbyView from '@/views/LobbyView.vue'
import { __resetGameSocketForTests, useGameSocket } from '@/composables/useGameSocket'
import { useDeckStore } from '@/stores/deck'
import { useGameStore } from '@/stores/game'
import { useLobbyStore } from '@/stores/lobby'
import type { CardInstance, PlayerState } from '@/types/game'

import {
  RawClient,
  card,
  commandsTo,
  installMockServer,
  tick,
  uninstallMockServer,
} from './helpers/stompHarness'
import type { MockCard, MockGameServer } from '../../devtools/mock-protocol'

// --- Fixtures ---------------------------------------------------------------

const LEGENDS: MockCard[] = [1, 2, 3].map((index) => ({
  id: `legend-${index}`,
  name: `Legend ${index}`,
  type: 'legend',
  color: 'red',
  cost: null,
  power: 4,
  streetCred: null,
  keywords: ['flip'],
  abilities: [],
}))

/** Deck du joueur A : Units puissantes (coût 1). */
const UNITS_A: MockCard[] = Array.from({ length: 10 }, (_, index) => ({
  id: `unit-a-${index}`,
  name: `Solo A${index}`,
  type: 'unit',
  color: 'green',
  cost: 1,
  power: 9,
  streetCred: null,
  keywords: [],
  abilities: [],
}))

/** Deck du joueur B : Units faibles (coût 1) → A gagne toujours ses combats. */
const UNITS_B: MockCard[] = Array.from({ length: 10 }, (_, index) => ({
  id: `unit-b-${index}`,
  name: `Ganger B${index}`,
  type: 'unit',
  color: 'blue',
  cost: 1,
  power: 2,
  streetCred: null,
  keywords: [],
  abilities: [],
}))

const CATALOG: MockCard[] = [...LEGENDS, ...UNITS_A, ...UNITS_B].map(card)
const DECK_A = [...LEGENDS.map((card) => card.id), ...UNITS_A.map((card) => card.id)]
const DECK_B = [...LEGENDS.map((card) => card.id), ...UNITS_B.map((card) => card.id)]

// --- Utilitaires de test ----------------------------------------------------

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/lobby', name: 'lobby', component: LobbyView },
      { path: '/game/:gameId?', name: 'game', component: GameView },
    ],
  })
}

function buttonWith(wrapper: VueWrapper, label: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(label))
  if (!button) throw new Error(`Bouton introuvable : « ${label} »`)
  return button
}

async function waitFor(predicate: () => boolean, label: string, timeoutMs = 3_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Délai dépassé en attendant : ${label}`)
    await tick(2)
  }
}

function playerOf(state: Record<string, unknown> | null, playerId: string): PlayerState {
  const players = (state?.players ?? []) as PlayerState[]
  const found = players.find((player) => player.playerId === playerId)
  if (!found) throw new Error(`Joueur ${playerId} absent de l'état`)
  return found
}

// --- Suite ------------------------------------------------------------------

describe('Flux complet Lobby → Partie → Jeu', () => {
  let server: MockGameServer
  let pinia: Pinia
  let router: Router
  let wrapper: VueWrapper

  beforeEach(async () => {
    window.localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))

    server = installMockServer(CATALOG)
    __resetGameSocketForTests()

    pinia = createPinia()
    setActivePinia(pinia)
    router = buildRouter()
    await router.push('/lobby')
    await router.isReady()

    wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
    await tick()
  })

  afterEach(() => {
    wrapper.unmount()
    uninstallMockServer()
    __resetGameSocketForTests()
    vi.unstubAllGlobals()
  })

  it('ouvre le canal STOMP avec le pseudo en en-tête CONNECT', async () => {
    const lobby = useLobbyStore()

    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')

    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    const connect = server.received.find((frame) => frame.command === 'CONNECT')
    expect(connect?.headers.pseudo).toBe('Alpha')

    const subscribed = server.received
      .filter((frame) => frame.command === 'SUBSCRIBE')
      .map((frame) => frame.headers.destination)
    // Ordre imposé par la doc §1.3 : files privées d'abord, puis topics publics.
    expect(subscribed).toEqual(
      expect.arrayContaining(['/user/queue/errors', '/user/queue/lobby', '/user/queue/rooms', '/topic/rooms']),
    )
  })

  it('crée un salon, démarre la partie au 2e joueur, puis joue un tour complet', async () => {
    const lobby = useLobbyStore()
    const game = useGameStore()
    const decks = useDeckStore()
    const socket = useGameSocket()

    // --- 1. Connexion + deck personnalisé --------------------------------
    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')
    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    decks.setDeck(DECK_A)
    lobby.useCustomDeck = true

    // --- 2. Création du salon --------------------------------------------
    await buttonWith(wrapper, 'Créer le salon').trigger('click')
    await waitFor(() => lobby.room !== null, 'LOBBY_STATE du salon créé')

    const roomCode = lobby.room?.code ?? ''
    expect(roomCode).toMatch(/^[A-Z2-9]{6}$/)
    expect(wrapper.text()).toContain(roomCode)
    expect(wrapper.text()).toContain('en attente du 2e joueur')

    const createCommand = commandsTo(server, '/app/lobby.create')[0]
    expect(createCommand).toMatchObject({ deckCardIds: DECK_A })

    // --- 3. Le second joueur rejoint → la partie démarre ------------------
    const bravo = new RawClient(server, 'Bravo')
    bravo.connect()
    bravo.subscribe('/user/queue/lobby')
    bravo.subscribe('/user/queue/errors')
    bravo.send('/app/lobby.join', { roomCode, deckCardIds: DECK_B })

    await waitFor(() => lobby.gameId !== null, 'gameId reçu après join')
    await waitFor(() => router.currentRoute.value.name === 'game', 'navigation vers /game')
    await waitFor(() => game.state !== null, 'première STATE de la partie')

    const gameId = lobby.gameId ?? ''
    expect(router.currentRoute.value.params.gameId).toBe(gameId)

    // Le client s'abonne à son topic d'état personnel puis demande un resync.
    const subscribed = server.received
      .filter((frame) => frame.command === 'SUBSCRIBE')
      .map((frame) => frame.headers.destination)
    expect(subscribed).toContain(`/topic/game/${gameId}/Alpha`)
    expect(subscribed).toContain(`/topic/game/${gameId}`)
    // Journal de diagnostic (feature 6.5) : topic public souscrit par le client.
    expect(subscribed).toContain(`/topic/game/${gameId}/log`)
    expect(commandsTo(server, '/app/game/' + gameId + '/resync')).toHaveLength(1)

    // Plateau rendu : 6 cartes en main, main adverse masquée, 3 Legends.
    expect(game.me?.hand).toHaveLength(6)
    expect(game.me?.legendsArea).toHaveLength(3)
    expect(wrapper.findAll('[data-card-side="me"][data-card-zone="HAND"]')).toHaveLength(6)
    expect(wrapper.findAll('[data-card-side="opponent"][data-card-zone="HAND"]')).toHaveLength(6)
    expect(
      wrapper.findAll('[data-card-side="opponent"][data-card-zone="HAND"]')[0]?.attributes('aria-label'),
    ).toBe('Carte masquée')
    expect(game.isMyTurn).toBe(true)
    expect(game.turnNumber).toBe(1)

    // --- 4. Vente : 1 carte de la main → +1 Eddie -------------------------
    const sold = game.me?.hand[0] as CardInstance
    await wrapper.get(`[data-instance-id="${sold.instanceId}"]`).trigger('click')
    expect(game.selectedInstanceId).toBe(sold.instanceId)

    await buttonWith(wrapper, 'Vendre').trigger('click')
    await waitFor(() => (game.me?.eddies ?? 0) === 1, 'Eddie reçu après vente')

    const actions = () => commandsTo(server, `/app/game/${gameId}/action`)
    const sellCommand = actions().find((command) => command.action === 'SELL_CARD')
    expect(sellCommand).toMatchObject({ action: 'SELL_CARD', instanceId: sold.instanceId })
    expect(typeof sellCommand?.clientRequestId).toBe('string')
    expect(game.me?.hand).toHaveLength(5)
    expect(game.me?.hasSoldThisTurn).toBe(true)

    // --- 4 bis. Journal de diagnostic : actions et refus tracés -------------
    await waitFor(
      () => game.debugLog.some((entry) => entry.actionType === 'SELL_CARD' && entry.result === 'SUCCESS'),
      'vente journalisée (SUCCESS)',
    )
    expect(game.debugLog.some((entry) => entry.actionType === 'GAME_START')).toBe(true)

    // Une seconde vente est illégale : le refus est journalisé et diffusé (ILLEGAL).
    const secondCard = game.me?.hand[0] as CardInstance
    socket.sendAction(gameId, { action: 'SELL_CARD', instanceId: secondCard.instanceId })
    await waitFor(
      () => game.debugLog.some((entry) => entry.result === 'ILLEGAL'),
      'refus journalisé (ILLEGAL)',
    )
    const refusal = game.debugLog.find((entry) => entry.result === 'ILLEGAL')
    expect(refusal?.description).toContain('REFUSÉ')
    expect(refusal?.phase).toBeTruthy()
    expect(game.me?.hasSoldThisTurn).toBe(true)

    // --- 5. Pose d'une Unit (coût 1) -------------------------------------
    const played = game.me?.hand.find((card) => card.type === 'unit' && card.cost <= 1) as CardInstance
    await wrapper.get(`[data-instance-id="${played.instanceId}"]`).trigger('click')
    await buttonWith(wrapper, 'Jouer la carte').trigger('click')
    await waitFor(() => (game.me?.field.length ?? 0) === 1, 'Unit posée sur le Field')

    const playCommand = actions().find((command) => command.action === 'PLAY_CARD' && command.instanceId === played.instanceId)
    expect(playCommand).toMatchObject({ action: 'PLAY_CARD', instanceId: played.instanceId })
    expect(game.me?.field[0]?.summoningSickness).toBe(true)
    expect(game.me?.eddies).toBe(0)
    expect(wrapper.findAll('[data-card-side="me"][data-card-zone="FIELD"]')).toHaveLength(1)

    // --- 6. Fin de tour ---------------------------------------------------
    await buttonWith(wrapper, 'Fin de tour').trigger('click')
    await waitFor(() => game.turnNumber === 2, 'passage au tour 2')
    expect(game.activePlayerId).toBe('Bravo')
    expect(game.isMyTurn).toBe(false)
    expect(wrapper.text()).toContain('tour de Bravo')

    // --- 7. Tour de Bravo (client brut) : vente, pose, fin de tour --------
    // Bravo s'abonne à SON topic d'état puis resync (doc §1.3, §5.3).
    bravo.subscribe(`/topic/game/${gameId}/Bravo`)
    bravo.subscribe(`/topic/game/${gameId}`)
    bravo.send(`/app/game/${gameId}/resync`, {})
    await waitFor(() => bravo.lastState() !== null, 'STATE reçue par Bravo')
    const bravoHand = playerOf(bravo.lastState(), 'Bravo').hand
    bravo.send(`/app/game/${gameId}/action`, { action: 'SELL_CARD', instanceId: bravoHand[0]?.instanceId })
    await waitFor(() => playerOf(bravo.lastState(), 'Bravo').eddies === 1, 'Eddie de Bravo')

    const bravoUnit = playerOf(bravo.lastState(), 'Bravo').hand.find((card) => card.type === 'unit') as CardInstance
    bravo.send(`/app/game/${gameId}/action`, { action: 'PLAY_CARD', instanceId: bravoUnit.instanceId })
    await waitFor(() => playerOf(bravo.lastState(), 'Bravo').field.length === 1, 'Unit de Bravo posée')

    bravo.send(`/app/game/${gameId}/action`, { action: 'END_TURN' })
    await waitFor(() => game.turnNumber === 3, 'retour au tour 3 (Alpha)')
    expect(game.isMyTurn).toBe(true)
    expect(game.me?.hand).toHaveLength(5) // 6 − vente − pose + pioche
    expect((game.me?.gigCount ?? 0) + (game.opponent?.gigCount ?? 0)).toBeGreaterThanOrEqual(2)

    // --- 8. Attaque ciblée via l'overlay ----------------------------------
    const attacker = game.me?.field.find((card) => card.type === 'unit') as CardInstance
    expect(game.canAttackWith(attacker)).toBeNull() // mal d'invocation levé au début du tour

    await wrapper.get(`[data-instance-id="${attacker.instanceId}"]`).trigger('click')
    await buttonWith(wrapper, 'Attaquer').trigger('click')
    await waitFor(() => game.targeting !== null, 'mode ciblage actif')
    expect(wrapper.text()).toContain('Choisis une Unit rivale à attaquer')

    const rivalUnit = game.opponent?.field.find((card) => card.type === 'unit') as CardInstance
    expect(game.targeting?.candidates).toContain(rivalUnit.instanceId)

    await wrapper.get(`[data-instance-id="${rivalUnit.instanceId}"]`).trigger('click')
    await waitFor(() => (game.opponent?.field.length ?? 1) === 0, 'Unit rivale vaincue')

    const attackCommand = actions().find((command) => command.action === 'ATTACK')
    expect(attackCommand).toMatchObject({
      action: 'ATTACK',
      instanceId: attacker.instanceId,
      targetInstanceId: rivalUnit.instanceId,
    })
    expect(game.opponent?.trash).toHaveLength(1)
    expect(game.log.some((entry) => entry.type === 'UNIT_DEFEATED')).toBe(true)
    expect(game.targeting).toBeNull()

    // --- 9. Fin de tour : la main passe à Bravo ---------------------------
    await buttonWith(wrapper, 'Fin de tour').trigger('click')
    await waitFor(() => game.turnNumber === 4, 'passage au tour 4')
    expect(game.activePlayerId).toBe('Bravo')
    expect(wrapper.text()).toContain('tour de Bravo')

    // Le journal complet est rendu (doc §7.4).
    expect(game.log.length).toBeGreaterThan(8)
    expect(wrapper.findAll('[data-anim="phase"]')).toHaveLength(1)
  })

  it('refuse une action illégale et affiche le message du serveur', async () => {
    const lobby = useLobbyStore()
    const game = useGameStore()
    const decks = useDeckStore()

    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')
    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    decks.setDeck(DECK_A)
    lobby.useCustomDeck = true
    await buttonWith(wrapper, 'Créer le salon').trigger('click')
    await waitFor(() => lobby.room !== null, 'salon créé')

    const bravo = new RawClient(server, 'Bravo')
    bravo.connect()
    bravo.subscribe('/user/queue/errors')
    bravo.send('/app/lobby.join', { roomCode: lobby.room?.code, deckCardIds: DECK_B })

    await waitFor(() => game.state !== null, 'partie démarrée')
    const gameId = lobby.gameId ?? ''

    // Bravo n'est pas le joueur actif : le serveur refuse et lui répond en privé.
    // Les instanceId de la main adverse sont publics (doc §7.3) : on les réutilise.
    const bravoCard = game.opponent?.hand[0]
    bravo.send(`/app/game/${gameId}/action`, {
      action: 'SELL_CARD',
      instanceId: bravoCard?.instanceId,
      clientRequestId: 'req-illegal',
    })

    await waitFor(() => bravo.errors().length > 0, 'erreur privée ILLEGAL_ACTION')
    const error = bravo.errors()[0]
    expect(error).toMatchObject({ type: 'ERROR', code: 'ILLEGAL_ACTION', clientRequestId: 'req-illegal' })
    expect(String(error?.message)).toContain('pas le tour')

    // Aucune STATE n'est diffusée pour l'action fautive : le tour n'a pas bougé.
    expect(game.turnNumber).toBe(1)
    expect(game.activePlayerId).toBe('Alpha')
    expect(game.state?.sequence).toBe(1)
  })
})
