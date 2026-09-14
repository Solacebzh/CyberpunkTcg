<script setup lang="ts">
/**
 * Aperçu d'une carte pilotée par les données (JSON).
 *
 * Le composant ne connaît que le schéma `GameCard` : ajouter une carte au jeu
 * ne demande aucune modification du rendu. C'est la base de l'affichage
 * data-driven des futures features (deck builder, plateau de jeu).
 */
import { computed } from 'vue'

import { CARD_COLOR_LABELS, CARD_TYPE_LABELS, type CardColor, type GameCard } from '@/types/card'

const props = defineProps<{ card: GameCard }>()

/** Classes Tailwind écrites en toutes lettres : nécessaires pour que le scanner les conserve. */
const COLOR_STYLES: Record<CardColor, { frame: string; text: string; glow: string }> = {
  red: { frame: 'border-cyber-magenta', text: 'text-cyber-magenta', glow: 'hover:shadow-[0_0_24px_rgba(255,42,109,0.35)]' },
  green: { frame: 'border-cyber-green', text: 'text-cyber-green', glow: 'hover:shadow-[0_0_24px_rgba(57,255,136,0.35)]' },
  blue: { frame: 'border-cyber-cyan', text: 'text-cyber-cyan', glow: 'hover:shadow-[0_0_24px_rgba(5,217,232,0.35)]' },
  yellow: { frame: 'border-cyber-yellow', text: 'text-cyber-yellow', glow: 'hover:shadow-[0_0_24px_rgba(252,238,10,0.35)]' },
}

const style = computed(() => COLOR_STYLES[props.card.color])
const stats = computed(() => {
  const { cost, power, streetCred, ram } = props.card
  return [
    { label: 'Eddies', value: cost },
    { label: 'Power', value: power },
    { label: 'Cred', value: streetCred },
    { label: 'RAM', value: ram },
  ].filter((stat) => stat.value !== null && stat.value !== undefined)
})
</script>

<template>
  <article
    class="cyber-panel flex h-full flex-col gap-3 border-2 p-4 transition-shadow duration-200"
    :class="[style.frame, style.glow]"
  >
    <header class="flex items-start justify-between gap-2">
      <div class="min-w-0">
        <h3 class="truncate text-base font-bold text-slate-100">{{ card.name }}</h3>
        <p v-if="card.subtitle" class="truncate font-mono text-[0.7rem] text-slate-400">{{ card.subtitle }}</p>
      </div>
      <span class="cyber-chip shrink-0" :class="style.text">
        {{ CARD_TYPE_LABELS[card.type] }}
      </span>
    </header>

    <dl class="grid grid-cols-4 gap-1 text-center font-mono text-[0.6rem] uppercase tracking-wider text-slate-400">
      <div v-for="stat in stats" :key="stat.label" class="rounded border border-cyber-line/80 py-1">
        <dt>{{ stat.label }}</dt>
        <dd class="text-sm font-bold text-slate-100">{{ stat.value }}</dd>
      </div>
    </dl>

    <ul v-if="card.tags.length" class="flex flex-wrap gap-1">
      <li v-for="tag in card.tags" :key="tag" class="cyber-chip text-slate-300">{{ tag }}</li>
    </ul>

    <p class="flex-1 text-xs leading-relaxed text-slate-300">{{ card.text }}</p>

    <ul v-if="card.keywords.length" class="flex flex-wrap gap-1 font-mono text-[0.6rem]">
      <li
        v-for="keyword in card.keywords"
        :key="keyword"
        class="rounded border border-cyber-yellow/50 px-1.5 py-0.5 uppercase text-cyber-yellow"
      >
        {{ keyword.replace('_', ' ') }}
      </li>
    </ul>

    <footer class="flex items-center justify-between border-t border-cyber-line/70 pt-2 font-mono text-[0.6rem] text-slate-500">
      <span>{{ card.setCode }} · {{ card.collectorNumber }}</span>
      <span>{{ CARD_COLOR_LABELS[card.color] }}<template v-if="card.rarity"> · {{ card.rarity }}</template></span>
    </footer>
  </article>
</template>
