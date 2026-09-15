<script setup lang="ts">
/**
 * FIXER — colonne de gauche du tapis : les dés Gig pas encore lancés.
 *
 * Les 6 dés (`d20`, `d12`, `d10`, `d8`, `d6`, `d4`) sont toujours affichés à leur
 * place imprimée : ceux qui restent dans la zone sont « prêts », les autres (déjà
 * lancés et déplacés dans la Gig Area) sont estompés. Le d20 est en haut : c'est le
 * dernier dé que la Fixer Area peut lâcher (docs/OFFICIAL-RULES.md § FIXER AREA).
 */
import { computed } from 'vue'

import { DIE_FACES, FIXER_DICE_ORDER } from '@/types/playmat'

const props = withDefaults(
  defineProps<{
    /** Dés encore disponibles (`PlayerState.fixerDice`). */
    dice: string[]
    side?: 'me' | 'opponent'
  }>(),
  { side: 'me' },
)

const slots = computed(() =>
  FIXER_DICE_ORDER.map((die) => ({
    die,
    faces: DIE_FACES[die] ?? 6,
    ready: props.dice.includes(die),
  })),
)

const remaining = computed(() => props.dice.length)
</script>

<template>
  <div class="fixer-area" :data-side="side" :data-remaining="remaining">
    <ul class="fixer-dice" :aria-label="`${remaining} dé(s) Gig dans la Fixer Area`">
      <li
        v-for="slot in slots"
        :key="slot.die"
        class="fixer-die"
        :data-fixer-die="slot.die"
        :data-ready="slot.ready"
        :title="
          slot.ready
            ? `${slot.die} : encore dans la Fixer Area (${slot.faces} faces)`
            : `${slot.die} : déjà lancé et placé dans la Gig Area`
        "
      >
        <span class="fixer-die__shape" :class="slot.ready ? 'fixer-die__shape--ready' : 'fixer-die__shape--spent'" aria-hidden="true">
          <span class="fixer-die__value">{{ slot.faces }}</span>
        </span>
        <span class="fixer-die__label">{{ slot.die }}</span>
      </li>
    </ul>
  </div>
</template>
