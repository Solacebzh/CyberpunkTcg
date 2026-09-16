/**
 * Tests ciblés : deck builder (catalogue REST + glisser-déposer) et
 * reconnexion automatique du canal STOMP.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import DeckBuilderView from '@/views/DeckBuilderView.vue'
import LobbyView from '@/views/LobbyView.vue'
import { __resetGameSocketForTests, useGameSocket } from '@/composables/useGameSocket'
import { useDeckStore } from '@/stores/deck'
import { useLobbyStore } from '@/stores/lobby'

import { card, createdSockets, installMockServer, tick, uninstallMockServer } from './helpers/stompHarness'
import type { MockCard, MockGameServer } from '../../devtools/mock-protocol'

const LEGENDS: MockCard[] = [1, 2, 3, 4].map((index) => ({
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
  type: index % 3 === 0 ? 'gear' : index % 3 === 1 ? 'program' : 'unit',
  color: 'red',
  ram: 1,
  cost: 2,
  power: 5,
  streetCred: null,
  keywords: [],
  abilities: [],
}))

const CATALOG: MockCard[] = [...LEGENDS, ...OTHERS].map(card)

describe('Deck builder', () => {
  let server: MockGameServer
  let pinia: Pinia
  let wrapper: VueWrapper

  beforeEach(async () => {
    window.localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
    server = installMockServer(CATALOG)
    __resetGameSocketForTests()
    pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', name: 'home', component: DeckBuilderView }] })
    await router.push('/')
    await router.isReady()
    wrapper = mount(DeckBuilderView, { global: { plugins: [pinia, router] } })
    await tick()
  })

  afterEach(() => {
    wrapper.unmount()
    uninstallMockServer()
    __resetGameSocketForTests()
    vi.unstubAllGlobals()
  })

  it('charge le catalogue depuis /api/cards et construit un deck valide', async () => {
    const decks = useDeckStore()

    await tick()
    expect(decks.catalogState).toBe('ready')
    expect(decks.cards).toHaveLength(CATALOG.length)
    expect(wrapper.text()).toContain(`${CATALOG.length} carte(s)`)

    // Clic = ajout (le glisser-déposer passe par la même fonction `add`).
    const legend = LEGENDS[0] as MockCard
    decks.add(legend.id)
    decks.add(legend.id) // doublon refusé
    expect(decks.deck).toEqual([legend.id])
    expect(decks.problems.join(' ')).toContain('3 Legends')

    // Deck d'exemple : 3 Legends + 40 cartes non-Legend (règles officielles).
    decks.buildSampleDeck()
    await tick()
    expect(decks.legendCount).toBe(3)
    expect(decks.mainCount).toBe(40)
    expect(decks.isValid).toBe(true)
    expect(wrapper.text()).toContain('Deck Valide')

    // Persistance : le deck est relu depuis le localStorage.
    expect(window.localStorage.getItem('cyberpunk-tcg.deck.v1')).toContain('legend-')

    // Retrait d'une carte.
    const removed = decks.deck[0] as string
    decks.remove(removed)
    expect(decks.deck).not.toContain(removed)
    expect(server.received.length).toBe(0) // aucune frame STOMP : le deck builder est hors ligne
  })

  it('accepte le glisser-déposer depuis le catalogue vers le deck', async () => {
    const decks = useDeckStore()
    await tick()

    const zone = wrapper.findAll('aside section')[1]
    if (!zone) throw new Error('Zone de dépôt du deck introuvable')

    await zone.trigger('drop', {
      dataTransfer: { getData: () => 'unit-2', setData: () => undefined, effectAllowed: 'copy' },
    })
    await tick()

    expect(decks.deck).toEqual(['unit-2'])
    expect(wrapper.text()).toContain('Deck · 1')
  })
})

describe('Canal STOMP', () => {
  let pinia: Pinia
  let wrapper: VueWrapper

  beforeEach(async () => {
    window.localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
    installMockServer(CATALOG)
    __resetGameSocketForTests()
    pinia = createPinia()
    setActivePinia(pinia)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/lobby', name: 'lobby', component: LobbyView },
        { path: '/game/:gameId?', name: 'game', component: LobbyView },
      ],
    })
    await router.push('/lobby')
    await router.isReady()
    wrapper = mount(LobbyView, { global: { plugins: [pinia, router] } })
    await tick()
  })

  afterEach(() => {
    wrapper.unmount()
    uninstallMockServer()
    __resetGameSocketForTests()
    vi.unstubAllGlobals()
  })

  it('bascule en reconnexion automatique quand le transport tombe', async () => {
    const lobby = useLobbyStore()
    const socket = useGameSocket()

    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    lobby.connect()
    await tick(6)
    expect(socket.status.value).toBe('connected')

    const transport = createdSockets[0]
    if (!transport) throw new Error('Aucune WebSocket créée')
    transport.forceClose()
    await tick(4)

    // stompjs retente tout seul ; le client affiche le back-off (2 s → 5 s).
    expect(socket.status.value).toBe('connecting')
    expect(socket.statusDetail.value ?? '').toContain('reconnexion')
  })
})
