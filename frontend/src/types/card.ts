/** Miroir TypeScript de backend/src/main/resources/schema/card-schema.json. */

export type CardType = 'legend' | 'unit' | 'program' | 'gear'
export type CardColor = 'red' | 'green' | 'blue' | 'yellow'
export type CardRarity = 'common' | 'uncommon' | 'rare' | 'epic' | 'secret' | 'iconic' | 'nova' | 'promo'
/**
 * Mots-clés de cartes (miroir de `CardKeyword.java`).
 *
 * `haste` (Mini-Feature 6) est l'alias « jeu rapide » du mal d'invocation ignoré :
 * il se comporte comme `go_solo` (et comme `adrenaline` côté moteur). Aucune carte
 * du catalogue officiel ne le porte encore.
 */
export type CardKeyword = 'go_solo' | 'blocker' | 'quick' | 'flip' | 'play' | 'attack' | 'haste'

export interface GameCard {
  id: string
  name: string
  subtitle: string | null
  type: CardType
  color: CardColor
  ram: number
  cost: number | null
  power: number | null
  streetCred: number | null
  tags: string[]
  keywords: CardKeyword[]
  text: string
  /** Paragraphes d'effet, conservés dans l'ordre d'impression. */
  abilities: string[]
  imageUrl: string | null
  setCode: string
  collectorNumber: string
  rarity: CardRarity | null
}

export const CARD_TYPE_LABELS: Record<CardType, string> = {
  legend: 'Legend',
  unit: 'Unit',
  program: 'Program',
  gear: 'Gear',
}

export const CARD_COLOR_LABELS: Record<CardColor, string> = {
  red: 'Rouge',
  green: 'Vert',
  blue: 'Bleu',
  yellow: 'Jaune',
}
