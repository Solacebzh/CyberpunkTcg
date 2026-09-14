/**
 * Store du deck builder.
 *
 * Le catalogue vient de `GET /api/cards` ; le deck n'est qu'une **liste
 * d'identifiants** envoyée au lobby (`deckCardIds`, doc §4.1). La validation
 * définitive est serveur (`DefaultDeckService`) : les contrôles d'ici sont des
 * garde-fous d'ergonomie, avec exactement les mêmes règles —
 * 3 Legends, ≥ 10 cartes non-Legend, aucun doublon.
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchCards } from '@/services/api'
import type { CardColor, CardType, GameCard } from '@/types/card'

const STORAGE_KEY = 'cyberpunk-tcg.deck.v1'
export const REQUIRED_LEGENDS = 3
export const REQUIRED_NON_LEGENDS = 10

export type CatalogState = 'idle' | 'loading' | 'ready' | 'error'

function loadStoredDeck(): string[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : null
    return Array.isArray(parsed) ? parsed.filter((id): id is string => typeof id === 'string') : []
  } catch {
    return []
  }
}

export const useDeckStore = defineStore('deck', () => {
  // --- Catalogue ---
  const cards = ref<GameCard[]>([])
  const catalogState = ref<CatalogState>('idle')
  const catalogError = ref<string | null>(null)

  // --- Filtres ---
  const search = ref('')
  const typeFilter = ref<CardType | 'all'>('all')
  const colorFilter = ref<CardColor | 'all'>('all')

  // --- Deck ---
  const deck = ref<string[]>(loadStoredDeck())

  const byId = computed(() => new Map(cards.value.map((card) => [card.id, card])))

  const legendEntries = computed(() => deck.value.filter((id) => byId.value.get(id)?.type === 'legend'))
  const mainEntries = computed(() => deck.value.filter((id) => byId.value.get(id)?.type !== 'legend'))

  const legendCount = computed(() => legendEntries.value.length)
  const mainCount = computed(() => mainEntries.value.length)
  const duplicates = computed(() => {
    const seen = new Set<string>()
    const found = new Set<string>()
    for (const id of deck.value) {
      if (seen.has(id)) found.add(id)
      seen.add(id)
    }
    return [...found]
  })

  const problems = computed<string[]>(() => {
    const issues: string[] = []
    if (legendCount.value !== REQUIRED_LEGENDS) {
      issues.push(`Exactement ${REQUIRED_LEGENDS} Legends requises (${legendCount.value} actuellement)`)
    }
    if (mainCount.value < REQUIRED_NON_LEGENDS) {
      issues.push(`Au moins ${REQUIRED_NON_LEGENDS} cartes non-Legend (${mainCount.value} actuellement)`)
    }
    if (duplicates.value.length > 0) {
      issues.push(`Doublons interdits : ${duplicates.value.map((id) => byId.value.get(id)?.name ?? id).join(', ')}`)
    }
    return issues
  })

  const isValid = computed(() => problems.value.length === 0 && deck.value.length > 0)

  const filteredCards = computed(() => {
    const needle = search.value.trim().toLowerCase()
    return cards.value.filter((card) => {
      if (typeFilter.value !== 'all' && card.type !== typeFilter.value) return false
      if (colorFilter.value !== 'all' && card.color !== colorFilter.value) return false
      if (!needle) return true
      const haystack = `${card.name} ${card.subtitle ?? ''} ${card.tags.join(' ')}`.toLowerCase()
      return haystack.includes(needle)
    })
  })

  const groupedDeck = computed(() => {
    const groups = new Map<string, { card: GameCard | undefined; count: number }>()
    for (const id of deck.value) {
      const entry = groups.get(id)
      if (entry) entry.count += 1
      else groups.set(id, { card: byId.value.get(id), count: 1 })
    }
    return [...groups.entries()].map(([id, entry]) => ({ id, ...entry }))
  })

  // --- Actions ---
  async function loadCatalog(force = false): Promise<void> {
    if (catalogState.value === 'ready' && !force) return
    if (catalogState.value === 'loading') return

    catalogState.value = 'loading'
    catalogError.value = null
    try {
      cards.value = await fetchCards()
      catalogState.value = 'ready'
    } catch (error) {
      catalogState.value = 'error'
      catalogError.value = error instanceof Error ? error.message : 'Catalogue indisponible'
    }
  }

  function persist(): void {
    if (typeof window === 'undefined') return
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(deck.value))
  }

  function canAdd(cardId: string): boolean {
    const card = byId.value.get(cardId)
    if (!card) return false
    if (card.type === 'legend' && legendCount.value >= REQUIRED_LEGENDS) return false
    return !deck.value.includes(cardId)
  }

  function add(cardId: string): boolean {
    if (!canAdd(cardId)) return false
    deck.value = [...deck.value, cardId]
    persist()
    return true
  }

  /** Retire la dernière occurrence d'une carte (les doublons sont refusés, mais on reste tolérant). */
  function remove(cardId: string): boolean {
    const index = deck.value.lastIndexOf(cardId)
    if (index < 0) return false
    deck.value = [...deck.value.slice(0, index), ...deck.value.slice(index + 1)]
    persist()
    return true
  }

  function clear(): void {
    deck.value = []
    persist()
  }

  function setDeck(ids: string[]): void {
    deck.value = [...ids]
    persist()
  }

  /** Deck légal construit depuis le catalogue (même esprit que le deck par défaut du serveur). */
  function buildSampleDeck(): void {
    if (cards.value.length === 0) return
    const legends = cards.value.filter((card) => card.type === 'legend').slice(0, REQUIRED_LEGENDS)
    const others = cards.value
      .filter((card) => card.type !== 'legend')
      .sort((a, b) => (a.cost ?? 99) - (b.cost ?? 99))
      .slice(0, REQUIRED_NON_LEGENDS + 2)
    setDeck([...legends, ...others].map((card) => card.id))
  }

  return {
    // catalogue
    cards,
    catalogState,
    catalogError,
    search,
    typeFilter,
    colorFilter,
    filteredCards,
    byId,
    // deck
    deck,
    groupedDeck,
    legendCount,
    mainCount,
    duplicates,
    problems,
    isValid,
    // actions
    loadCatalog,
    add,
    remove,
    clear,
    setDeck,
    canAdd,
    buildSampleDeck,
  }
})
