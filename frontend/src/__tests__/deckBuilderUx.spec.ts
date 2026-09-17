/**
 * Mini-Feature 10A — UX du deckbuilder.
 *
 * Deux bugs corrigés, tous deux couverts ici :
 *   1. « Créer un nouveau deck » laissait l'éditeur rempli des cartes du deck
 *      précédent → `deckStore.resetDeck()` vide la liste, remet le nom par
 *      défaut et détache l'éditeur du deck sauvegardé.
 *   2. L'import textuel confondait les cartes homonymes (« Adam Smasher »
 *      existe en « Ender of Legends » et en « Metal Over Meat ») → le parseur
 *      compare désormais `card.name` **et** `card.subtitle`.
 *
 * Le backend est simulé par un `fetch` en mémoire, comme dans
 * `deckPersistence.spec.ts` : on teste le contrat du deck builder, pas les règles.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import DeckBuilderView from '@/views/DeckBuilderView.vue'
import { storeAuth } from '@/services/authToken'
import type { SavedDeck } from '@/services/api'
import {
  DEFAULT_DECK_NAME,
  cardQueryVariants,
  findCardInCatalog,
  findCardMatches,
  useDeckStore,
} from '@/stores/deck'
import type { GameCard } from '@/types/card'

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

/**
 * Catalogue volontairement piégé : deux « Adam Smasher », trois « V », un nom
 * avec tiret (« T-Bug ») et un nom avec parenthèse, pour vérifier que le
 * découpage nom / sous-titre ne casse pas sur la ponctuation des noms.
 */
const CATALOG: GameCard[] = [
  makeCard({ id: 'adam-smasher-ender-of-legends', name: 'Adam Smasher', subtitle: 'Ender of Legends', type: 'legend', color: 'red', ram: 2 }),
  makeCard({ id: 'adam-smasher-metal-over-meat', name: 'Adam Smasher', subtitle: 'Metal Over Meat', type: 'legend', color: 'green', ram: 2 }),
  makeCard({ id: 'royce-psycho-on-the-edge', name: 'Royce', subtitle: 'Psycho on the Edge', type: 'legend', color: 'red', ram: 2 }),
  makeCard({ id: 'v-corporate-exile', name: 'V', subtitle: 'Corporate Exile', type: 'legend', color: 'red', ram: 1 }),
  makeCard({ id: 'v-roamer-of-the-badlands', name: 'V', subtitle: 'Roamer of the Badlands', type: 'legend', color: 'green', ram: 1 }),
  makeCard({ id: 'v-streetkid', name: 'V', subtitle: 'Streetkid', type: 'legend', color: 'yellow', ram: 1 }),
  makeCard({ id: 't-bug-amateur-philosopher', name: 'T-Bug', subtitle: 'Amateur Philosopher', type: 'unit', color: 'blue', ram: 1 }),
  makeCard({ id: 'dont-fear-the-reaper', name: "(Don't Fear) The Reaper", type: 'program', color: 'red', ram: 1 }),
  makeCard({ id: 'the-heist', name: 'The Heist', type: 'program', color: 'red', ram: 2 }),
  makeCard({ id: 'unit-0', name: '6th Street Recruits', type: 'unit', color: 'red', ram: 1 }),
  makeCard({ id: 'unit-1', name: 'Corporate Surveillance', type: 'unit', color: 'red', ram: 2 }),
]

/**
 * Catalogue officiel `backend/src/main/resources/data/cards.json`, remonté
 * depuis le répertoire de travail (monorepo) ; `null` s'il est absent.
 */
function loadOfficialCatalog(): GameCard[] | null {
  let directory = process.cwd()
  for (let depth = 0; depth < 5; depth += 1) {
    const candidate = join(directory, 'backend', 'src', 'main', 'resources', 'data', 'cards.json')
    try {
      return JSON.parse(readFileSync(candidate, 'utf8')) as GameCard[]
    } catch {
      directory = dirname(directory) // remonte vers la racine du monorepo
    }
  }
  return null
}

/** Deck du compte, tel que `GET /api/decks` le renvoie. */
function savedDeck(id: number, name: string, cardIds: string[]): SavedDeck {
  return { id, name, userId: 7, cardIds, totalCards: cardIds.length, createdAt: null, updatedAt: null }
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

/** `GET /api/cards` + `GET/POST /api/decks` en mémoire (les règles ne sont pas rejouées ici). */
function installApiMock(readStored: () => SavedDeck[]): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = (init?.method ?? 'GET').toUpperCase()
      if (url.endsWith('/api/cards')) return jsonResponse(200, CATALOG)
      if (url.endsWith('/api/decks') && method === 'GET') return jsonResponse(200, readStored())
      if (url.endsWith('/api/decks') && method === 'POST') {
        const raw =
          typeof init?.body === 'string' ? (JSON.parse(init.body) as { name: string; cardIds: string[] }) : null
        return jsonResponse(201, savedDeck(99, raw?.name ?? '', raw?.cardIds ?? []))
      }
      return jsonResponse(404, { status: 404, message: `Route inconnue : ${url}` })
    }) as unknown as typeof fetch,
  )
}

describe('Import textuel — nom ET sous-titre (Mini-Feature 10A)', () => {
  it('découpe la ligne sur le séparateur de sous-titre, sans casser les noms à tiret', () => {
    expect(cardQueryVariants('Adam Smasher: Metal Over Meat')).toContainEqual({
      name: 'Adam Smasher',
      subtitle: 'Metal Over Meat',
    })
    expect(cardQueryVariants('Adam Smasher - Ender of Legends')).toContainEqual({
      name: 'Adam Smasher',
      subtitle: 'Ender of Legends',
    })
    expect(cardQueryVariants('Adam Smasher (Metal Over Meat)')).toContainEqual({
      name: 'Adam Smasher',
      subtitle: 'Metal Over Meat',
    })
    // « T-Bug » : le tiret fait partie du nom, aucune découpe n'est proposée.
    expect(cardQueryVariants('T-Bug')).toEqual([{ name: 'T-Bug', subtitle: null }])
  })

  it('distingue les deux Adam Smasher selon le sous-titre de la ligne', () => {
    expect(findCardInCatalog('Adam Smasher: Metal Over Meat', CATALOG)?.id).toBe('adam-smasher-metal-over-meat')
    expect(findCardInCatalog('Adam Smasher : Metal Over Meat', CATALOG)?.id).toBe('adam-smasher-metal-over-meat')
    expect(findCardInCatalog('Adam Smasher - Ender of Legends', CATALOG)?.id).toBe('adam-smasher-ender-of-legends')
    expect(findCardInCatalog('Adam Smasher | Ender of Legends', CATALOG)?.id).toBe('adam-smasher-ender-of-legends')
    expect(findCardInCatalog('Adam Smasher (Metal Over Meat)', CATALOG)?.id).toBe('adam-smasher-metal-over-meat')
    // Sans séparateur : le sous-titre écrit en toutes lettres suffit à trancher.
    expect(findCardInCatalog('Adam Smasher Metal Over Meat', CATALOG)?.id).toBe('adam-smasher-metal-over-meat')
    // Les identifiants restent acceptés (listes exportées depuis d'autres outils).
    expect(findCardInCatalog('adam-smasher-ender-of-legends', CATALOG)?.id).toBe('adam-smasher-ender-of-legends')
  })

  it('gère trois versions du même nom (« V ») et la ponctuation des noms', () => {
    expect(findCardInCatalog('V: Streetkid', CATALOG)?.id).toBe('v-streetkid')
    expect(findCardInCatalog('V - Roamer of the Badlands', CATALOG)?.id).toBe('v-roamer-of-the-badlands')
    expect(findCardInCatalog('V', CATALOG)?.id).toBe('v-corporate-exile') // premier du catalogue
    expect(findCardInCatalog('T-Bug: Amateur Philosopher', CATALOG)?.id).toBe('t-bug-amateur-philosopher')
    expect(findCardInCatalog("(Don't Fear) The Reaper", CATALOG)?.id).toBe('dont-fear-the-reaper')
  })

  it('remonte toutes les versions en cas de nom ambigu, aucune si le sous-titre est inconnu', () => {
    expect(findCardMatches('Adam Smasher', CATALOG).map((card) => card.id)).toEqual([
      'adam-smasher-ender-of-legends',
      'adam-smasher-metal-over-meat',
    ])
    expect(findCardMatches('Adam Smasher: Unknown Subtitle', CATALOG)).toHaveLength(0)
    expect(findCardMatches('Carte Inexistante', CATALOG)).toHaveLength(0)
    expect(findCardInCatalog('', CATALOG)).toBeUndefined()
  })
})

describe('importFromText — quantités, ambiguïtés et remplacement du deck', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    window.localStorage.clear()
  })

  it('applique la quantité à la bonne version de la carte', () => {
    const store = useDeckStore()
    store.cards = CATALOG

    const result = store.importFromText(`// Legends
3 Adam Smasher: Metal Over Meat
1 Adam Smasher: Ender of Legends
2 The Heist`)

    expect(result.unknownLines).toHaveLength(0)
    expect(result.ambiguousLines).toHaveLength(0)
    expect(result.totalAdded).toBe(6)
    expect(store.deck.filter((id) => id === 'adam-smasher-metal-over-meat')).toHaveLength(3)
    expect(store.deck.filter((id) => id === 'adam-smasher-ender-of-legends')).toHaveLength(1)
    expect(store.deck.filter((id) => id === 'the-heist')).toHaveLength(2)
  })

  it('signale les lignes ambiguës sans les ignorer', () => {
    const store = useDeckStore()
    store.cards = CATALOG

    const result = store.importFromText('2 Adam Smasher')

    expect(result.totalAdded).toBe(2)
    expect(result.ambiguousLines).toHaveLength(1)
    expect(result.ambiguousLines[0]?.line).toBe('2 Adam Smasher')
    expect(result.ambiguousLines[0]?.options).toHaveLength(2)
    expect(result.ambiguousLines[0]?.chosen.id).toBe('adam-smasher-ender-of-legends')
  })

  it('ignore une version au sous-titre inconnu plutôt que de deviner', () => {
    const store = useDeckStore()
    store.cards = CATALOG

    const result = store.importFromText('1 Adam Smasher: Edition Promoo\n1 The Heist')

    expect(result.totalAdded).toBe(1)
    expect(result.unknownLines).toHaveLength(1)
    expect(result.unknownLines[0]).toContain('Adam Smasher: Edition Promoo')
  })

  it('remplace la liste en cours et détache le deck sauvegardé (pas d écrasement)', () => {
    const store = useDeckStore()
    store.cards = CATALOG
    store.setDeck(['unit-0', 'unit-1'])
    store.savedDecks = [savedDeck(5, 'Deck Rouge', ['unit-0'])]
    store.openSavedDeck(savedDeck(5, 'Deck Rouge', ['unit-0']))
    expect(store.currentDeckId).toBe(5)

    store.importFromText('3 Adam Smasher: Metal Over Meat')

    expect(store.deck).toEqual([
      'adam-smasher-metal-over-meat',
      'adam-smasher-metal-over-meat',
      'adam-smasher-metal-over-meat',
    ])
    expect(store.currentDeckId).toBeNull()
    expect(store.currentDeckName).toBe(DEFAULT_DECK_NAME)
  })
})

describe('resetDeck — un nouveau deck part bien d une liste vide', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    window.localStorage.clear()
  })

  it('vide les cartes, remet le nom par défaut et détache le deck chargé', () => {
    const store = useDeckStore()
    store.cards = CATALOG
    store.openSavedDeck(savedDeck(12, 'Deck Rouge', ['unit-0', 'unit-1']))
    expect(store.deck).toHaveLength(2)

    store.resetDeck()

    expect(store.deck).toHaveLength(0)
    expect(store.groupedDeck).toHaveLength(0)
    expect(store.mainCount).toBe(0)
    expect(store.legendCount).toBe(0)
    expect(store.currentDeckId).toBeNull()
    expect(store.currentDeckName).toBe(DEFAULT_DECK_NAME)
    expect(store.serverErrors).toHaveLength(0)
    // Le brouillon persisté ne doit pas ressusciter les anciennes cartes.
    expect(window.localStorage.getItem('cyberpunk-tcg.deck.v1')).toBe('[]')
  })

  it('« Vider » garde le deck sauvegardé chargé, contrairement à « Nouveau Deck »', () => {
    const store = useDeckStore()
    store.cards = CATALOG
    store.openSavedDeck(savedDeck(12, 'Deck Rouge', ['unit-0']))

    store.clear()

    expect(store.deck).toHaveLength(0)
    expect(store.currentDeckId).toBe(12)
  })
})

describe('Catalogue officiel — aucune collision de nom à l import', () => {
  // Le catalogue servi par le backend (`/api/cards`) est la seule source qui
  // contient réellement deux « Adam Smasher » : on rejoue l'import dessus.
  const officialCatalog = loadOfficialCatalog()

  it.skipIf(!officialCatalog)(
    'résout chaque carte par « nom: sous-titre » sur l ensemble du catalogue',
    () => {
      const catalog = officialCatalog!
      expect(catalog.length).toBeGreaterThan(100)

      const collisions: string[] = []
      for (const card of catalog) {
        const line = card.subtitle ? `${card.name}: ${card.subtitle}` : card.name
        const found = findCardInCatalog(line, catalog)
        if (found?.id !== card.id) collisions.push(`${line} → ${found?.id ?? 'aucune carte'}`)
      }
      expect(collisions).toEqual([])
    },
  )

  it.skipIf(!officialCatalog)(
    'résout les listes bruitées (set, collector number, foil) sur la bonne version',
    () => {
      const catalog = officialCatalog!
      // Ces deux lignes tombaient sur la même carte avant la 10A.
      expect(findCardInCatalog('Adam Smasher: Metal Over Meat', catalog)?.id).toBe('adam-smasher-metal-over-meat')
      expect(findCardInCatalog('Adam Smasher (Metal Over Meat) [WNC 006]', catalog)?.id).toBe(
        'adam-smasher-metal-over-meat',
      )
      expect(findCardInCatalog('Adam Smasher - Ender of Legends (foil)', catalog)?.id).toBe(
        'adam-smasher-ender-of-legends',
      )
    },
  )

  it.skipIf(!officialCatalog)('distingue bien les deux Adam Smasher du catalogue', () => {
    const catalog = officialCatalog!
    expect(findCardInCatalog('3 Adam Smasher: Metal Over Meat'.replace(/^\d+\s/, ''), catalog)?.id).toBe(
      'adam-smasher-metal-over-meat',
    )
    expect(findCardInCatalog('Adam Smasher: Ender of Legends', catalog)?.id).toBe('adam-smasher-ender-of-legends')
    // Trois « V », trois « Goro Takemura » : le sous-titre reste le critère.
    expect(findCardInCatalog('Goro Takemura - Vengeful Bodyguard', catalog)?.name).toBe('Goro Takemura')
    expect(findCardInCatalog('Goro Takemura - Vengeful Bodyguard', catalog)?.subtitle).toBe('Vengeful Bodyguard')
  })
})

describe('DeckBuilderView — actions de la page de création', () => {
  let stored: SavedDeck[]
  let pinia: Pinia
  let wrapper: VueWrapper

  beforeEach(async () => {
    window.localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))
    pinia = createPinia()
    setActivePinia(pinia)
    stored = [savedDeck(3, 'Deck solo', ['adam-smasher-ender-of-legends', 'royce-psycho-on-the-edge', 'the-heist'])]
    installApiMock(() => stored)
    storeAuth('test-jwt-token', 'netrunner_v')

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'deck', component: DeckBuilderView },
        { path: '/lobby', name: 'lobby', component: DeckBuilderView },
      ],
    })
    await router.push('/')
    await router.isReady()
    wrapper = mount(DeckBuilderView, { global: { plugins: [pinia, router] } })
    await flushPromises()
  })

  afterEach(() => {
    wrapper.unmount()
    vi.unstubAllGlobals()
    window.localStorage.clear()
  })

  function decks(): ReturnType<typeof useDeckStore> {
    return useDeckStore()
  }

  async function loadSavedDeck(): Promise<void> {
    const items = wrapper.findAll('[data-testid="deck-item"]')
    await items[0]?.find('button').trigger('click')
    await flushPromises()
  }

  it('« Nouveau Deck » après un deck chargé repart d une liste vide', async () => {
    await loadSavedDeck()
    expect(decks().deck).toHaveLength(3)
    expect(wrapper.text()).toContain('Adam Smasher')

    await wrapper.find('[data-testid="deck-reset"]').trigger('click')
    await flushPromises()

    expect(decks().deck).toHaveLength(0)
    expect(decks().currentDeckId).toBeNull()
    // Plus aucune carte du deck précédent n'est affichée.
    expect(wrapper.find('[data-testid="main-deck-list"]').text()).not.toContain('The Heist')
    expect(wrapper.text()).toContain('Dépose ici tes 3 Legends uniques')
    expect(wrapper.text()).toContain('Dépose ici Units, Programs et Gears')
    // Le nom redevient le nom par défaut.
    expect((wrapper.find('[data-testid="deck-name"]').element as HTMLInputElement).value).toBe(DEFAULT_DECK_NAME)
    // Le compte n'a rien perdu : le deck sauvegardé est intact.
    expect(stored).toHaveLength(1)
    expect(stored[0]?.cardIds).toHaveLength(3)
  })

  it('le « + » de la liste « Mes Decks » crée lui aussi un deck vierge', async () => {
    await loadSavedDeck()
    expect(decks().deck).toHaveLength(3)

    await wrapper.find('[data-testid="deck-add"]').trigger('click')
    await flushPromises()

    expect(decks().deck).toHaveLength(0)
    expect(decks().currentDeckName).toBe(DEFAULT_DECK_NAME)
  })

  it('« Vider le deck » est désactivé quand il n y a rien à vider', async () => {
    const store = decks()
    store.resetDeck()
    await flushPromises()

    expect(wrapper.find('[data-testid="deck-clear"]').attributes('disabled')).toBeDefined()

    store.add('unit-0')
    await flushPromises()
    expect(wrapper.find('[data-testid="deck-clear"]').attributes('disabled')).toBeUndefined()

    await wrapper.find('[data-testid="deck-clear"]').trigger('click')
    await flushPromises()
    expect(store.deck).toHaveLength(0)
  })

  it('l import depuis la modale distingue les versions et affiche les lignes ambiguës', async () => {
    const openImport = wrapper
      .findAll('button')
      .find((button) => button.text().includes('Importer un Deck'))
    await openImport?.trigger('click')
    await flushPromises()

    await wrapper.find('[data-testid="import-textarea"]').setValue(
      '1 Adam Smasher: Metal Over Meat\n1 Adam Smasher: Ender of Legends\n2 Adam Smasher',
    )
    await wrapper.find('[data-testid="import-confirm"]').trigger('click')
    await flushPromises()

    expect(decks().deck).toEqual([
      'adam-smasher-metal-over-meat',
      'adam-smasher-ender-of-legends',
      'adam-smasher-ender-of-legends',
      'adam-smasher-ender-of-legends',
    ])
    // Les lignes ambiguës sont expliquées, pas silencieusement absorbées.
    expect(wrapper.find('[data-testid="import-ambiguous-lines"]').text()).toContain('Précise le sous-titre')
    // Le sous-titre identifie la version retenue dans la liste des Legends.
    expect(wrapper.find('[data-testid="legend-deck-list"]').text()).toContain('Metal Over Meat')
  })
})
