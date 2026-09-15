<script setup lang="ts">
/**
 * Zone imprimée du tapis officiel (Fixer, Field, Legends, Eddies, Trash, Deck).
 *
 * Le composant ne dessine **que le cadre** de la zone : l'emplacement dans la grille
 * vient de l'attribut `data-zone` (règles CSS `.playmat-zone[data-zone="…"]` de
 * `assets/main.css`) et le contenu est fourni par le slot par défaut.
 *
 * `data-anim` est dérivé du nom de zone (`FIELD` → `field`) : les animations GSAP de
 * `useGameAnimations` (sélecteur `[data-anim="…"][data-side="…"]`) continuent donc de
 * fonctionner quel que soit le composant enfant.
 */
import { computed } from 'vue'

import { PLAYMAT_ZONE_HINTS, PLAYMAT_ZONE_LABELS, type PlaymatZone } from '@/types/playmat'

const props = withDefaults(
  defineProps<{
    zone: PlaymatZone
    side?: 'me' | 'opponent'
    /** Libellé affiché (par défaut celui du tapis : « Fixer », « Legends »…). */
    label?: string
    /** Compteur affiché à droite du libellé (ex. nombre de cartes). */
    badge?: string | number | null
    /** Texte de survol (par défaut le rappel de règle de la zone). */
    hint?: string
    /** Hauteur minimale de la zone en `rem` (0 = automatique). */
    minHeight?: number
  }>(),
  { side: 'me', label: undefined, badge: null, hint: undefined, minHeight: 0 },
)

const title = computed(() => props.label ?? PLAYMAT_ZONE_LABELS[props.zone])
const detail = computed(() => props.hint ?? PLAYMAT_ZONE_HINTS[props.zone])
const style = computed(() => (props.minHeight > 0 ? { minHeight: `${props.minHeight}rem` } : undefined))
</script>

<template>
  <section
    class="playmat-zone"
    :data-zone="zone"
    :data-side="side"
    :data-anim="zone.toLowerCase()"
    :style="style"
    :title="detail"
    :aria-label="`${title} — ${detail}`"
  >
    <header class="playmat-zone__bar">
      <span class="zone-label" data-zone-label="">{{ title }}</span>
      <span v-if="badge !== null && badge !== ''" class="cyber-chip playmat-zone__badge">{{ badge }}</span>
      <slot name="header" />
    </header>

    <div class="playmat-zone__body">
      <slot />
    </div>
  </section>
</template>
