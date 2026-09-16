import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  findCardInCatalog,
  normalizeCardQuery,
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

describe('DeckBuilder — Règles de validation & Import textuel (Mini-Feature 8)', () => {
  let sampleCatalog: GameCard[]

  beforeEach(() => {
    setActivePinia(createPinia())
    window.localStorage.clear()

    // Création d'un catalogue de test
    const legends: GameCard[] = [
      makeCard({ id: 'leg-red-1', name: 'Adam Smasher', subtitle: 'Ender of Legends', type: 'legend', color: 'red', ram: 2 }),
      makeCard({ id: 'leg-red-2', name: 'Royce', subtitle: 'Psycho on the Edge', type: 'legend', color: 'red', ram: 2 }),
      makeCard({ id: 'leg-green-1', name: 'Goro Takemura', subtitle: 'Hands Unclean', type: 'legend', color: 'green', ram: 1 }),
      makeCard({ id: 'leg-blue-1', name: 'Alt Cunningham', subtitle: 'Soulkiller Architect', type: 'legend', color: 'blue', ram: 2 }),
    ]

    const units: GameCard[] = []
    for (let i = 0; i < 15; i++) {
      units.push(
        makeCard({
          id: `unit-red-${i}`,
          name: `Red Unit ${i}`,
          type: 'unit',
          color: 'red',
          ram: (i % 4) + 1, // RAM 1..4
        }),
      )
    }
    units.push(
      makeCard({ id: 'unit-red-expensive', name: 'Expensive Red', type: 'unit', color: 'red', ram: 5 }),
    )
    units.push(
      makeCard({ id: 'unit-green-1', name: 'Green Guard', type: 'unit', color: 'green', ram: 1 }),
    )
    units.push(
      makeCard({ id: 'unit-blue-1', name: 'Blue Netrunner', type: 'unit', color: 'blue', ram: 1 }),
    )
    units.push(
      makeCard({ id: 'the-heist', name: 'The Heist', type: 'program', color: 'red', ram: 2 }),
    )

    sampleCatalog = [...legends, ...units]
  })

  describe('Algorithme de parsing et recherche de carte', () => {
    it('normalise les chaînes en ignorant la casse, les accents et la ponctuation', () => {
      expect(normalizeCardQuery('Judy Álvarez')).toBe('judy alvarez')
      expect(normalizeCardQuery('Muamar Reyes - El Capitán')).toBe('muamar reyes el capitan')
      expect(normalizeCardQuery('  // Test #1!  ')).toBe('test 1')
    })

    it('trouve une carte par identifiant, nom exact ou nom avec sous-titre', () => {
      expect(findCardInCatalog('the-heist', sampleCatalog)?.id).toBe('the-heist')
      expect(findCardInCatalog('The Heist', sampleCatalog)?.id).toBe('the-heist')
      expect(findCardInCatalog('Adam Smasher - Ender of Legends', sampleCatalog)?.id).toBe('leg-red-1')
      expect(findCardInCatalog('Adam Smasher', sampleCatalog)?.id).toBe('leg-red-1')
      expect(findCardInCatalog('Carte Inexistante', sampleCatalog)).toBeUndefined()
    })
  })

  describe('Fonctionnalité d’importation texte', () => {
    it('ignore les commentaires // et # et les lignes vides', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      const text = `
        // Section Legends
        1 Adam Smasher - Ender of Legends
        # Commentaire dièse
        1 Royce - Psycho on the Edge

        // Section Main Deck
        3 The Heist
      `

      const result = store.importFromText(text)
      expect(result.totalAdded).toBe(5)
      expect(result.unknownLines).toHaveLength(0)
      expect(store.deck).toContain('leg-red-1')
      expect(store.deck).toContain('leg-red-2')
      expect(store.deck.filter((id) => id === 'the-heist')).toHaveLength(3)
    })

    it('signale les lignes contenant des cartes inconnues', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      const text = `
        1 Adam Smasher - Ender of Legends
        3 Carte Fantome Inconnue
        2 The Heist
      `

      const result = store.importFromText(text)
      expect(result.totalAdded).toBe(3) // 1 legend + 2 The Heist
      expect(result.unknownLines).toHaveLength(1)
      expect(result.unknownLines[0]).toContain('Carte Fantome Inconnue')
    })
  })

  describe('Validation en temps réel des 4 règles officielles', () => {
    it('calcule correctement les plafonds de RAM selon les Legends', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      // 2 Legends rouges (2 RAM chacune) + 1 Legend verte (1 RAM)
      store.setDeck(['leg-red-1', 'leg-red-2', 'leg-green-1'])

      expect(store.ramCeilings.red).toBe(4)
      expect(store.ramCeilings.green).toBe(1)
      expect(store.ramCeilings.blue).toBe(0)
      expect(store.ramCeilings.yellow).toBe(0)
    })

    it('valide un deck légal respectant toutes les conditions', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      // 3 Legends uniques (Plafond Rouge: 4, Vert: 1)
      const deck: string[] = ['leg-red-1', 'leg-red-2', 'leg-green-1']

      // 40 cartes Main Deck :
      // 13 cartes rouges x 3 copies = 39 cartes (RAM <= 4)
      for (let i = 0; i < 13; i++) {
        deck.push(`unit-red-${i}`, `unit-red-${i}`, `unit-red-${i}`)
      }
      // 1 carte verte x 1 copie (RAM 1 <= 1)
      deck.push('unit-green-1')

      store.setDeck(deck)

      expect(store.legendCount).toBe(3)
      expect(store.mainCount).toBe(40)
      expect(store.isValid).toBe(true)
      expect(store.problems).toHaveLength(0)
      expect(store.validationStatus).toBe('Deck Valide')
    })

    it('rejette un nombre incorrect de Legends ou un doublon de Legend', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      // Seulement 2 Legends
      store.setDeck(['leg-red-1', 'leg-red-2'])
      expect(store.isValid).toBe(false)
      expect(store.problems.some((p) => p.includes('3 Legends'))).toBe(true)

      // 3 Legends mais un doublon
      store.setDeck(['leg-red-1', 'leg-red-1', 'leg-green-1'])
      expect(store.isValid).toBe(false)
      expect(store.problems.some((p) => p.includes('en double'))).toBe(true)
    })

    it('rejette un Main Deck inférieur à 40 cartes ou supérieur à 50 cartes', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      const deck: string[] = ['leg-red-1', 'leg-red-2', 'leg-green-1']
      // 39 cartes dans le Main Deck
      for (let i = 0; i < 13; i++) {
        deck.push(`unit-red-${i}`, `unit-red-${i}`, `unit-red-${i}`)
      }
      store.setDeck(deck)
      expect(store.mainCount).toBe(39)
      expect(store.isValid).toBe(false)
      expect(store.problems.some((p) => p.includes('entre 40 et 50 cartes'))).toBe(true)

      // Ajout de 12 cartes -> 51 cartes
      for (let i = 0; i < 12; i++) {
        deck.push('unit-green-1') // Même si copies > 3, on vérifie la borne
      }
      store.setDeck(deck)
      expect(store.mainCount).toBe(51)
      expect(store.isValid).toBe(false)
      expect(store.problems.some((p) => p.includes('entre 40 et 50 cartes'))).toBe(true)
    })

    it('rejette plus de 3 copies de la même carte dans le Main Deck', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      const deck: string[] = ['leg-red-1', 'leg-red-2', 'leg-green-1']
      // 4 copies de 'unit-red-0'
      deck.push('unit-red-0', 'unit-red-0', 'unit-red-0', 'unit-red-0')
      // 36 autres cartes
      for (let i = 1; i <= 12; i++) {
        deck.push(`unit-red-${i}`, `unit-red-${i}`, `unit-red-${i}`)
      }
      store.setDeck(deck)

      expect(store.mainCount).toBe(40)
      expect(store.isValid).toBe(false)
      expect(store.problems.some((p) => p.includes('Max 3 copies'))).toBe(true)
      expect(store.validationStatus).toContain('Max 3 copies')
    })

    it('rejette les cartes dépassant le plafond de RAM ou d’une couleur non couverte', () => {
      const store = useDeckStore()
      store.cards = sampleCatalog

      // Plafond Rouge: 4, Vert: 1, Bleu: 0, Jaune: 0
      const deck: string[] = ['leg-red-1', 'leg-red-2', 'leg-green-1']

      // 39 cartes valides
      for (let i = 0; i < 13; i++) {
        deck.push(`unit-red-${i}`, `unit-red-${i}`, `unit-red-${i}`)
      }

      // Carte avec RAM 5 (> 4)
      const deckWithExpensive = [...deck, 'unit-red-expensive']
      store.setDeck(deckWithExpensive)
      expect(store.isValid).toBe(false)
      expect(store.problems.some((p) => p.includes('dépasse le plafond'))).toBe(true)

      // Carte bleue alors que le plafond bleu est 0
      const deckWithBlue = [...deck, 'unit-blue-1']
      store.setDeck(deckWithBlue)
      expect(store.isValid).toBe(false)
      expect(store.problems.some((p) => p.includes('interdite'))).toBe(true)
    })
  })
})
