/**
 * Test de présence : ce que voit le frontend quand un socket se ferme.
 *
 * Le backend traite la déconnexion dans `ws/GamePresenceService` (siège libéré
 * dans un salon en attente, `PLAYER_DISCONNECTED` + minuteur de forfait 120 s
 * pendant une partie). Le serveur simulé reproduit ce comportement ; ces tests
 * vérifient le **contrat côté client** : notifications reçues, minuteur démarré
 * puis annulé, état `connected` des joueurs, partie terminée sur forfait.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import GameView from '@/views/GameView.vue'
import LobbyView from '@/views/LobbyView.vue'
import { __resetGameSocketForTests } from '@/composables/useGameSocket'
import { useDeckStore } from '@/stores/deck'
import { useGameStore } from '@/stores/game'
import { useLobbyStore } from '@/stores/lobby'

import {
  RawClient,
  card,
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

// --- Utilitaires ------------------------------------------------------------

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

/** Client « brut » assis dans le salon et abonné aux files utiles. */
function seatGuest(server: MockGameServer, pseudo: string, roomCode: string): RawClient {
  const guest = new RawClient(server, pseudo)
  guest.connect()
  guest.subscribe('/user/queue/lobby')
  guest.subscribe('/user/queue/errors')
  guest.subscribe('/topic/rooms')
  guest.send('/app/lobby.join', { roomCode, deckCardIds: DECK_B })
  return guest
}

// --- Suite ------------------------------------------------------------------

describe('Présence des joueurs (déconnexion / reconnexion)', () => {
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

  it('libère le siège d’un salon en attente quand le socket se ferme', async () => {
    const host = new RawClient(server, 'Vic')
    host.connect()
    host.subscribe('/user/queue/lobby')
    host.subscribe('/user/queue/errors')
    host.subscribe('/topic/rooms')
    host.send('/app/lobby.create', { roomName: 'Salon fragile' })
    await tick()

    const created = host.messages.find((message) => message.body.type === 'LOBBY_STATE')
    const code = created?.body.code as string
    expect(code).toMatch(/^[A-Z2-9]{6}$/)

    // Rafraîchissement de page : le socket ferme sans `lobby.leave`.
    host.disconnect()
    await tick()
    expect(server.rooms.size).toBe(0)

    // Le même pseudo peut aussitôt recréer un salon (sinon : ALREADY_IN_ROOM).
    const again = new RawClient(server, 'Vic')
    again.connect()
    again.subscribe('/user/queue/lobby')
    again.subscribe('/user/queue/errors')
    again.send('/app/lobby.create', { roomName: 'Salon suivant' })
    await tick()

    expect(again.errors()).toEqual([])
    expect(server.rooms.size).toBe(1)
    expect(again.messages.find((message) => message.body.type === 'LOBBY_STATE')?.body.code).not.toBe(code)
  })

  it('signale la déconnexion puis la reconnexion pendant une partie', async () => {
    const lobby = useLobbyStore()
    const game = useGameStore()
    const decks = useDeckStore()

    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')
    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    decks.setDeck(DECK_A)
    lobby.useCustomDeck = true
    await buttonWith(wrapper, 'Créer le salon').trigger('click')
    await waitFor(() => lobby.room !== null, 'LOBBY_STATE du salon créé')
    const roomCode = lobby.room?.code ?? ''

    const bravo = seatGuest(server, 'Bravo', roomCode)
    await waitFor(() => game.state !== null, 'première STATE de la partie')
    const gameId = lobby.gameId ?? ''
    expect(gameId).not.toBe('')

    bravo.subscribe(`/topic/game/${gameId}`)
    bravo.subscribe(`/topic/game/${gameId}/Bravo`)
    bravo.send(`/app/game/${gameId}/resync`, {})
    await tick()
    expect(game.opponent?.connected).toBe(true)

    // --- Déconnexion de Bravo -------------------------------------------
    bravo.disconnect()
    await waitFor(() => game.disconnection !== null, 'PLAYER_DISCONNECTED')
    expect(game.disconnection).toMatchObject({ playerId: 'Bravo', secondsLeft: 120 })
    expect(game.notice?.type).toBe('PLAYER_DISCONNECTED')
    expect(game.opponent?.connected).toBe(false)

    // --- Reconnexion de Bravo (nouveau socket, même pseudo) --------------
    const back = new RawClient(server, 'Bravo')
    back.connect()
    await waitFor(() => game.disconnection === null, 'PLAYER_RECONNECTED')
    expect(game.notice?.type).toBe('PLAYER_RECONNECTED')
    expect(game.opponent?.connected).toBe(true)

    back.subscribe('/user/queue/errors')
    back.subscribe(`/topic/game/${gameId}`)
    back.subscribe(`/topic/game/${gameId}/Bravo`)
    back.send(`/app/game/${gameId}/resync`, {})
    await tick()
    expect(back.errors()).toEqual([])
    expect(back.lastState()?.yourPlayerId).toBe('Bravo')
    expect((back.lastState()?.players as Array<Record<string, unknown>>)?.[1]).toMatchObject({
      playerId: 'Bravo',
      connected: true,
    })
  })

  it('termine la partie sur forfait quand le joueur absent ne revient pas', async () => {
    const lobby = useLobbyStore()
    const game = useGameStore()
    const decks = useDeckStore()

    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')
    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    decks.setDeck(DECK_A)
    lobby.useCustomDeck = true
    await buttonWith(wrapper, 'Créer le salon').trigger('click')
    await waitFor(() => lobby.room !== null, 'LOBBY_STATE du salon créé')

    const bravo = seatGuest(server, 'Bravo', lobby.room?.code ?? '')
    await waitFor(() => game.state !== null, 'première STATE de la partie')
    const gameId = lobby.gameId ?? ''

    bravo.disconnect()
    await waitFor(() => game.disconnection !== null, 'PLAYER_DISCONNECTED')

    // Le backend attend 120 s ; le serveur simulé expose l'issue directement.
    server.forfeitOfflinePlayer(gameId, 'Bravo')
    await waitFor(() => game.isGameOver, 'GAME_OVER après forfait')

    expect(game.notice?.type).toBe('GAME_OVER')
    expect(game.winnerId).toBe('Alpha')
    expect(game.iWon).toBe(true)
    expect(game.endReason).toContain('Forfait déconnexion de Bravo')
    expect(game.disconnection).toBeNull()
    expect(wrapper.text()).toContain('Victoire')
  })
})
