<script setup lang="ts">
/**
 * FIXER — colonne de gauche du tapis : les dés Gig pas encore lancés.
 *
 * Les 6 dés (`d20`, `d12`, `d10`, `d8`, `d6`, `d4`) sont toujours affichés à leur
 * place imprimée : ceux qui restent dans la zone sont « prêts », les autres (déjà
 * lancés et déplacés dans la Gig Area) sont estompés. Le d20 est en haut : c'est le
 * dernier dé que la Fixer Area peut lâcher (docs/OFFICIAL-RULES.md § FIXER AREA).
 *
 * Mini-Feature 5 (phase DRAW interactive) : quand le serveur attend le choix du
 * dé (`AWAITING_DIE_SELECT`), les dés listés dans `selectableDice` deviennent des
 * boutons ; les autres (déjà lancés, ou le d20 tant qu'il reste d'autres dés)
 * restent grisés. Le clic remonte `select-die` — le serveur lance le dé.
 */
import { computed } from 'vue'

import { DIE_FACES, FIXER_DICE_ORDER } from '@/types/playmat'

const props = withDefaults(
  defineProps<{
    /** Dés encore disponibles (`PlayerState.fixerDice`). */
    dice: string[]
    side?: 'me' | 'opponent'
    /** Dés cliquables maintenant (vide = aucune sélection en cours). */
    selectableDice?: string[]
    /** Une sélection de dé est attendue du joueur (met la colonne en évidence). */
    selecting?: boolean
  }>(),
  { side: 'me', selectableDice: () => [], selecting: false },
)

const emit = defineEmits<{ selectDie: [die: string] }>()

const slots = computed(() =>
  FIXER_DICE_ORDER.map((die) => {
    const ready = props.dice.includes(die)
    const selectable = props.selecting && ready && props.selectableDice.includes(die)
    return {
      die,
      faces: DIE_FACES[die] ?? 6,
      ready,
      selectable,
      /** Prêt mais interdit pour l'instant : le d20 tant qu'il reste d'autres dés. */
      locked: props.selecting && ready && !selectable,
    }
  }),
)

const remaining = computed(() => props.dice.length)

function titleOf(slot: { die: string; faces: number; ready: boolean; selectable: boolean; locked: boolean }): string {
  if (slot.selectable) return `Lancer le ${slot.die} (1 à ${slot.faces})`
  if (slot.locked) return `${slot.die} : le d20 se lance toujours en dernier`
  if (slot.ready) return `${slot.die} : encore dans la Fixer Area (${slot.faces} faces)`
  return `${slot.die} : déjà lancé et placé dans la Gig Area`
}

function onSelect(die: string, selectable: boolean): void {
  if (selectable) emit('selectDie', die)
}
</script>

<template>
  <div class="fixer-area" :data-side="side" :data-remaining="remaining" :data-selecting="selecting ? 'true' : null">
    <ul class="fixer-dice" :aria-label="`${remaining} dé(s) Gig dans la Fixer Area`">
      <li
        v-for="slot in slots"
        :key="slot.die"
        class="fixer-die"
        :data-fixer-die="slot.die"
        :data-ready="slot.ready"
        :data-selectable="slot.selectable ? 'true' : null"
        :data-locked="slot.locked ? 'true' : null"
        :title="titleOf(slot)"
      >
        <component
          :is="slot.selectable ? 'button' : 'span'"
          :type="slot.selectable ? 'button' : undefined"
          class="fixer-die__shape"
          :class="[
            slot.ready ? 'fixer-die__shape--ready' : 'fixer-die__shape--spent',
            slot.selectable ? 'fixer-die__shape--selectable' : '',
            slot.locked ? 'fixer-die__shape--locked' : '',
          ]"
          :aria-label="slot.selectable ? `Lancer le ${slot.die}` : undefined"
          :aria-hidden="slot.selectable ? undefined : 'true'"
          :disabled="slot.selectable ? undefined : slot.locked ? true : undefined"
          @click="onSelect(slot.die, slot.selectable)"
        >
          <span class="fixer-die__value">{{ slot.faces }}</span>
        </component>
        <span class="fixer-die__label">{{ slot.die }}</span>
      </li>
    </ul>
  </div>
</template>
