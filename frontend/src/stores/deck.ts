/**
 * Store du deck builder avec les règles officielles de deckbuilding (Mini-Feature 8).
 *
 * Règles officielles :
 * 1. Exactement 3 Legends uniques (aucun doublon).
 * 2. Main Deck entre 40 et 50 cartes (Units, Programs, Gears).
 * 3. Maximum 3 exemplaires de la même carte dans le Main Deck.
 * 4. Plafond de RAM calculé par couleur depuis les Legends (cartes de couleurs
 *    à plafond > 0 uniquement, coût RAM <= plafond).
 *
 * Mini-Feature 9C — persistance : le deck en cours d'édition reste dans le
 * `localStorage` (brouillon hors ligne), et « Mes Decks » est lu/écrit sur le
 * compte du joueur via `/api/decks` (JWT). Le serveur rejoue les règles
 * ci-dessus **avant** d'écrire : ses refus (`400` + `errors`) alimentent
 * `serverErrors`, affichés en rouge par `DeckBuilderView`.
 *
 * Mini-Feature 10A — UX du deckbuilder :
 * - `resetDeck()` remet l'éditeur à zéro (liste vide + nom par défaut) quand
 *   on crée un nouveau deck, au lieu de conserver les cartes du deck chargé ;
 * - l'import textuel compare `card.name` **et** `card.subtitle`, ce qui
 *   distingue les cartes homonymes (« Adam Smasher: Metal Over Meat » vs
 *   « Adam Smasher: Ender of Legends ») ; une ligne restée ambiguë est signalée.
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import {
  ApiError,
  createDeck,
  deleteDeck,
  fetchCards,
  fetchDecks,
  updateDeck,
  type SavedDeck,
} from '@/services/api'
import { AUTH_CLEARED_EVENT, getAuthToken } from '@/services/authToken'
import { CARD_COLOR_LABELS, type CardColor, type CardType, type GameCard } from '@/types/card'

export type { SavedDeck }

const STORAGE_KEY = 'cyberpunk-tcg.deck.v1'

export const REQUIRED_LEGENDS = 3
export const MAIN_DECK_MIN = 40
export const MAIN_DECK_MAX = 50
export const MAX_COPIES_PER_CARD = 3
export const REQUIRED_NON_LEGENDS = MAIN_DECK_MIN

/** Nom affiché par l'éditeur quand aucun deck sauvegardé n'est chargé (Mini-Feature 10A). */
export const DEFAULT_DECK_NAME = 'Nouveau deck'

export type CatalogState = 'idle' | 'loading' | 'ready' | 'error'

/** État du chargement de « Mes Decks » (decks persistés du compte courant). */
export type SavedDecksState = 'idle' | 'loading' | 'ready' | 'error'

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

/** Une ligne de carte décomposée en nom principal + sous-titre éventuel. */
export interface CardQueryParts {
  name: string
  subtitle: string | null
}

/**
 * Séparateurs reconnus entre le nom et le sous-titre d'une ligne d'import :
 * « Adam Smasher: Metal Over Meat », « Adam Smasher - Ender of Legends »,
 * « Adam Smasher | Metal Over Meat », « Adam Smasher (Metal Over Meat) ».
 *
 * Le tiret **non espacé** n'y figure pas volontairement : il appartient à des
 * noms du catalogue (« T-Bug », « Jacked-In Voodoo Boy »).
 */
const SUBTITLE_SEPARATORS = [':', ' - ', ' — ', ' – ', '|'] as const

/** En dessous de ce score, la correspondance est jugée trop lâche pour être importée. */
const MIN_MATCH_SCORE = 30

/**
 * Découpe une ligne de carte en (nom, sous-titre). Plusieurs lectures sont
 * produites — de la plus littérale à la plus permissive — parce qu'un
 * séparateur peut aussi bien introduire un sous-titre qu'appartenir au nom :
 * c'est le score obtenu sur le catalogue qui arbitre (voir {@link findCardMatches}).
 */
export function cardQueryVariants(query: string): CardQueryParts[] {
  const clean = query.trim().replace(/\s+/g, ' ')
  if (!clean) return []

  const variants: CardQueryParts[] = [{ name: clean, subtitle: null }]

  // « Adam Smasher (Metal Over Meat) » — uniquement si la ligne finit par « ) ».
  const parentheses = /^(.*)\(([^()]*)\)$/.exec(clean)
  if (parentheses?.[1]?.trim() && parentheses[2]?.trim()) {
    variants.push({ name: parentheses[1].trim(), subtitle: parentheses[2].trim() })
  }

  for (const separator of SUBTITLE_SEPARATORS) {
    const index = clean.indexOf(separator)
    if (index <= 0) continue
    const name = clean.slice(0, index).trim()
    const subtitle = clean.slice(index + separator.length).trim()
    if (name && subtitle) variants.push({ name, subtitle })
  }

  return variants
}

/**
 * Score d'une carte pour une lecture donnée de la requête (0 = à rejeter).
 *
 * Le nom **et** le sous-titre sont comparés : deux cartes peuvent porter le
 * même nom principal (« Adam Smasher » existe en « Ender of Legends » et en
 * « Metal Over Meat »), et un sous-titre explicitement demandé qui ne
 * correspond pas doit éliminer la carte plutôt que d'importer la mauvaise
 * version.
 *
 * @param looseName autorise les correspondances approximatives sur le nom.
 *   Elle est refusée à la lecture « ligne entière » dès qu'un séparateur a
 *   proposé une découpe : sinon « Adam Smasher: Sous-titre Inexistant »
 *   retomberait, par simple inclusion, sur le premier Adam Smasher du catalogue.
 */
function scoreCardVariant(
  card: GameCard,
  variant: CardQueryParts,
  normQuery: string,
  looseName: boolean,
): number {
  const normVariantName = normalizeCardQuery(variant.name)
  if (!normVariantName) return 0

  const normName = normalizeCardQuery(card.name)
  const normSubtitle = card.subtitle ? normalizeCardQuery(card.subtitle) : ''
  const normVariantSubtitle = variant.subtitle ? normalizeCardQuery(variant.subtitle) : null

  let score: number
  if (normVariantName === normName) {
    score = 60 // nom principal exact
  } else if (looseName && normName.includes(normVariantName)) {
    score = 45 // la ligne est plus longue que le nom
  } else if (looseName && normVariantName.includes(normName)) {
    score = 30 // la ligne est plus courte (« Adam Smas »)
  } else {
    return 0
  }

  if (normVariantSubtitle) {
    if (!normSubtitle) return 0
    if (normVariantSubtitle === normSubtitle) score += 40
    else if (normSubtitle.includes(normVariantSubtitle) || normVariantSubtitle.includes(normSubtitle)) score += 25
    else return 0 // sous-titre demandé mais différent : ce n'est pas cette carte
  } else if (normSubtitle && normQuery.includes(normSubtitle)) {
    score += 25 // pas de séparateur, mais le sous-titre apparaît dans la ligne
  }

  return score
}

/**
 * Toutes les cartes du catalogue qui répondent à la requête, par score
 * décroissant (à score égal, l'ordre du catalogue fait foi).
 *
 * Une liste de taille > 1 signale une **ligne ambiguë** : plusieurs versions
 * portent le même nom et la ligne ne désigne aucune d'elles par son sous-titre.
 */
export function findCardMatches(query: string, catalog: GameCard[]): GameCard[] {
  const trimmed = query.trim()
  if (!trimmed) return []

  const normQuery = normalizeCardQuery(trimmed)
  const variants = cardQueryVariants(trimmed)
  const rawQuery = trimmed.toLowerCase()
  // La 1re lecture est la ligne recopiée telle quelle ; les suivantes viennent
  // d'une découpe sur un séparateur. Dès qu'une découpe existe, la lecture
  // littérale ne garde que son droit d'exactitude (voir `scoreCardVariant`).
  const splitWasProposed = variants.length > 1

  const scored = catalog
    .map((card, index) => ({
      card,
      index,
      score: Math.max(
        // Les listes exportées ailleurs utilisent souvent l'identifiant
        // (« adam-smasher-metal-over-meat ») : c'est la correspondance reine.
        card.id.toLowerCase() === rawQuery || normalizeCardQuery(card.id) === normQuery ? 120 : 0,
        ...variants.map((variant, variantIndex) =>
          scoreCardVariant(card, variant, normQuery, variantIndex > 0 || !splitWasProposed),
        ),
      ),
    }))
    .filter((entry) => entry.score >= MIN_MATCH_SCORE)
    .sort((left, right) => right.score - left.score || left.index - right.index)

  const best = scored[0]?.score
  if (best === undefined) return []
  return scored.filter((entry) => entry.score === best).map((entry) => entry.card)
}

/**
 * Associe un nom ou extrait textuel de carte à une carte du catalogue.
 * En cas d'égalité (plusieurs versions du même nom sans sous-titre indiqué),
 * la première du catalogue est retenue.
 */
export function findCardInCatalog(query: string, catalog: GameCard[]): GameCard | undefined {
  return findCardMatches(query, catalog)[0]
}

/** Une ligne d'import dont le nom désigne plusieurs versions de carte. */
export interface AmbiguousImportLine {
  /** Ligne telle qu'elle a été écrite dans le texte importé. */
  line: string
  /** Version retenue par défaut (la première du catalogue). */
  chosen: GameCard
  /** Toutes les versions candidates, sous-titres en clair. */
  options: GameCard[]
}

export interface TextImportResult {
  addedCardIds: string[]
  unknownLines: string[]
  /** Lignes où le nom colle à plusieurs versions : le sous-titre manque. */
  ambiguousLines: AmbiguousImportLine[]
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

  // --- Decks sauvegardés sur le compte (Mini-Feature 9C) ---
  /** « Mes Decks » : uniquement ceux du joueur connecté. */
  const savedDecks = ref<SavedDeck[]>([])
  const savedDecksState = ref<SavedDecksState>('idle')
  const savedDecksError = ref<string | null>(null)
  /**
   * Infractions renvoyées par le serveur lors d'une sauvegarde refusée
   * (`400` + `ApiErrorResponse.errors`) : affichées telles quelles, en rouge.
   */
  const serverErrors = ref<string[]>([])
  /** Deck du compte en cours d'édition ; `null` = la sauvegarde créera un nouveau deck. */
  const currentDeckId = ref<number | null>(null)
  const currentDeckName = ref(DEFAULT_DECK_NAME)
  const savingDeck = ref(false)

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

  // --- Decks sauvegardés : dérivés ---
  const currentSavedDeck = computed<SavedDeck | null>(
    () => savedDecks.value.find((saved) => saved.id === currentDeckId.value) ?? null,
  )

  /** Le deck en cours d'édition diffère-t-il de sa version sauvegardée ? */
  const isDirty = computed(() => {
    if (currentDeckId.value === null) return deck.value.length > 0
    const saved = currentSavedDeck.value
    if (!saved) return true
    return saved.cardIds.join('|') !== deck.value.join('|')
  })

  /** Résumé affichable d'un deck sauvegardé (« 43 cartes · 3 Legends »). */
  function describeSavedDeck(saved: SavedDeck): string {
    const legends = saved.cardIds.filter((id) => byId.value.get(id)?.type === 'legend').length
    return `${saved.cardIds.length} cartes · ${legends} Legends`
  }

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
   * Nouveau deck vierge (Mini-Feature 10A) : vide la liste de cartes — le
   * formulaire n'affiche donc plus les cartes du deck précédent — puis
   * détache l'éditeur avec son nom par défaut (voir {@link startNewDeck}).
   */
  function resetDeck(): void {
    deck.value = []
    persist()
    startNewDeck()
  }

  // --- Decks sauvegardés sur le compte (Mini-Feature 9C) ---

  /** Un jeton est-il présent ? (le deck builder est une route authentifiée) */
  function hasToken(): boolean {
    return getAuthToken() !== null
  }

  /**
   * Traduit une erreur d'API en lignes affichables : le serveur renvoie une
   * infraction par ligne (`ApiErrorResponse.errors`), on les montre telles
   * quelles, en rouge, sans reformater.
   */
  function describeError(error: unknown): string[] {
    if (error instanceof ApiError) {
      if (error.details.length > 0) return [...error.details]
      if (error.status === 401 || error.status === 403) {
        return ['Session expirée ou accès refusé : reconnecte-toi pour gérer tes decks.']
      }
      if (error.serverMessage) return [error.serverMessage]
    }
    return [error instanceof Error ? error.message : 'Erreur inconnue']
  }

  /** Charge « Mes Decks » (`GET /api/decks`). Sans jeton, on n'appelle rien. */
  async function loadSavedDecks(): Promise<void> {
    if (!hasToken()) {
      savedDecks.value = []
      savedDecksState.value = 'idle'
      savedDecksError.value = null
      return
    }

    savedDecksState.value = 'loading'
    savedDecksError.value = null
    try {
      savedDecks.value = await fetchDecks()
      savedDecksState.value = 'ready'
    } catch (error) {
      savedDecksState.value = 'error'
      savedDecksError.value = describeError(error)[0] ?? 'Mes Decks indisponibles'
    }
  }

  /**
   * Sauvegarde le deck courant sur le compte du joueur :
   * `POST /api/decks` s'il est nouveau, `PUT /api/decks/{id}` s'il est déjà chargé.
   *
   * <p>Le serveur rejoue les règles officielles (3 Legends uniques, Main Deck
   * 40-50, max 3 copies, plafonds de RAM) <strong>avant</strong> d'écrire :
   * en cas de refus (`400`), {@link serverErrors} porte ses messages et le deck
   * n'est pas créé.</p>
   *
   * @return `true` si le serveur a accepté le deck
   */
  async function saveDeck(name: string): Promise<boolean> {
    serverErrors.value = []
    const trimmedName = name.trim()

    if (!hasToken()) {
      serverErrors.value = ['Connecte-toi pour sauvegarder tes decks.']
      return false
    }
    if (!trimmedName) {
      serverErrors.value = ['Donne un nom au deck avant de le sauvegarder.']
      return false
    }
    if (deck.value.length === 0) {
      serverErrors.value = ['Le deck est vide : ajoute des cartes avant de le sauvegarder.']
      return false
    }

    savingDeck.value = true
    try {
      const payload = { name: trimmedName, cardIds: [...deck.value] }
      const saved =
        currentDeckId.value === null
          ? await createDeck(payload)
          : await updateDeck(currentDeckId.value, payload)
      currentDeckId.value = saved.id
      currentDeckName.value = saved.name
      await loadSavedDecks()
      return true
    } catch (error) {
      serverErrors.value = describeError(error)
      return false
    } finally {
      savingDeck.value = false
    }
  }

  /** Charge un deck sauvegardé dans l'éditeur (il devient le deck courant). */
  function openSavedDeck(saved: SavedDeck): void {
    setDeck(saved.cardIds)
    currentDeckId.value = saved.id
    currentDeckName.value = saved.name
    serverErrors.value = []
  }

  /**
   * Détache l'éditeur de tout deck sauvegardé : la prochaine sauvegarde créera
   * un deck distinct. Le nom revient à {@link DEFAULT_DECK_NAME} (Mini-Feature 10A).
   */
  function startNewDeck(): void {
    currentDeckId.value = null
    currentDeckName.value = DEFAULT_DECK_NAME
    serverErrors.value = []
  }

  /** Supprime un de mes decks (`DELETE /api/decks/{id}`). */
  async function deleteSavedDeck(deckId: number): Promise<boolean> {
    serverErrors.value = []
    try {
      await deleteDeck(deckId)
      savedDecks.value = savedDecks.value.filter((saved) => saved.id !== deckId)
      if (currentDeckId.value === deckId) startNewDeck()
      return true
    } catch (error) {
      serverErrors.value = describeError(error)
      return false
    }
  }

  function clearServerErrors(): void {
    serverErrors.value = []
  }

  /**
   * Importation textuelle d'un deck :
   * - Ignore les lignes de commentaires (// ou #) et les lignes vides.
   * - Parse les lignes au format [Quantité] [Nom de la carte][: sous-titre].
   * - Associe le texte au catalogue en comparant `name` **et** `subtitle`, ce
   *   qui distingue les cartes homonymes (Mini-Feature 10A) ; une ligne dont le
   *   nom colle à plusieurs versions est remontée dans `ambiguousLines`.
   * - Remplit le deck et déclenche la validation.
   */
  function importFromText(text: string): TextImportResult {
    const lines = text.split(/\r?\n/)
    const addedCardIds: string[] = []
    const unknownLines: string[] = []
    const ambiguousLines: AmbiguousImportLine[] = []

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

      const candidates = findCardMatches(cardQuery, cards.value)
      const card = candidates[0]
      if (!card) {
        unknownLines.push(rawLine)
        continue
      }

      // Le nom est porté par plusieurs versions et la ligne ne précise rien :
      // on garde la première (comportement historique) mais on prévient.
      if (candidates.length > 1) {
        ambiguousLines.push({ line: rawLine, chosen: card, options: candidates })
      }

      for (let i = 0; i < quantity; i++) {
        addedCardIds.push(card.id)
      }
    }

    if (addedCardIds.length > 0) {
      setDeck(addedCardIds)
      // L'import remplace la liste : on ne doit jamais écraser le deck
      // sauvegardé qui était chargé dans l'éditeur.
      startNewDeck()
    }

    return {
      addedCardIds,
      unknownLines,
      ambiguousLines,
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

  // Une déconnexion — ou un JWT expiré signalé par l'API — vide « Mes Decks » :
  // aucun deck d'un autre compte ne doit rester affiché.
  if (typeof window !== 'undefined') {
    window.addEventListener(AUTH_CLEARED_EVENT, () => {
      savedDecks.value = []
      savedDecksState.value = 'idle'
      savedDecksError.value = null
      serverErrors.value = []
      currentDeckId.value = null
      currentDeckName.value = DEFAULT_DECK_NAME
    })
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
    // decks sauvegardés sur le compte
    savedDecks,
    savedDecksState,
    savedDecksError,
    serverErrors,
    currentDeckId,
    currentDeckName,
    currentSavedDeck,
    savingDeck,
    isDirty,
    // actions
    loadCatalog,
    add,
    remove,
    clear,
    resetDeck,
    setDeck,
    canAdd,
    buildSampleDeck,
    importFromText,
    loadSavedDecks,
    saveDeck,
    openSavedDeck,
    startNewDeck,
    deleteSavedDeck,
    clearServerErrors,
    describeSavedDeck,
  }
})
