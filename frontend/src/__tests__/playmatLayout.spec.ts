/**
 * Contrat de mise en page du **tapis officiel** (mini-feature « Layout exact du Playmat »).
 *
 * Ces tests figent la disposition demandée, zone par zone :
 *
 * ```
 *            RIVAL GIGS            FRIENDLY GIGS          ← tout en haut
 *  ┌────────┬─────────────────────────────────┬────────┐
 *  │        │  FIELD (immense)                │        │
 *  │ FIXER  │                                 │  DECK  │
 *  │ d20→d4 ├───────────────┬─────────────────┼────────┤
 *  │        │  LEGENDS (×3) │  EDDIES         │ TRASH  │
 *  └────────┴───────────────┴─────────────────┴────────┘
 * ```
 *
 * Deux niveaux de vérification :
 * 1. **rendu** — les composants (`PlayerBoard`, `GigsBar`) exposent les zones avec le bon
 *    nombre d'emplacements (3 Legends, 6 dés Fixer) et les hooks d'animation GSAP ;
 * 2. **CSS** — `.playmat-grid` place réellement ces zones via `grid-template-areas`
 *    (c'est cette grille qui reproduit le tapis, pas l'ordre du DOM).
 */
/// <reference types="node" />
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { mount } from '@vue/test-utils'

import GigsBar from '@/components/game/GigsBar.vue'
import PlayerBoard from '@/components/game/PlayerBoard.vue'
import type { GameCard } from '@/types/card'
import type { CardInstance, PlayerState } from '@/types/game'

// --- Fixtures ---------------------------------------------------------------

function makeCard(overrides: Partial<CardInstance>): CardInstance {
  return {
    instanceId: overrides.instanceId ?? 'i-0',
    cardId: overrides.cardId ?? 'card-0',
    name: overrides.name ?? 'Carte',
    type: overrides.type ?? 'unit',
    color: overrides.color ?? 'green',
    cost: overrides.cost ?? 1,
    power: overrides.power ?? 3,
    powerBonus: 0,
    damage: 0,
    keywords: [],
    abilities: [],
    ownerId: overrides.ownerId ?? 'Alpha',
    zone: overrides.zone ?? 'FIELD',
    faceDown: overrides.faceDown ?? false,
    exhausted: false,
    summoningSickness: false,
    ...overrides,
  }
}

const LEGENDS: CardInstance[] = [1, 2, 3].map((index) =>
  makeCard({
    instanceId: `legend-${index}`,
    cardId: `legend-${index}`,
    name: `Legend ${index}`,
    type: 'legend',
    power: 4,
    zone: 'LEGENDS_AREA',
    faceDown: true,
  }),
)

function makePlayer(overrides: Partial<PlayerState> = {}): PlayerState {
  return {
    playerId: 'Alpha',
    name: 'Alpha',
    connected: true,
    deckCount: 34,
    hand: [makeCard({ instanceId: 'hand-1', zone: 'HAND', faceDown: true })],
    field: [
      makeCard({ instanceId: 'unit-1', name: 'Solo A1' }),
      makeCard({ instanceId: 'gear-1', name: 'Gear A1', type: 'gear', attachedTo: 'unit-1', zone: 'FIELD' }),
    ],
    trash: [makeCard({ instanceId: 'trash-1', name: 'Ganger B0', zone: 'TRASH' })],
    eddiesArea: [
      makeCard({ instanceId: 'sold-1', name: 'Carte masquée', zone: 'EDDIES_AREA', faceDown: true }),
      makeCard({ instanceId: 'sold-2', name: 'Carte masquée', zone: 'EDDIES_AREA', faceDown: true }),
    ],
    legendsArea: LEGENDS,
    gigs: [3, 5],
    fixerDice: ['d20', 'd12', 'd10', 'd8'],
    gigCount: 2,
    streetCred: 8,
    eddies: 2,
    availableEddies: 2,
    costDiscount: 0,
    hasSoldThisTurn: false,
    ...overrides,
  }
}

const DEFINITIONS = new Map<string, GameCard>()

const ZONES = ['FIXER', 'FIELD', 'DECK', 'LEGENDS', 'EDDIES', 'TRASH'] as const

// --- 1. Rendu des zones -----------------------------------------------------

describe('Tapis officiel — zones du demi-tapis', () => {
  function mountBoard(isMe = true, player: PlayerState = makePlayer()) {
    return mount(PlayerBoard, {
      props: {
        player,
        isMe,
        isActive: true,
        definitions: DEFINITIONS,
        selectedInstanceId: null,
        targetableIds: [],
        actionableIds: [],
        interactive: isMe,
      },
    })
  }

  it('expose les six zones du tapis, dans les deux camps', () => {
    for (const isMe of [true, false]) {
      const wrapper = mountBoard(isMe)
      const side = isMe ? 'me' : 'opponent'

      for (const zone of ZONES) {
        expect(wrapper.findAll(`[data-zone="${zone}"][data-side="${side}"]`)).toHaveLength(1)
      }
      // Les hooks GSAP restent adossés aux zones (pioche, pose, défaite, Gig…).
      for (const anim of ['field', 'deck', 'legends', 'eddies', 'trash', 'fixer', 'hand']) {
        expect(wrapper.findAll(`[data-anim="${anim}"][data-side="${side}"]`)).toHaveLength(1)
      }
    }
  })

  it('range les 3 Legends dans les 3 slots de la zone LEGENDS', () => {
    const wrapper = mountBoard(true)

    const legends = wrapper.get('[data-zone="LEGENDS"][data-side="me"]')
    const slots = legends.findAll('[data-slot]')
    expect(slots).toHaveLength(3)
    expect(slots.map((slot) => slot.attributes('data-slot'))).toEqual(['1', '2', '3'])
    expect(slots.every((slot) => slot.attributes('data-filled') === 'true')).toBe(true)

    // Chaque Legend est bien rendue dans un slot, dans l'ordre du serveur.
    expect(legends.findAll('[data-card-zone="LEGENDS_AREA"]').map((card) => card.attributes('data-instance-id'))).toEqual([
      'legend-1',
      'legend-2',
      'legend-3',
    ])
  })

  it('dessine un emplacement fantôme quand une Legend manque (jamais masqué)', () => {
    const wrapper = mountBoard(true, makePlayer({ legendsArea: LEGENDS.slice(1) }))
    const slots = wrapper.get('[data-zone="LEGENDS"][data-side="me"]').findAll('[data-slot]')

    expect(slots).toHaveLength(3)
    expect(slots.filter((slot) => slot.attributes('data-filled') === 'false')).toHaveLength(1)
  })

  it('place les 6 dés Gig dans la colonne FIXER, du d20 au d4', () => {
    const wrapper = mountBoard(true)
    const dice = wrapper.get('[data-zone="FIXER"][data-side="me"]').findAll('[data-fixer-die]')

    expect(dice.map((die) => die.attributes('data-fixer-die'))).toEqual(['d20', 'd12', 'd10', 'd8', 'd6', 'd4'])
    // `fixerDice` = dés restants : ici les 4 plus gros, les deux derniers ont été lancés.
    expect(dice.filter((die) => die.attributes('data-ready') === 'true').map((die) => die.attributes('data-fixer-die'))).toEqual([
      'd20',
      'd12',
      'd10',
      'd8',
    ])
  })

  it('rend les cartes vendues face cachée dans EDDIES et la défausse dans TRASH', () => {
    const wrapper = mountBoard(true)

    const eddies = wrapper.get('[data-zone="EDDIES"][data-side="me"]')
    expect(eddies.get('[data-count="2"]')).toBeTruthy()
    // Mini-Feature 4 (R4) : chaque carte vendue est rendue individuellement
    // (face cachée) pour pouvoir être sélectionnée puis inclinée.
    const eddiesCards = eddies.findAll('[data-card-zone="EDDIES_AREA"]')
    expect(eddiesCards).toHaveLength(2)
    expect(eddiesCards.map((card) => card.attributes('data-instance-id'))).toEqual(['sold-1', 'sold-2'])

    const trash = wrapper.get('[data-zone="TRASH"][data-side="me"]')
    expect(trash.findAll('[data-card-zone="TRASH"]')).toHaveLength(1)

    // La pioche n'expose pas de carte (contenu connu du serveur seul) : dos de carte + compteur.
    const deck = wrapper.get('[data-zone="DECK"][data-side="me"]')
    expect(deck.findAll('[data-card-back]')).toHaveLength(1)
    expect(deck.text()).toContain('34')
  })
})

// --- 2. Bandeau du haut : Rival Gigs / Friendly Gigs ------------------------

describe('Tapis officiel — compteurs de Gigs tout en haut', () => {
  it('affiche RIVAL GIGS et FRIENDLY GIGS avec les dés et le Street Cred', () => {
    const wrapper = mount(GigsBar, {
      props: {
        me: makePlayer({ playerId: 'Alpha', name: 'Alpha' }),
        opponent: makePlayer({ playerId: 'Bravo', name: 'Bravo', gigs: [6], gigCount: 1, streetCred: 6, fixerDice: ['d20'] }),
        activePlayerId: 'Bravo',
      },
    })

    const gigs = wrapper.get('[data-zone="GIGS"]')
    expect(gigs.text()).toContain('Rival Gigs')
    expect(gigs.text()).toContain('Friendly Gigs')

    // Un compteur par camp, avec les hooks d'animation (pulsation des dés).
    expect(wrapper.findAll('[data-anim="gigs"][data-side="me"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-anim="gigs"][data-side="opponent"]')).toHaveLength(1)
    expect(gigs.findAll('[data-anim="gig-pip"]').length).toBeGreaterThan(0)

    // Valeurs des dés + Street Cred, et camp actif mis en avant.
    expect(gigs.text()).toContain('SC 8')
    expect(gigs.text()).toContain('SC 6')
    expect(wrapper.get('[data-active="true"]').text()).toContain('Bravo')
  })
})

// --- 3. La grille CSS est bien celle du tapis officiel ----------------------

describe('Tapis officiel — grille CSS', () => {
  // La disposition vit dans la feuille de style globale (`assets/main.css`) : on la lit
  // telle quelle pour vérifier la grille réellement servie au navigateur.
  const css = readFileSync(resolve(process.cwd(), 'src/assets/main.css'), 'utf8')

  function block(selector: string): string {
    const start = css.indexOf(`${selector} {`)
    expect(start, `sélecteur ${selector} introuvable dans main.css`).toBeGreaterThan(-1)
    const end = css.indexOf('}', start)
    return css.slice(start, end)
  }

  it('positionne les zones sur les 2 × 4 cellules du tapis', () => {
    const grid = block('.playmat-grid')

    // Deux lignes, quatre colonnes : la ligne du bas = Legends | Eddies | Trash,
    // la colonne de droite = Deck (haut) puis Trash (bas), la gauche = Fixer.
    expect(grid).toContain('grid-template-areas')
    expect(grid.replace(/\s+/g, ' ')).toContain("'fixer field field deck'")
    expect(grid.replace(/\s+/g, ' ')).toContain("'fixer legends eddies trash'")
    expect(grid).toContain('grid-template-columns')
    expect(grid).toContain('grid-template-rows')
  })

  it('associe chaque zone à sa cellule (`grid-area`)', () => {
    for (const [zone, area] of [
      ['FIXER', 'fixer'],
      ['FIELD', 'field'],
      ['DECK', 'deck'],
      ['LEGENDS', 'legends'],
      ['EDDIES', 'eddies'],
      ['TRASH', 'trash'],
    ]) {
      expect(block(`.playmat-zone[data-zone='${zone}']`)).toContain(`grid-area: ${area}`)
    }
  })

  it('empile le tapis en une colonne sur écran étroit', () => {
    const narrow = css.slice(css.indexOf('@media (max-width: 1023px)'))
    expect(narrow.replace(/\s+/g, ' ')).toContain("'field' 'legends' 'eddies' 'trash' 'deck' 'fixer'")
  })
})
