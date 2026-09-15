<script setup lang="ts">
/**
 * FIELD — centre-haut du tapis : la plus grande zone, pour les Units et leurs Gears.
 *
 * Le serveur envoie le Field comme une seule liste : les cartes sans `attachedTo`
 * sont les Units, celles qui en ont un sont les Gears équipés à cette Unit. Le
 * composant reconstruit donc la grappe « Unit + Gears » à l'affichage.
 */
import { computed } from 'vue'

import CardComponent from '@/components/CardComponent.vue'
import type { GameCard } from '@/types/card'
import type { CardInstance } from '@/types/game'

const props = withDefaults(
  defineProps<{
    /** `PlayerState.field` (Units **et** Gears attachés). */
    field: CardInstance[]
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

const units = computed(() => props.field.filter((card) => !card.attachedTo))

function gearsOf(instanceId: string): CardInstance[] {
  return props.field.filter((card) => card.attachedTo === instanceId)
}

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
  <div class="field-area field-zone" :data-side="side" :data-units="units.length">
    <p v-if="units.length === 0" class="self-center font-mono text-[0.62rem] uppercase tracking-widest text-slate-600">
      Field vide — pose tes Units ici
    </p>

    <div v-for="unit in units" :key="unit.instanceId" class="field-unit">
      <CardComponent
        :card="unit"
        :definition="definitionOf(unit)"
        :side="side"
        size="sm"
        :selectable="interactive"
        :selected="selectedInstanceId === unit.instanceId"
        :targetable="isTargetable(unit.instanceId)"
        :dimmed="interactive && !isActionable(unit.instanceId) && !isTargetable(unit.instanceId)"
        @click="emit('cardClick', $event)"
      />

      <div v-if="gearsOf(unit.instanceId).length" class="field-unit__gears">
        <CardComponent
          v-for="gear in gearsOf(unit.instanceId)"
          :key="gear.instanceId"
          :card="gear"
          :definition="definitionOf(gear)"
          :side="side"
          size="xs"
          :selectable="interactive"
          :selected="selectedInstanceId === gear.instanceId"
          :targetable="isTargetable(gear.instanceId)"
          @click="emit('cardClick', $event)"
        />
      </div>
    </div>
  </div>
</template>
