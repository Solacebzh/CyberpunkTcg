/**
 * Modèle de carte côté client — miroir de `docs/schemas/card.schema.json`.
 * Toute évolution du schéma doit être répercutée ici ET dans le schéma JSON.
 */

export type CardType = 'legend' | 'unit' | 'program' | 'gear'

/** Couleurs officielles du Cyberpunk TCG (système « Color Tree »). */
export type CardColor = 'red' | 'green' | 'blue' | 'yellow'

export type CardRarity = 'common' | 'uncommon' | 'rare' | 'legendary'

/** Mots-clés de règles reconnus par le moteur (feature 05). */
export type CardKeyword = 'go_solo' | 'blocker' | 'quick' | 'flip' | 'play' | 'attack'

export interface GameCard {
  /** Identifiant stable, slug unique (ex. `wtnc-a029-saburo-arasaka`) */
  id: string
  name: string
  /** Sous-titre de la carte (ex. « Stubborn Patriarch ») */
  subtitle?: string | null
  type: CardType
  color: CardColor
  /** Valeur de RAM : plafond fixé par les Legends du deck */
  ram: number
  /** Coût en Eddies (absent pour les Legends, mises face cachée) */
  cost?: number | null
  /** Puissance de combat (Units uniquement) */
  power?: number | null
  /** Exigence de Street Cred (nombre de dés Gig nécessaires) */
  streetCred?: number | null
  /** Tags tribaux : ARASAKA, MERCS, CORPO… exploités par les effets */
  tags: string[]
  keywords: CardKeyword[]
  /** Texte de règle de la carte */
  text: string
  flavorText?: string | null
  setCode: string
  collectorNumber: string
  rarity?: CardRarity | null
  imageUrl?: string | null
}

/** Étiquette lisible d'un type de carte (affichage). */
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
