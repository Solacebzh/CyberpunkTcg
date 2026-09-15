<script setup lang="ts">
/**
 * Compteur de Gigs d'un camp (compteur de victoire) + Street Cred.
 *
 * Les Gigs sont des **dés** : leur valeur compte pour le Street Cred, leur nombre
 * compte pour la victoire (`gigCount ≥ 7` en début de tour). Les deux compteurs
 * (Rival / Friendly) sont posés **tout en haut** du tapis par `GigsBar`, comme sur
 * le tapis officiel (§ PLAYMAT AREAS / GIG AREA).
 *
 * `data-anim="gigs" data-side="…"` est le point d'accroche de `useGameAnimations.gigPulse`.
 */
import { computed } from 'vue'

import { GIGS_TO_WIN } from '@/types/game'

const props = withDefaults(
  defineProps<{
    /** « Rival Gigs » ou « Friendly Gigs ». */
    label: string
    side: 'me' | 'opponent'
    playerName: string
    gigCount: number
    /** Valeurs des dés Gig possédés (ex. `[2, 6]`). */
    gigs: number[]
    /** Dés de la Fixer Area pas encore lancés. */
    fixerDice: string[]
    streetCred: number
    /** Nombre de dés Gig disponibles au départ (6). */
    fixerTotal?: number
    target?: number
  }>(),
  { fixerTotal: 6, target: GIGS_TO_WIN },
)

const pips = computed(() => Array.from({ length: props.target }, (_, index) => index))
const leading = computed(() => props.gigCount >= props.target)
</script>

<template>
  <div
    data-anim="gigs"
    :data-side="side"
    class="cyber-panel gig-counter"
    :class="leading ? 'gig-counter--leading' : ''"
    :aria-label="`${label} (${playerName}) : ${gigCount} Gigs sur ${target}`"
  >
    <p class="flex items-center gap-1.5 font-mono text-[0.55rem] uppercase tracking-[0.25em]">
      <span :class="side === 'me' ? 'text-cyber-green' : 'text-cyber-magenta'">{{ label }}</span>
      <span class="text-slate-500" :title="`Gig Area de ${playerName}`">{{ playerName }}</span>
    </p>

    <div class="gig-counter__row">
      <span class="font-mono text-base font-bold leading-none" :class="side === 'me' ? 'text-cyber-green' : 'text-cyber-magenta'">
        {{ gigCount }}<span class="text-slate-500">/{{ target }}</span>
      </span>

      <ul class="flex items-center gap-1" aria-hidden="true">
        <li
          v-for="pip in pips"
          :key="pip"
          data-anim="gig-pip"
          class="h-3 w-3 rotate-45 border transition"
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

      <span class="ml-auto flex items-center gap-1.5 font-mono text-[0.6rem] text-slate-400">
        <span class="cyber-chip" :title="`Dés encore dans la Fixer Area (${fixerDice.length}/${fixerTotal})`">
          fixer {{ fixerDice.length }}/{{ fixerTotal }}
        </span>
        <span class="cyber-chip border-cyber-cyan/50 text-cyber-cyan" title="Street Cred = somme des dés Gig">
          SC {{ streetCred }}
        </span>
      </span>
    </div>
  </div>
</template>
