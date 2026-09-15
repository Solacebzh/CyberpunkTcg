<script setup lang="ts">
/**
 * Bandeau « tout en haut » du tapis : les deux compteurs de Gigs.
 *
 * Reprend la disposition officielle du haut du playmat : la Gig Area du rival
 * (**RIVAL GIGS**) et la nôtre (**FRIENDLY GIGS**), côte à côte, avec les dés Gig,
 * le Street Cred (somme des valeurs) et le rappel des dés restants en Fixer Area.
 *
 * Le composant est passif : tout vient de `PlayerState` (dernière `STATE` serveur).
 */
import GigTracker from '@/components/game/GigTracker.vue'
import type { PlayerState } from '@/types/game'
import { GIG_LABELS } from '@/types/playmat'

const props = defineProps<{
  me: PlayerState | null
  opponent: PlayerState | null
  activePlayerId?: string | null
}>()

function isActive(player: PlayerState | null): boolean {
  return !!player && player.playerId === props.activePlayerId
}
</script>

<template>
  <section class="gigs-bar" data-zone="GIGS" aria-label="Gig Area — Gigs du rival et Gigs alliés">
    <div class="gigs-bar__side" :data-active="isActive(opponent)">
      <GigTracker
        v-if="opponent"
        :label="GIG_LABELS.rival"
        side="opponent"
        :player-name="opponent.name"
        :gig-count="opponent.gigCount"
        :gigs="opponent.gigs"
        :fixer-dice="opponent.fixerDice"
        :street-cred="opponent.streetCred"
      />
      <p v-else class="gigs-bar__waiting font-mono text-[0.6rem] uppercase tracking-widest text-slate-600">
        en attente du rival…
      </p>
    </div>

    <div class="gigs-bar__side" :data-active="isActive(me)">
      <GigTracker
        v-if="me"
        :label="GIG_LABELS.friendly"
        side="me"
        :player-name="me.name"
        :gig-count="me.gigCount"
        :gigs="me.gigs"
        :fixer-dice="me.fixerDice"
        :street-cred="me.streetCred"
      />
      <p v-else class="gigs-bar__waiting font-mono text-[0.6rem] uppercase tracking-widest text-slate-600">
        synchronisation…
      </p>
    </div>
  </section>
</template>
