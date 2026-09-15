<script setup lang="ts">
/**
 * Pile de cartes d'une zone du tapis (Deck, Eddies, Trash).
 *
 * Trois usages, un seul composant :
 * - **Deck** : le serveur n'envoie jamais le contenu de la pioche, seulement
 *   `deckCount` → on dessine un dos de carte tant qu'il reste des cartes ;
 * - **Eddies** : cartes vendues, face cachée (le serveur les masque à l'adversaire) ;
 * - **Trash** : cartes vaincues/résolues, face visible, la dernière sur le dessus.
 *
 * Seule la carte du dessus est affichée (c'est elle qui porte l'information) : les
 * couches dessinées derrière matérialisent l'épaisseur de la pile, et le nombre réel
 * est affiché en badge par `PlaymatZone`. Aucun comptage n'est recalculé ici : tout
 * vient du serveur.
 */
import { computed } from 'vue'

import CardComponent from '@/components/CardComponent.vue'
import type { GameCard } from '@/types/card'
import type { CardInstance } from '@/types/game'

const props = withDefaults(
  defineProps<{
    /** Cartes connues de la zone (vide pour la pioche). */
    cards?: CardInstance[]
    /** Nombre total de cartes de la zone (`deckCount` quand `cards` est vide). */
    count: number
    /** `true` = cartes visibles (Trash), `false` = dos de carte (Deck, Eddies). */
    faceUp?: boolean
    definitions?: Map<string, GameCard>
    side?: 'me' | 'opponent'
    /** Texte affiché quand la zone est vide. */
    emptyLabel?: string
    size?: 'xs' | 'sm'
  }>(),
  {
    cards: () => [],
    faceUp: false,
    definitions: () => new Map<string, GameCard>(),
    side: 'me',
    emptyLabel: 'vide',
    size: 'sm',
  },
)

/** Carte du dessus : la dernière entrée de la zone (face visible pour le Trash). */
const topCard = computed<CardInstance | null>(() => props.cards[props.cards.length - 1] ?? null)

/** Épaisseur visible de la pile (3 couches suffisent à suggérer le reste). */
const layers = computed(() => Math.min(Math.max(props.count - 1, 0), 3))

function definitionOf(card: CardInstance): GameCard | null {
  return props.definitions.get(card.cardId) ?? null
}
</script>

<template>
  <div
    class="card-pile"
    :data-side="side"
    :data-face-up="faceUp"
    :data-count="count"
    :data-top="topCard ? topCard.name : null"
    :aria-label="`${count} carte(s) dans la pile${topCard ? `, dessus : ${topCard.name}` : ''}`"
  >
    <p v-if="count === 0" class="card-pile__empty">{{ emptyLabel }}</p>

    <div v-else class="card-pile__top">
      <!-- Épaisseur de la pile (purement décoratif) -->
      <span
        v-for="layer in layers"
        :key="`layer-${layer}`"
        class="card-pile__layer"
        :style="{ '--pile-layer': layer }"
        aria-hidden="true"
      />

      <CardComponent
        v-if="topCard"
        :card="topCard"
        :definition="definitionOf(topCard)"
        :side="side"
        :size="size"
      />
      <div v-else class="card-pile__back cyber-cardback" data-card-back="" aria-hidden="true">
        <span class="font-mono text-[0.55rem] uppercase tracking-[0.3em] text-cyber-cyan">CP</span>
      </div>

      <span v-if="count > 1" class="card-pile__count font-mono" :title="`${count} cartes empilées`">×{{ count }}</span>
    </div>
  </div>
</template>
