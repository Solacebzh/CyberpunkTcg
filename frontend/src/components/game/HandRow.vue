<script setup lang="ts">
/**
 * Main d'un joueur — rendue **hors tapis**, sous la grille du playmat.
 *
 * Le serveur masque la main adverse (`cardId: 'hidden'`) : les cartes de l'adversaire
 * s'affichent donc en dos de carte, en petit. La main locale reste interactive
 * (sélection, jeu, vente) comme avant.
 *
 * `data-anim="hand" data-side="…"` est le point d'accroche de l'animation de pioche
 * (`useGameAnimations.opponentDraw`) — ne pas le retirer.
 */
import { computed } from 'vue'

import CardComponent from '@/components/CardComponent.vue'
import type { GameCard } from '@/types/card'
import type { CardInstance, PlayerState } from '@/types/game'

const props = withDefaults(
  defineProps<{
    player: PlayerState
    definitions: Map<string, GameCard>
    isMe?: boolean
    selectedInstanceId?: string | null
    targetableIds?: string[]
    actionableIds?: string[]
    interactive?: boolean
  }>(),
  {
    isMe: false,
    selectedInstanceId: null,
    targetableIds: () => [],
    actionableIds: () => [],
    interactive: false,
  },
)

const emit = defineEmits<{ cardClick: [card: CardInstance] }>()

const side = computed<'me' | 'opponent'>(() => (props.isMe ? 'me' : 'opponent'))
const hand = computed(() => props.player.hand)

function definitionOf(card: CardInstance): GameCard | null {
  return props.definitions.get(card.cardId) ?? null
}

function isTargetable(instanceId: string): boolean {
  return props.targetableIds.includes(instanceId)
}

function isActionable(instanceId: string): boolean {
  return props.interactive && props.actionableIds.includes(instanceId)
}
</script>

<template>
  <div class="hand-row" :data-side="side" :data-count="hand.length">
    <p class="zone-label">{{ isMe ? 'Main' : `Main de ${player.name}` }} · {{ hand.length }}</p>

    <div data-anim="hand" :data-side="side" class="cyber-scroll hand-row__cards">
      <p v-if="hand.length === 0" class="font-mono text-[0.6rem] text-slate-600">main vide</p>

      <CardComponent
        v-for="card in hand"
        :key="card.instanceId"
        :card="card"
        :definition="definitionOf(card)"
        :side="side"
        :size="isMe ? 'sm' : 'xs'"
        :selectable="interactive"
        :selected="selectedInstanceId === card.instanceId"
        :targetable="isTargetable(card.instanceId)"
        :dimmed="interactive && !isActionable(card.instanceId)"
        :show-abilities="isMe"
        @click="emit('cardClick', $event)"
      />
    </div>
  </div>
</template>
