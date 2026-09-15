<script setup lang="ts">
/**
 * LEGENDS — bas-centre-gauche du tapis : **exactement 3 slots** distincts.
 *
 * Les Legends d'un deck sont toujours au nombre de 3 (règle de construction) et
 * occupent chacune un emplacement imprimé : la carte d'index `i` va dans le slot `i+1`,
 * ce qui garde un emplacement stable quand une Legend est retournée (FLIP) ou dépensée.
 * Un slot vide est dessiné comme un emplacement fantôme, jamais masqué.
 */
import { computed } from 'vue'

import CardComponent from '@/components/CardComponent.vue'
import type { GameCard } from '@/types/card'
import { LEGEND_SLOTS } from '@/types/playmat'
import type { CardInstance } from '@/types/game'

const props = withDefaults(
  defineProps<{
    legends: CardInstance[]
    definitions: Map<string, GameCard>
    side?: 'me' | 'opponent'
    selectedInstanceId?: string | null
    targetableIds?: string[]
    actionableIds?: string[]
    interactive?: boolean
  }>(),
  {
    side: 'me',
    selectedInstanceId: null,
    targetableIds: () => [],
    actionableIds: () => [],
    interactive: false,
  },
)

const emit = defineEmits<{ cardClick: [card: CardInstance] }>()

/** Les 3 emplacements imprimés, remplis ou non (index → `data-slot`). */
const slots = computed(() =>
  Array.from({ length: LEGEND_SLOTS }, (_, index) => ({
    slot: index + 1,
    card: props.legends[index] ?? null,
  })),
)

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
  <div class="legends-area" :data-side="side" :data-slots="LEGEND_SLOTS">
    <div
      v-for="entry in slots"
      :key="entry.slot"
      class="legend-slot"
      :data-slot="entry.slot"
      :data-filled="entry.card !== null"
    >
      <CardComponent
        v-if="entry.card"
        :card="entry.card"
        :definition="definitionOf(entry.card)"
        :side="side"
        size="xs"
        :selectable="interactive"
        :selected="selectedInstanceId === entry.card.instanceId"
        :targetable="entry.card !== null && isTargetable(entry.card.instanceId)"
        :dimmed="interactive && !isActionable(entry.card.instanceId)"
        @click="emit('cardClick', $event)"
      />
      <div v-else class="legend-slot__empty" :title="`Emplacement de Legend ${entry.slot} (vide)`">
        <span class="text-base leading-none text-slate-700" aria-hidden="true">★</span>
        <span class="font-mono text-[0.5rem] uppercase tracking-widest text-slate-600">slot {{ entry.slot }}</span>
      </div>
    </div>
  </div>
</template>
