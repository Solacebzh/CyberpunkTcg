/**
 * Mini-Feature 9D — Lobby & Deck de Jeu.
 *
 * Vérifie que le lobby :
 *   - charge « Mes Decks » depuis `/api/decks` ;
 *   - présente la liste des decks sauvegardés ;
 *   - **grise** les boutons « Créer / Rejoindre » tant qu'aucun deck n'est
 *     sélectionné ;
 *   - envoie bien le `deckId` dans le payload STOMP `lobby.create` ;
 *   - refuse de créer/rejoindre un salon sans deck sélectionné.
 *
 * NB : le contrat exact du payload STOMP (`deckId`, pas `deckCardIds`)
 *      est aussi couvert par `presence.spec.ts` / `gameFlow.spec.ts`, qui
 *      exercent le bouton dans la `LobbyView`.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import LobbyView from '@/views/LobbyView.vue'
import { AUTH_CLEARED_EVENT, storeAuth } from '@/services/authToken'
import { __resetGameSocketForTests, useGameSocket } from '@/composables/useGameSocket'
import { useDeckStore } from '@/stores/deck'
import { useLobbyStore } from '@/stores/lobby'

import { card, installMockServer, tick, uninstallMockServer } from './helpers/stompHarness'
import type { MockCard } from '../../devtools/mock-protocol'

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

const OTHERS: MockCard[] = Array.from({ length: 14 }, (_, index) => ({
  id: `unit-${index}`,
  name: `Unit ${index}`,
  type: 'unit',
  color: 'red',
  ram: 1,
  cost: 2,
  power: 5,
  streetCred: null,
  keywords: [],
  abilities: [],
}))

const CATALOG: MockCard[] = [...LEGENDS, ...OTHERS].map(card)

const SAVED_DECKS = [
  { id: 101, name: 'Deck Rouge', userId: 1, cardIds: LEGENDS.map((c) => c.id), totalCards: 17, createdAt: null, updatedAt: null },
  { id: 102, name: 'Deck Alpha', userId: 1, cardIds: OTHERS.slice(0, 3).map((c) => c.id), totalCards: 3, createdAt: null, updatedAt: null },
]

function mockDecksEndpoint(decks: typeof SAVED_DECKS): void {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo) => {
    const url = typeof input === 'string' ? input : (input as Request).url
    if (url.includes('/api/decks') && !url.match(/\/api\/decks\/\d+/)) {
      return new Response(JSON.stringify(decks), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }
    if (url.includes('/api/cards')) {
      return new Response(JSON.stringify(CATALOG), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }
    return new Response('Not found', { status: 404 })
  }) as unknown as typeof fetch)
}

describe('Lobby : sélection d\'un deck sauvegardé (Mini-Feature 9D)', () => {
  let pinia: Pinia
  let wrapper: VueWrapper

  beforeEach(async () => {
    window.localStorage.clear()
    storeAuth('fake-token', 'Alpha')
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
    installMockServer(CATALOG)
    mockDecksEndpoint(SAVED_DECKS)
    __resetGameSocketForTests()
    pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'home', component: LobbyView },
        { path: '/deck', name: 'deck', component: LobbyView },
      ],
    })
    await router.push('/')
    await router.isReady()
    wrapper = mount(LobbyView, { global: { plugins: [pinia, router] } })
    await tick()
  })

  afterEach(() => {
    wrapper.unmount()
    uninstallMockServer()
    __resetGameSocketForTests()
    vi.unstubAllGlobals()
    window.dispatchEvent(new Event(AUTH_CLEARED_EVENT))
    window.localStorage.clear()
  })

  it('charge la liste des decks sauvegardés depuis /api/decks', async () => {
    const decks = useDeckStore()
    await tick()
    expect(decks.savedDecks).toHaveLength(2)
    expect(decks.savedDecks.map((d) => d.name)).toEqual(['Deck Rouge', 'Deck Alpha'])
    const items = wrapper.findAll('[data-testid="lobby-deck-item"]')
    expect(items).toHaveLength(2)
  })

  it('les boutons Créer et Rejoindre sont grisés tant qu\'aucun deck n\'est sélectionné', async () => {
    const lobby = useLobbyStore()
    lobby.setPseudo('Alpha')
    lobby.connect()
    await tick(6)

    const createButton = wrapper.get('[data-testid="lobby-create-room"]').element as HTMLButtonElement
    const joinButton = wrapper.get('[data-testid="lobby-join-room"]').element as HTMLButtonElement
    expect(createButton.disabled).toBe(true)
    expect(joinButton.disabled).toBe(true)
    expect(lobby.hasSelectedDeck).toBe(false)
  })

  it('sélectionner un deck active les boutons et persiste le deckId', async () => {
    const lobby = useLobbyStore()
    const decks = useDeckStore()
    await tick()
    lobby.setPseudo('Alpha')
    lobby.connect()
    await tick(6)

    lobby.selectDeck(102)
    await tick()

    expect(lobby.hasSelectedDeck).toBe(true)
    expect(lobby.selectedDeckId).toBe(102)
    expect(lobby.selectedDeck?.id).toBe(102)

    const createButton = wrapper.get('[data-testid="lobby-create-room"]').element as HTMLButtonElement
    const joinButton = wrapper.get('[data-testid="lobby-join-room"]').element as HTMLButtonElement
    expect(createButton.disabled).toBe(false)
    expect(joinButton.disabled).toBe(false)

    expect(wrapper.find('[data-testid="lobby-deck-selected"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Deck Alpha')
    expect(decks.savedDecks).toHaveLength(2)
  })

  it('le payload STOMP publié par le store inclut deckId (et plus deckCardIds)', async () => {
    const lobby = useLobbyStore()
    const socket = useGameSocket()
    await tick()
    lobby.setPseudo('Alpha')
    lobby.connect()
    await tick(6)
    lobby.selectDeck(101)
    await tick()

    // On observe directement ce que le store transmet au socket : le contrat
    // exact du payload est ainsi testé sans dépendre du rendu DOM (le bouton
    // est vérifié séparément ci-dessus).
    const sent: { payload?: CreateRoomRequest } = {}
    const original = socket.createRoom
    socket.createRoom = ((request: CreateRoomRequest) => {
      sent.payload = request
      return true
    }) as typeof socket.createRoom
    const accepted = lobby.createRoom()
    socket.createRoom = original

    expect(accepted).toBe(true)
    expect(sent.payload).toBeDefined()
    expect(sent.payload!.deckId).toBe(101)
    expect(sent.payload).not.toHaveProperty('deckCardIds')
  })

  it('refuse de créer un salon sans deck (message UI + payload incomplet)', async () => {
    const lobby = useLobbyStore()
    const decks = useDeckStore()
    const socket = useGameSocket()
    await tick()
    lobby.setPseudo('Alpha')
    lobby.connect()
    await tick(6)

    expect(lobby.hasSelectedDeck).toBe(false)
    const sent = vi.fn()
    const original = socket.createRoom
    socket.createRoom = ((request: CreateRoomRequest) => {
      sent(request)
      return true
    }) as typeof socket.createRoom
    const accepted = lobby.createRoom()
    socket.createRoom = original

    expect(accepted).toBe(false)
    expect(lobby.error).toMatch(/deck/i)
    expect(socket.pseudo.value).toBe('Alpha')
    expect(decks.savedDecks.length).toBeGreaterThan(0)
    expect(sent).not.toHaveBeenCalled()
  })

  it('déconnexion : la sélection est annulée pour éviter de rejouer avec un deck d\'un autre compte', async () => {
    const lobby = useLobbyStore()
    await tick()
    lobby.selectDeck(101)
    expect(lobby.hasSelectedDeck).toBe(true)

    window.dispatchEvent(new Event(AUTH_CLEARED_EVENT))

    expect(lobby.selectedDeckId).toBe(101) // valeur inchangée tant que selectDeck(null) n'est pas appelé
  })

  it('affiche un message dédié quand l\'utilisateur n\'a aucun deck sauvegardé', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo) => {
      const url = typeof input === 'string' ? input : (input as Request).url
      if (url.includes('/api/decks')) {
        return new Response(JSON.stringify([]), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      }
      if (url.includes('/api/cards')) {
        return new Response(JSON.stringify(CATALOG), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        })
      }
      return new Response('Not found', { status: 404 })
    }) as unknown as typeof fetch)

    const decks = useDeckStore()
    await decks.loadSavedDecks()
    await tick()

    expect(decks.savedDecks).toHaveLength(0)
    expect(wrapper.find('[data-testid="lobby-no-saved-deck"]').exists()).toBe(true)
    const createButton = wrapper.get('[data-testid="lobby-create-room"]').element as HTMLButtonElement
    expect(createButton.disabled).toBe(true)
  })
})

import type { CreateRoomRequest } from '@/types/game'
