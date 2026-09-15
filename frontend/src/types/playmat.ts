/**
 * Playmat officiel — zones **de présentation** (mini-feature « Layout exact du Playmat »).
 *
 * ⚠️ Ces identifiants n'existent **que dans le DOM** (`data-zone="…"`) : ils décrivent
 * l'emplacement *imprimé* des zones sur le tapis officiel (docs/OFFICIAL-RULES.md
 * § PLAYMAT AREAS), pas le vocabulaire du serveur. La correspondance avec les zones du
 * protocole (`Zone` de `types/game.ts`) est la suivante :
 *
 * | Zone playmat | Source serveur (immuable) |
 * | --- | --- |
 * | `FIXER` | `PlayerState.fixerDice` — dés Gig pas encore lancés (d20 → d4) |
 * | `GIGS` | `PlayerState.gigs` / `gigCount` / `streetCred` (Friendly & Rival) |
 * | `FIELD` | `PlayerState.field` (+ `attachedTo` pour les Gears équipés) |
 * | `LEGENDS` | `PlayerState.legendsArea` — exactement 3 slots |
 * | `EDDIES` | `PlayerState.eddiesArea` — cartes vendues, face cachée |
 * | `TRASH` | `PlayerState.trash` — cartes vaincues/résolues, face visible |
 * | `DECK` | `PlayerState.deckCount` — le contenu de la pioche reste serveur |
 * | `HAND` | `PlayerState.hand` — hors tapis (rangée dédiée devant le joueur) |
 *
 * Aucun renommage n'a lieu sur le transport : le backend Spring **et** le serveur simulé
 * (`devtools/mock-protocol.ts`) continuent d'émettre `FIELD | HAND | TRASH | EDDIES_AREA |
 * LEGENDS_AREA | DECK`. La traduction se fait ici, à l'affichage uniquement.
 */

/** Zones du tapis officiel + la main (rendue hors tapis). */
export type PlaymatZone = 'FIXER' | 'GIGS' | 'FIELD' | 'LEGENDS' | 'EDDIES' | 'TRASH' | 'DECK' | 'HAND'

/** Libellés affichés dans le bandeau de zone (majuscules officielles). */
export const PLAYMAT_ZONE_LABELS: Record<PlaymatZone, string> = {
  FIXER: 'Fixer',
  GIGS: 'Gigs',
  FIELD: 'Field',
  LEGENDS: 'Legends',
  EDDIES: 'Eddies',
  TRASH: 'Trash',
  DECK: 'Deck',
  HAND: 'Main',
}

/** Rappel de règle affiché au survol (`title`) et lu par les lecteurs d'écran. */
export const PLAYMAT_ZONE_HINTS: Record<PlaymatZone, string> = {
  FIXER: 'Fixer Area — les dés Gig pas encore lancés (le d20 est toujours le dernier)',
  GIGS: 'Gig Area — Gigs alliés (friendly) et Gigs du rival, Street Cred = somme des dés',
  FIELD: 'Field — les Units et les Gears qui leur sont équipés',
  LEGENDS: 'Legends Area — exactement 3 Legends, face cachée au départ',
  EDDIES: 'Eddies Area — chaque carte vendue face cachée vaut 1 Eddie',
  TRASH: 'Trash — cartes défaussées, vaincues ou résolues (face visible)',
  DECK: 'Deck — pioche : une carte par phase de pioche',
  HAND: 'Main — les cartes en main (hors tapis)',
}

/** Slots imprimés dans la zone LEGENDS : la règle en impose exactement 3. */
export const LEGEND_SLOTS = 3

/**
 * Ordre d'affichage de la colonne FIXER : du d20 au d4, comme sur le tapis
 * (le d20 est le dernier dé à quitter la Fixer Area).
 */
export const FIXER_DICE_ORDER = ['d20', 'd12', 'd10', 'd8', 'd6', 'd4'] as const

export type FixerDie = (typeof FIXER_DICE_ORDER)[number]

/** Faces des dés Gig (`GameConstants.GIG_DICE` côté serveur). */
export const DIE_FACES: Record<string, number> = {
  d4: 4,
  d6: 6,
  d8: 8,
  d10: 10,
  d12: 12,
  d20: 20,
}

/** Libellés des deux compteurs de Gigs (haut du tapis). */
export const GIG_LABELS = {
  rival: 'Rival Gigs',
  friendly: 'Friendly Gigs',
} as const

/** Zone du tapis correspondant à une zone du protocole (pour le debug/coloration). */
export const SERVER_ZONE_TO_PLAYMAT: Record<string, PlaymatZone> = {
  DECK: 'DECK',
  HAND: 'HAND',
  FIELD: 'FIELD',
  TRASH: 'TRASH',
  EDDIES_AREA: 'EDDIES',
  LEGENDS_AREA: 'LEGENDS',
}
