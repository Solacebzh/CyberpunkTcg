/**
 * Persistance des decks (Mini-Feature 9C).
 *
 * Le backend Spring est remplacé par un `fetch` simulé qui applique les mêmes
 * règles officielles (3 Legends uniques, Main Deck 40-50, max 3 copies,
 * plafonds de RAM par couleur) : on vérifie ici le contrat client —
 * `GET/POST/PUT/DELETE /api/decks` avec le JWT, et l'affichage **en rouge**
 * des erreurs de validation renvoyées par le serveur.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import DeckBuilderView from '@/views/DeckBuilderView.vue'
import { storeAuth } from '@/services/authToken'
import { useDeckStore } from '@/stores/deck'
import type { GameCard } from '@/types/card'

const REQUIRED_LEGENDS = 3
const MAIN_DECK_MIN = 40
const MAIN_DECK_MAX = 50
const MAX_COPIES_PER_CARD = 3

function makeCard(partial: Partial<GameCard> & { id: string; name: string }): GameCard {
  const base: GameCard = {
    id: partial.id,
    name: partial.name,
    subtitle: null,
    type: 'unit',
    color: 'red',
    ram: 1,
    cost: 2,
    power: 2,
    streetCred: null,
    tags: [],
    keywords: [],
    text: '',
    abilities: [],
    imageUrl: null,
    setCode: 'SET1',
    collectorNumber: '001',
    rarity: 'common',
  }
  return Object.assign(base, partial)
}

/** 3 Legends rouges (RAM 2 → plafond rouge 6) + 14 Units rouges de RAM ≤ 4. */
const CATALOG: GameCard[] = [
  makeCard({ id: 'legend-a', name: 'Adam Smasher', subtitle: 'Ender of Legends', type: 'legend', ram: 2 }),
  makeCard({ id: 'legend-b', name: 'Royce', subtitle: 'Psycho on the Edge', type: 'legend', ram: 2 }),
  makeCard({ id: 'legend-c', name: 'Johnny Silverhand', subtitle: 'Rocking Renegade', type: 'legend', ram: 2 }),
  ...Array.from({ length: 14 }, (_, index) =>
    makeCard({ id: `unit-${index}`, name: `Unit ${index}`, ram: (index % 4) + 1 }),
  ),
]

const CATALOG_BY_ID = new Map(CATALOG.map((card) => [card.id, card]))

/** Deck légal : 3 Legends + 40 cartes (3 exemplaires max par carte). */
function legalDeck(): string[] {
  const ids = CATALOG.filter((card) => card.type === 'legend').map((card) => card.id)
  const pool = CATALOG.filter((card) => card.type !== 'legend')
  for (let copies = 0; copies < MAX_COPIES_PER_CARD && ids.length < REQUIRED_LEGENDS + MAIN_DECK_MIN; copies++) {
    for (const card of pool) {
      if (ids.length >= REQUIRED_LEGENDS + MAIN_DECK_MIN) break
      ids.push(card.id)
    }
  }
  return ids
}

/** Mêmes règles que `DeckValidator` côté Spring. */
function validateDeck(cardIds: string[]): string[] {
  const errors: string[] = []
  const unknown = cardIds.filter((id) => !CATALOG_BY_ID.has(id))
  if (unknown.length > 0) errors.push(`Carte(s) inconnue(s) dans le deck : ${unknown.join(', ')}`)

  const cards = cardIds.map((id) => CATALOG_BY_ID.get(id)).filter((card): card is GameCard => Boolean(card))
  const legends = cards.filter((card) => card.type === 'legend')
  const main = cards.filter((card) => card.type !== 'legend')

  if (legends.length !== REQUIRED_LEGENDS) {
    errors.push(
      `Le deck doit contenir exactement ${REQUIRED_LEGENDS} Legends (actuellement : ${legends.length})`,
    )
  }
  if (main.length < MAIN_DECK_MIN || main.length > MAIN_DECK_MAX) {
    errors.push(
      `Le Main Deck doit contenir entre ${MAIN_DECK_MIN} et ${MAIN_DECK_MAX} cartes (actuellement : ${main.length})`,
    )
  }

  const copies = new Map<string, number>()
  for (const card of main) copies.set(card.id, (copies.get(card.id) ?? 0) + 1)
  for (const [id, count] of copies) {
    if (count > MAX_COPIES_PER_CARD) {
      errors.push(
        `Maximum ${MAX_COPIES_PER_CARD} exemplaires de la même carte autorisés : '${CATALOG_BY_ID.get(id)?.name}' apparaît ${count} fois`,
      )
    }
  }

  const ceilings: Record<string, number> = { red: 0, green: 0, blue: 0, yellow: 0 }
  for (const legend of legends) ceilings[legend.color] += legend.ram
  for (const card of main) {
    const ceiling = ceilings[card.color] ?? 0
    if (ceiling <= 0) errors.push(`Couleur ${card.color} interdite pour '${card.name}'`)
    else if (card.ram > ceiling) errors.push(`RAM de '${card.name}' au-dessus du plafond`)
  }
  return errors
}

interface RecordedCall {
  method: string
  url: string
  authorization: string | null
  body: { name?: string; cardIds?: string[] } | null
}

/** « Base de données » du serveur simulé + journal des appels. */
interface MockApi {
  calls: RecordedCall[]
  stored: Array<{ id: number; name: string; userId: number; cardIds: string[]; totalCards: number }>
  nextId: number
}

function jsonResponse(status: number, payload?: unknown): Response {
  const body = payload === undefined ? '' : JSON.stringify(payload)
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => payload,
    text: async () => body,
  } as unknown as Response
}

function installApiMock(): MockApi {
  const api: MockApi = { calls: [], stored: [], nextId: 1 }

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = (init?.method ?? 'GET').toUpperCase()
    const headers = new Headers(init?.headers)
    const rawBody = typeof init?.body === 'string' ? (JSON.parse(init.body) as Record<string, unknown>) : null
    api.calls.push({
      method,
      url,
      authorization: headers.get('Authorization'),
      body: rawBody as RecordedCall['body'],
    })

    if (url.endsWith('/api/cards')) return jsonResponse(200, CATALOG)

    if (url.endsWith('/api/decks') && method === 'GET') return jsonResponse(200, api.stored)

    if (url.endsWith('/api/decks') && method === 'POST') {
      const name = String(rawBody?.name ?? '')
      const cardIds = (rawBody?.cardIds ?? []) as string[]
      const errors = validateDeck(cardIds)
      if (errors.length > 0) {
        return jsonResponse(400, {
          status: 400,
          code: 'DECK_INVALID',
          message: `Deck invalide : ${errors.join(' ; ')}`,
          errors,
        })
      }
      const deck = { id: api.nextId++, name, userId: 7, cardIds, totalCards: cardIds.length }
      api.stored.push(deck)
      return jsonResponse(201, { ...deck, createdAt: '2026-09-16T00:00:00Z', updatedAt: '2026-09-16T00:00:00Z' })
    }

    const byId = /\/api\/decks\/(\d+)$/.exec(url)
    if (byId && method === 'PUT') {
      const id = Number(byId[1])
      const deck = api.stored.find((item) => item.id === id)
      if (!deck) return jsonResponse(404, { status: 404, message: 'Deck introuvable' })
      const name = String(rawBody?.name ?? '')
      const cardIds = (rawBody?.cardIds ?? []) as string[]
      const errors = validateDeck(cardIds)
      if (errors.length > 0) {
        return jsonResponse(400, { status: 400, code: 'DECK_INVALID', message: 'Deck invalide', errors })
      }
      deck.name = name
      deck.cardIds = cardIds
      deck.totalCards = cardIds.length
      return jsonResponse(200, { ...deck, createdAt: null, updatedAt: '2026-09-16T01:00:00Z' })
    }

    if (byId && method === 'DELETE') {
      const id = Number(byId[1])
      api.stored = api.stored.filter((item) => item.id !== id)
      return jsonResponse(204)
    }

    return jsonResponse(404, { status: 404, message: `Route inconnue : ${url}` })
  })

  vi.stubGlobal('fetch', fetchMock)
  return api
}

function callsTo(api: MockApi, method: string, suffix = '/api/decks'): RecordedCall[] {
  return api.calls.filter((call) => call.method === method && call.url.endsWith(suffix))
}

describe('Persistance des decks (Mini-Feature 9C)', () => {
  let api: MockApi
  let pinia: Pinia

  beforeEach(() => {
    window.localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
    pinia = createPinia()
    setActivePinia(pinia)
    api = installApiMock()
    storeAuth('test-jwt-token', 'netrunner_v')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  it('liste « Mes Decks » depuis GET /api/decks avec le JWT', async () => {
    api.stored.push({ id: 1, name: 'Deck existant', userId: 7, cardIds: legalDeck(), totalCards: 43 })
    const decks = useDeckStore()

    await decks.loadSavedDecks()

    expect(decks.savedDecksState).toBe('ready')
    expect(decks.savedDecks).toHaveLength(1)
    expect(decks.savedDecks[0]?.name).toBe('Deck existant')
    expect(decks.savedDecks[0]?.cardIds).toHaveLength(43)

    const list = callsTo(api, 'GET')
    expect(list).toHaveLength(1)
    expect(list[0]?.authorization).toBe('Bearer test-jwt-token')
  })

  it("n'appelle pas l'API sans jeton (joueur déconnecté)", async () => {
    window.localStorage.clear()
    const decks = useDeckStore()

    await decks.loadSavedDecks()
    expect(callsTo(api, 'GET')).toHaveLength(0)

    const saved = await decks.saveDeck('Sans compte')
    expect(saved).toBe(false)
    expect(decks.serverErrors.join(' ')).toContain('Connecte-toi')
    expect(callsTo(api, 'POST')).toHaveLength(0)
  })

  it('sauvegarde un deck valide (POST /api/decks) et le retrouve dans la liste', async () => {
    const decks = useDeckStore()
    const ids = legalDeck()
    decks.setDeck(ids)

    const saved = await decks.saveDeck('Netrunner Prime')

    expect(saved).toBe(true)
    expect(decks.serverErrors).toHaveLength(0)

    const posts = callsTo(api, 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0]?.authorization).toBe('Bearer test-jwt-token')
    expect(posts[0]?.body?.name).toBe('Netrunner Prime')
    expect(posts[0]?.body?.cardIds).toEqual(ids)

    expect(decks.currentDeckId).toBe(1)
    expect(decks.currentDeckName).toBe('Netrunner Prime')
    expect(decks.savedDecks.map((deck) => deck.name)).toEqual(['Netrunner Prime'])
    expect(decks.isDirty).toBe(false)
  })

  it('refuse un deck invalide : erreurs du serveur exposées, rien de créé', async () => {
    const decks = useDeckStore()
    const ids = legalDeck()
    const overCopied = ids[REQUIRED_LEGENDS] as string
    ids.push(overCopied) // 4e exemplaire d'une carte du Main Deck
    decks.setDeck(ids)

    const saved = await decks.saveDeck('Trop de copies')

    expect(saved).toBe(false)
    expect(callsTo(api, 'POST')).toHaveLength(1)
    expect(api.stored).toHaveLength(0)
    expect(decks.currentDeckId).toBeNull()
    expect(decks.savedDecks).toHaveLength(0)
    expect(decks.serverErrors.join(' ')).toContain(`Maximum ${MAX_COPIES_PER_CARD} exemplaires`)
    expect(decks.serverErrors.join(' ')).toContain(CATALOG_BY_ID.get(overCopied)?.name as string)
  })

  it('remonte toutes les infractions quand plusieurs règles sont violées', async () => {
    const decks = useDeckStore()
    decks.setDeck(['legend-a', 'legend-b', 'unit-0', 'unit-1']) // 2 Legends, Main Deck de 2

    const saved = await decks.saveDeck('Deck cassé')

    expect(saved).toBe(false)
    expect(decks.serverErrors.some((error) => error.includes('exactement 3 Legends'))).toBe(true)
    expect(decks.serverErrors.some((error) => error.includes('entre 40 et 50 cartes'))).toBe(true)
    expect(api.stored).toHaveLength(0)
  })

  it('met à jour le deck chargé (PUT /api/decks/{id}) au lieu de recréer', async () => {
    const stored = legalDeck()
    api.stored.push({ id: 42, name: 'Version 1', userId: 7, cardIds: stored, totalCards: stored.length })
    const decks = useDeckStore()
    await decks.loadSavedDecks()

    const loaded = decks.savedDecks[0]
    if (!loaded) throw new Error('Deck sauvegardé introuvable')
    decks.openSavedDeck(loaded)
    expect(decks.deck).toEqual(stored)
    expect(decks.currentDeckId).toBe(42)
    expect(decks.isDirty).toBe(false)

    const updated = [...stored, 'unit-13']
    decks.setDeck(updated)
    expect(decks.isDirty).toBe(true)

    const saved = await decks.saveDeck('Version 2')

    expect(saved).toBe(true)
    expect(callsTo(api, 'POST')).toHaveLength(0)
    const puts = callsTo(api, 'PUT', '/api/decks/42')
    expect(puts).toHaveLength(1)
    expect(puts[0]?.body?.name).toBe('Version 2')
    expect(puts[0]?.body?.cardIds).toEqual(updated)
    expect(decks.currentDeckId).toBe(42)
    expect(api.stored[0]?.cardIds).toEqual(updated)
  })

  it('supprime un deck (DELETE /api/decks/{id}) et détache l’éditeur', async () => {
    const ids = legalDeck()
    api.stored.push({ id: 9, name: 'À jeter', userId: 7, cardIds: ids, totalCards: ids.length })
    const decks = useDeckStore()
    await decks.loadSavedDecks()
    decks.openSavedDeck(decks.savedDecks[0]!)

    const removed = await decks.deleteSavedDeck(9)

    expect(removed).toBe(true)
    expect(callsTo(api, 'DELETE', '/api/decks/9')).toHaveLength(1)
    expect(decks.savedDecks).toHaveLength(0)
    expect(decks.currentDeckId).toBeNull()
    expect(api.stored).toHaveLength(0)
  })

  it('exige un nom et un deck non vide sans appeler le serveur', async () => {
    const decks = useDeckStore()
    decks.setDeck(legalDeck())

    expect(await decks.saveDeck('   ')).toBe(false)
    expect(decks.serverErrors.join(' ')).toContain('nom')
    expect(callsTo(api, 'POST')).toHaveLength(0)

    decks.clear()
    expect(await decks.saveDeck('Deck vide')).toBe(false)
    expect(decks.serverErrors.join(' ')).toContain('vide')
    expect(callsTo(api, 'POST')).toHaveLength(0)
  })

  it("ignore une réponse qui n'a pas la forme d'un deck", async () => {
    // Le harnais de `frontendFlow` renvoie le catalogue pour toute URL :
    // le store ne doit pas transformer des cartes en decks fantômes.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse(200, CATALOG)) as unknown as typeof fetch,
    )
    const decks = useDeckStore()

    await decks.loadSavedDecks()

    expect(decks.savedDecksState).toBe('ready')
    expect(decks.savedDecks).toHaveLength(0)
  })
})

describe('DeckBuilderView — affichage des refus du serveur', () => {
  let api: MockApi
  let wrapper: VueWrapper

  beforeEach(async () => {
    window.localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
    const pinia = createPinia()
    setActivePinia(pinia)
    api = installApiMock()
    storeAuth('test-jwt-token', 'netrunner_v')

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', name: 'deck', component: DeckBuilderView }],
    })
    await router.push('/')
    await router.isReady()

    wrapper = mount(DeckBuilderView, { global: { plugins: [pinia, router] } })
    await flush()
  })

  afterEach(() => {
    wrapper.unmount()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  async function flush(): Promise<void> {
    for (let i = 0; i < 6; i += 1) {
      await Promise.resolve()
      await new Promise((resolve) => setTimeout(resolve, 0))
      await wrapper.vm.$nextTick()
    }
  }

  it('affiche la liste « Mes Decks », le champ nom et le bouton de sauvegarde', async () => {
    expect(wrapper.find('[data-testid="my-decks"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Mes Decks')
    expect(wrapper.find('[data-testid="deck-save"]').text()).toContain('Sauvegarder le Deck')
    expect(wrapper.find('[data-testid="deck-name"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="deck-list"]').text()).toContain('Aucun deck sauvegardé')
  })

  it('liste les decks du compte et recharge celui qu’on clique', async () => {
    const ids = legalDeck()
    api.stored.push({ id: 3, name: 'Deck solo', userId: 7, cardIds: ids, totalCards: ids.length })
    const decks = useDeckStore()
    await decks.loadSavedDecks()
    await flush()

    const items = wrapper.findAll('[data-testid="deck-item"]')
    expect(items).toHaveLength(1)
    expect(items[0]?.text()).toContain('Deck solo')
    expect(items[0]?.text()).toContain('43 cartes')
    expect(items[0]?.text()).toContain('3 Legends')

    await items[0]?.find('button').trigger('click')
    await flush()
    expect(decks.deck).toEqual(ids)
    expect(decks.currentDeckId).toBe(3)
    expect((wrapper.find('[data-testid="deck-name"]').element as HTMLInputElement).value).toBe('Deck solo')
  })

  it('affiche en rouge les erreurs de validation renvoyées par le serveur', async () => {
    const decks = useDeckStore()
    const ids = legalDeck()
    ids.push(ids[REQUIRED_LEGENDS] as string) // 4e exemplaire → refus serveur
    decks.setDeck(ids)
    await wrapper.find('[data-testid="deck-name"]').setValue('Deck illégal')
    await flush()

    await wrapper.find('[data-testid="deck-save"]').trigger('click')
    await flush()

    const errors = wrapper.find('[data-testid="deck-server-errors"]')
    expect(errors.exists()).toBe(true)
    expect(errors.text()).toContain('Deck invalide')
    expect(errors.text()).toContain(`Maximum ${MAX_COPIES_PER_CARD} exemplaires`)
    // Rendu en rouge : bordure/fond/texte rouges, comme demandé.
    expect(errors.html()).toContain('text-red-300')
    expect(errors.attributes('role')).toBe('alert')
    expect(api.stored).toHaveLength(0)
  })

  it('sauvegarde un deck valide et le montre dans « Mes Decks »', async () => {
    const decks = useDeckStore()
    decks.setDeck(legalDeck())
    await wrapper.find('[data-testid="deck-name"]').setValue('Netrunner Prime')
    await flush()

    await wrapper.find('[data-testid="deck-save"]').trigger('click')
    await flush()

    expect(callsTo(api, 'POST')).toHaveLength(1)
    expect(wrapper.find('[data-testid="deck-server-errors"]').exists()).toBe(false)
    const items = wrapper.findAll('[data-testid="deck-item"]')
    expect(items).toHaveLength(1)
    expect(items[0]?.text()).toContain('Netrunner Prime')
    expect(wrapper.find('[data-testid="deck-count"]').text()).toBe('1')
  })
})
