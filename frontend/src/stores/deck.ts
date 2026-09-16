/**
 * Store du deck builder avec les règles officielles de deckbuilding (Mini-Feature 8).
 *
 * Règles officielles :
 * 1. Exactement 3 Legends uniques (aucun doublon).
 * 2. Main Deck entre 40 et 50 cartes (Units, Programs, Gears).
 * 3. Maximum 3 exemplaires de la même carte dans le Main Deck.
 * 4. Plafond de RAM calculé par couleur depuis les Legends (cartes de couleurs
 *    à plafond > 0 uniquement, coût RAM <= plafond).
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchCards } from '@/services/api'
import { CARD_COLOR_LABELS, type CardColor, type CardType, type GameCard } from '@/types/card'

const STORAGE_KEY = 'cyberpunk-tcg.deck.v1'

export const REQUIRED_LEGENDS = 3
export const MAIN_DECK_MIN = 40
export const MAIN_DECK_MAX = 50
export const MAX_COPIES_PER_CARD = 3
export const REQUIRED_NON_LEGENDS = MAIN_DECK_MIN

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

/**
 * Normalise une chaîne pour faciliter le matching insensible à la casse,
 * aux accents et à la ponctuation.
 */
export function normalizeCardQuery(str: string): string {
  return str
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Associe un nom ou extrait textuel de carte à une carte du catalogue.
 */
export function findCardInCatalog(query: string, catalog: GameCard[]): GameCard | undefined {
  const trimmed = query.trim()
  if (!trimmed) return undefined
  const normQ = normalizeCardQuery(trimmed)

  // 1. Match direct par ID exact ou normalisé
  let match = catalog.find((c) => c.id.toLowerCase() === trimmed.toLowerCase() || normalizeCardQuery(c.id) === normQ)
  if (match) return match

  // 2. Match complet avec sous-titre (ex: "Adam Smasher - Ender of Legends" ou "Adam Smasher: Ender of Legends")
  match = catalog.find((c) => {
    if (!c.subtitle) return false
    const combo1 = normalizeCardQuery(`${c.name} ${c.subtitle}`)
    const combo2 = normalizeCardQuery(`${c.name} - ${c.subtitle}`)
    const combo3 = normalizeCardQuery(`${c.name}: ${c.subtitle}`)
    return normQ === combo1 || normQ === combo2 || normQ === combo3
  })
  if (match) return match

  // 3. Match exact sur le nom
  const nameMatches = catalog.filter((c) => normalizeCardQuery(c.name) === normQ)
  if (nameMatches.length === 1) return nameMatches[0]
  if (nameMatches.length > 1) {
    // Si plusieurs cartes portent ce nom (ex: Legends avec sous-titres distincts),
    // on vérifie si la requête contient un mot du sous-titre
    const subMatch = nameMatches.find(
      (c) => c.subtitle && normQ.includes(normalizeCardQuery(c.subtitle)),
    )
    return subMatch || nameMatches[0]
  }

  // 4. Match partiel / inclusion si la requête contient le nom
  match = catalog.find((c) => {
    const normName = normalizeCardQuery(c.name)
    if (normQ.includes(normName) || normName.includes(normQ)) return true
    if (c.subtitle) {
      const full = normalizeCardQuery(`${c.name} ${c.subtitle}`)
      if (normQ.includes(full) || full.includes(normQ)) return true
    }
    return false
  })
  return match
}

export interface TextImportResult {
  addedCardIds: string[]
  unknownLines: string[]
  totalAdded: number
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

  // --- Plafonds de RAM par couleur calculés depuis les 3 Legends ---
  const ramCeilings = computed<Record<CardColor, number>>(() => {
    const ceilings: Record<CardColor, number> = { red: 0, green: 0, blue: 0, yellow: 0 }
    for (const id of legendEntries.value) {
      const card = byId.value.get(id)
      if (card && card.color && card.color in ceilings) {
        ceilings[card.color] += (card.ram ?? 0)
      }
    }
    return ceilings
  })

  // --- Détection des Legends en double (ID ou nom identique) ---
  const legendDuplicates = computed(() => {
    const seenIds = new Set<string>()
    const seenNames = new Set<string>()
    const dups: string[] = []
    for (const id of legendEntries.value) {
      const card = byId.value.get(id)
      const name = card?.name?.trim().toLowerCase() ?? id
      if (seenIds.has(id) || seenNames.has(name)) {
        dups.push(card?.name ?? id)
      }
      seenIds.add(id)
      seenNames.add(name)
    }
    return [...new Set(dups)]
  })

  // --- Détection des cartes du Main Deck dépassant 3 copies ---
  const mainOverLimit = computed(() => {
    const counts = new Map<string, number>()
    for (const id of mainEntries.value) {
      counts.set(id, (counts.get(id) ?? 0) + 1)
    }
    const over: { id: string; card?: GameCard; count: number }[] = []
    for (const [id, count] of counts.entries()) {
      if (count > MAX_COPIES_PER_CARD) {
        over.push({ id, card: byId.value.get(id), count })
      }
    }
    return over
  })

  // --- Détection des infractions au plafond de RAM dans le Main Deck ---
  const ramViolations = computed(() => {
    const violations: { id: string; card?: GameCard; reason: string }[] = []
    const ceilings = ramCeilings.value
    for (const id of mainEntries.value) {
      const card = byId.value.get(id)
      if (!card) continue
      const color = card.color
      const ceiling = ceilings[color] ?? 0
      if (ceiling <= 0) {
        const colorLabel = CARD_COLOR_LABELS[color] ?? color
        violations.push({
          id,
          card,
          reason: `Couleur ${colorLabel} interdite (aucun plafond de RAM fourni par les Legends)`,
        })
      } else if ((card.ram ?? 0) > ceiling) {
        const colorLabel = CARD_COLOR_LABELS[color] ?? color
        violations.push({
          id,
          card,
          reason: `RAM ${card.ram} dépasse le plafond ${colorLabel} (${ceiling})`,
        })
      }
    }
    return violations
  })

  // --- Rétrocompatibilité : liste des doublons non autorisés ---
  const duplicates = computed(() => {
    return [
      ...legendDuplicates.value,
      ...mainOverLimit.value.map((o) => o.id),
    ]
  })

  // --- Liste détaillée des problèmes de validation ---
  const problems = computed<string[]>(() => {
    const issues: string[] = []

    // Règle 1 : Exactement 3 Legends, uniques
    if (legendCount.value !== REQUIRED_LEGENDS) {
      issues.push(`Exactement ${REQUIRED_LEGENDS} Legends requises (${legendCount.value} actuellement)`)
    }
    if (legendDuplicates.value.length > 0) {
      issues.push(`Legends en double interdites : ${legendDuplicates.value.join(', ')}`)
    }

    // Règle 2 : Main Deck de 40 à 50 cartes
    if (mainCount.value < MAIN_DECK_MIN || mainCount.value > MAIN_DECK_MAX) {
      issues.push(
        `Main Deck : entre ${MAIN_DECK_MIN} et ${MAIN_DECK_MAX} cartes (${mainCount.value} actuellement)`,
      )
    }

    // Règle 3 : Maximum 3 exemplaires de la même carte
    if (mainOverLimit.value.length > 0) {
      issues.push(
        `Max ${MAX_COPIES_PER_CARD} copies de la même carte : ${mainOverLimit.value.map((o) => `${o.card?.name ?? o.id} (${o.count}x)`).join(', ')}`,
      )
    }

    // Règle 4 : Plafonds de RAM par couleur
    if (ramViolations.value.length > 0) {
      const uniqueReasons = [
        ...new Set(ramViolations.value.map((v) => `${v.card?.name ?? v.id} : ${v.reason}`)),
      ]
      for (const reason of uniqueReasons) {
        issues.push(reason)
      }
    }

    return issues
  })

  const isValid = computed(() => problems.value.length === 0 && deck.value.length > 0)

  const validationStatus = computed(() => {
    if (isValid.value) {
      return 'Deck Valide'
    }
    if (problems.value.length > 0) {
      return `Invalide : ${problems.value[0]}`
    }
    return 'Deck Incomplet'
  })

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
    if (card.type === 'legend') {
      if (legendCount.value >= REQUIRED_LEGENDS) return false
      if (legendEntries.value.includes(cardId)) return false
      const name = card.name?.trim().toLowerCase()
      if (
        name &&
        legendEntries.value.some((id) => byId.value.get(id)?.name?.trim().toLowerCase() === name)
      ) {
        return false
      }
      return true
    }
    const currentCount = deck.value.filter((id) => id === cardId).length
    return currentCount < MAX_COPIES_PER_CARD
  }

  function add(cardId: string): boolean {
    if (!canAdd(cardId)) return false
    deck.value = [...deck.value, cardId]
    persist()
    return true
  }

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

  /**
   * Importation textuelle d'un deck :
   * - Ignore les lignes de commentaires (// ou #) et les lignes vides.
   * - Parse les lignes au format [Quantité] [Nom de la carte].
   * - Associe le nom au catalogue cards.json.
   * - Remplit le deck et déclenche la validation.
   */
  function importFromText(text: string): TextImportResult {
    const lines = text.split(/\r?\n/)
    const addedCardIds: string[] = []
    const unknownLines: string[] = []

    for (const rawLine of lines) {
      const line = rawLine.trim()
      if (!line) continue
      // Ignorer les commentaires commençant par // ou #
      if (line.startsWith('//') || line.startsWith('#')) continue

      // Format : [Quantité] [Nom de la carte] (avec "x" optionnel, ex: "3 The Heist" ou "3x The Heist")
      const match = line.match(/^(\d+)\s*[xX]?\s+(.+)$/)
      let quantity = 1
      let cardQuery = line
      if (match) {
        quantity = Math.max(1, parseInt(match[1], 10))
        cardQuery = match[2].trim()
      }

      const card = findCardInCatalog(cardQuery, cards.value)
      if (card) {
        for (let i = 0; i < quantity; i++) {
          addedCardIds.push(card.id)
        }
      } else {
        unknownLines.push(rawLine)
      }
    }

    if (addedCardIds.length > 0) {
      setDeck(addedCardIds)
    }

    return {
      addedCardIds,
      unknownLines,
      totalAdded: addedCardIds.length,
    }
  }

  /**
   * Construit un deck d'exemple légal selon les règles officielles :
   * 3 Legends uniques d'une même couleur + 40 cartes Main Deck respectant
   * le plafond de RAM et la limite de 3 exemplaires.
   */
  function buildSampleDeck(): void {
    if (cards.value.length === 0) return

    // Sélection de 3 Legends uniques, en favorisant le Rouge pour maximiser le plafond
    const allLegends = cards.value.filter((card) => card.type === 'legend')
    const redLegends = allLegends.filter((l) => l.color === 'red')
    const candidateLegends = redLegends.length >= REQUIRED_LEGENDS ? redLegends : allLegends

    const selectedLegends: GameCard[] = []
    const seenLegendNames = new Set<string>()
    for (const legend of candidateLegends) {
      const norm = legend.name.trim().toLowerCase()
      if (!seenLegendNames.has(norm)) {
        seenLegendNames.add(norm)
        selectedLegends.push(legend)
        if (selectedLegends.length === REQUIRED_LEGENDS) break
      }
    }

    // Calcul des plafonds de RAM des Legends sélectionnées
    const ceilings: Record<string, number> = { red: 0, green: 0, blue: 0, yellow: 0 }
    for (const l of selectedLegends) {
      if (l.color) {
        ceilings[l.color] = (ceilings[l.color] ?? 0) + (l.ram ?? 0)
      }
    }

    // Recherche des cartes éligibles (couleur couverte et RAM <= plafond)
    const eligibleMain = cards.value.filter((card) => {
      if (card.type === 'legend') return false
      const ceil = ceilings[card.color] ?? 0
      return ceil > 0 && (card.ram ?? 0) <= ceil
    })

    const newDeckIds: string[] = [...selectedLegends.map((l) => l.id)]

    // Remplissage jusqu'à 40 cartes avec au maximum 3 exemplaires par carte
    const copiesPerCard = new Map<string, number>()
    let added = 0
    let pass = 0

    while (added < MAIN_DECK_MIN && eligibleMain.length > 0 && pass < MAX_COPIES_PER_CARD) {
      for (const card of eligibleMain) {
        if (added >= MAIN_DECK_MIN) break
        const currentCopies = copiesPerCard.get(card.id) ?? 0
        if (currentCopies < MAX_COPIES_PER_CARD) {
          newDeckIds.push(card.id)
          copiesPerCard.set(card.id, currentCopies + 1)
          added++
        }
      }
      pass++
    }

    // Garde-fou pour petits catalogues (ex: mocks de tests avec peu de cartes)
    if (added < MAIN_DECK_MIN) {
      const anyMain = cards.value.filter((card) => card.type !== 'legend')
      if (anyMain.length > 0) {
        const needed = MAIN_DECK_MIN - added
        for (let i = 0; i < needed; i++) {
          newDeckIds.push(anyMain[i % anyMain.length].id)
        }
      }
    }

    setDeck(newDeckIds)
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
    // deck & validation
    deck,
    groupedDeck,
    legendCount,
    mainCount,
    ramCeilings,
    legendDuplicates,
    mainOverLimit,
    ramViolations,
    duplicates,
    problems,
    isValid,
    validationStatus,
    // actions
    loadCatalog,
    add,
    remove,
    clear,
    setDeck,
    canAdd,
    buildSampleDeck,
    importFromText,
  }
})
