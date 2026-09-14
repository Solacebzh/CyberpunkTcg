<script setup lang="ts">
/**
 * Compteur de Gigs (objectif 7) + dés possédés + Fixer Area restante.
 *
 * Les Gigs sont des **dés** : leur valeur compte pour le Street Cred, leur
 * nombre compte pour la victoire (`gigCount ≥ 7` en début de tour).
 */
import { GIGS_TO_WIN } from '@/types/game'

const props = withDefaults(
  defineProps<{
    side: 'me' | 'opponent'
    playerName: string
    gigCount: number
    /** Valeurs des dés Gig possédés (ex. `[2, 6]`). */
    gigs: number[]
    /** Dés de la Fixer Area pas encore lancés. */
    fixerDice: string[]
    streetCred: number
    target?: number
  }>(),
  { target: GIGS_TO_WIN },
)

const pips = Array.from({ length: props.target }, (_, index) => index)
</script>

<template>
  <div
    data-anim="gigs"
    :data-side="side"
    class="cyber-panel flex flex-wrap items-center gap-x-3 gap-y-1.5 px-3 py-2"
    :aria-label="`${playerName} : ${gigCount} Gigs sur ${target}`"
  >
    <div class="flex flex-col">
      <span class="font-mono text-[0.55rem] uppercase tracking-[0.25em] text-slate-500">Gigs</span>
      <span class="font-mono text-base font-bold leading-none" :class="side === 'me' ? 'text-cyber-green' : 'text-cyber-magenta'">
        {{ gigCount }}<span class="text-slate-500">/{{ target }}</span>
      </span>
    </div>

    <ul class="flex items-center gap-1" aria-hidden="true">
      <li
        v-for="pip in pips"
        :key="pip"
        data-anim="gig-pip"
        class="h-3.5 w-3.5 rotate-45 border transition"
        :class="
          pip < gigCount
            ? side === 'me'
              ? 'border-cyber-green bg-cyber-green/70 shadow-[0_0_10px_rgba(57,255,136,0.55)]'
              : 'border-cyber-magenta bg-cyber-magenta/70 shadow-[0_0_10px_rgba(255,42,109,0.55)]'
            : 'border-cyber-line bg-black/40'
        "
      />
    </ul>

    <ul v-if="gigs.length" class="flex flex-wrap items-center gap-1" :aria-label="`Dés Gig de ${playerName}`">
      <li
        v-for="(value, index) in gigs"
        :key="`${side}-gig-${index}`"
        class="cyber-chip border-cyber-yellow/50 text-cyber-yellow"
        :title="`Dé Gig de valeur ${value}`"
      >
        {{ value }}
      </li>
    </ul>
    <span v-else class="font-mono text-[0.6rem] text-slate-600">aucun Gig</span>

    <div class="ml-auto flex items-center gap-2 font-mono text-[0.6rem] text-slate-400">
      <span class="cyber-chip" :title="`Dés de la Fixer Area encore disponibles (${fixerDice.length})`">
        fixer {{ fixerDice.length ? fixerDice.join(' ') : '—' }}
      </span>
      <span class="cyber-chip border-cyber-cyan/50 text-cyber-cyan" title="Street Cred = somme des dés Gig">
        SC {{ streetCred }}
      </span>
    </div>
  </div>
</template>
