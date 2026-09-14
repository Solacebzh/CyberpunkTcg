<script setup lang="ts">
/** Indicateur de phase (Draw → Main → Combat → End) et de tour courant. */
import { computed } from 'vue'

import { PHASE_HINTS, PHASE_LABELS, PHASE_ORDER, type Phase } from '@/types/game'

const props = defineProps<{
  phase: Phase | null
  turnNumber: number
  isMyTurn: boolean
  activePlayerName: string
  gameOver: boolean
  waiting?: boolean
}>()

const currentIndex = computed(() => (props.phase ? PHASE_ORDER.indexOf(props.phase) : -1))
const hint = computed(() => (props.phase ? PHASE_HINTS[props.phase] : ''))
</script>

<template>
  <div data-anim="phase" class="cyber-panel flex flex-wrap items-center gap-x-4 gap-y-2 px-3 py-2">
    <div class="flex items-center gap-1.5">
      <span class="font-mono text-[0.6rem] uppercase tracking-[0.25em] text-slate-500">Tour</span>
      <span class="font-mono text-lg font-bold leading-none text-cyber-yellow">{{ turnNumber }}</span>
    </div>

    <ol class="flex items-center gap-1" aria-label="Phases du tour">
      <li
        v-for="(step, index) in PHASE_ORDER"
        :key="step"
        class="flex items-center gap-1"
        :aria-current="step === phase ? 'step' : undefined"
      >
        <span
          class="rounded-sm border px-2 py-1 font-mono text-[0.6rem] uppercase tracking-widest transition"
          :class="
            step === phase
              ? 'border-cyber-cyan bg-cyber-cyan/15 text-cyber-cyan shadow-[0_0_14px_rgba(5,217,232,0.4)]'
              : index < currentIndex
                ? 'border-cyber-line text-slate-500'
                : 'border-cyber-line/60 text-slate-600'
          "
        >
          {{ PHASE_LABELS[step] }}
        </span>
        <span v-if="index < PHASE_ORDER.length - 1" class="text-cyber-line">›</span>
      </li>
    </ol>

    <p class="font-mono text-[0.65rem] text-slate-400">
      <span v-if="gameOver" class="text-cyber-magenta">partie terminée</span>
      <template v-else>
        <span :class="isMyTurn ? 'text-cyber-green' : 'text-cyber-magenta'">
          {{ isMyTurn ? 'à ton tour' : `tour de ${activePlayerName}` }}
        </span>
        <span v-if="hint"> — {{ hint }}</span>
      </template>
      <span v-if="waiting" class="text-cyber-yellow"> · en attente du serveur…</span>
    </p>
  </div>
</template>
