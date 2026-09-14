<script setup lang="ts">
import { computed, ref } from 'vue'

import { CARD_COLOR_LABELS, CARD_TYPE_LABELS, type CardColor, type GameCard } from '@/types/card'

const props = defineProps<{ card: GameCard }>()
const imageFailed = ref(false)

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
  ].filter((stat) => stat.value !== null)
})
</script>

<template>
  <article
    class="cyber-panel flex h-full flex-col gap-3 overflow-hidden border-2 p-4 transition-shadow duration-200"
    :class="[style.frame, style.glow]"
  >
    <img
      v-if="card.imageUrl && !imageFailed"
      :src="card.imageUrl"
      :alt="`${card.name}${card.subtitle ? ` — ${card.subtitle}` : ''}`"
      class="-mx-4 -mt-4 aspect-[5/3] w-[calc(100%+2rem)] border-b border-cyber-line object-cover object-top"
      loading="lazy"
      @error="imageFailed = true"
    />

    <header class="flex items-start justify-between gap-2">
      <div class="min-w-0">
        <h3 class="truncate text-base font-bold text-slate-100">{{ card.name }}</h3>
        <p v-if="card.subtitle" class="truncate font-mono text-[0.7rem] text-slate-400">{{ card.subtitle }}</p>
      </div>
      <span class="cyber-chip shrink-0" :class="style.text">{{ CARD_TYPE_LABELS[card.type] }}</span>
    </header>

    <dl class="grid grid-cols-4 gap-1 text-center font-mono text-[0.6rem] uppercase tracking-wider text-slate-400">
      <div v-for="stat in stats" :key="stat.label" class="rounded border border-cyber-line/80 py-1">
        <dt>{{ stat.label }}</dt>
        <dd class="text-sm font-bold text-slate-100">{{ stat.value }}</dd>
      </div>
    </dl>

    <ul v-if="card.tags.length" class="flex flex-wrap gap-1" aria-label="Tags">
      <li v-for="tag in card.tags" :key="tag" class="cyber-chip text-slate-300">{{ tag }}</li>
    </ul>

    <section class="flex-1 rounded border border-cyber-line/60 bg-black/10 p-2.5">
      <h4 class="mb-1.5 font-mono text-[0.6rem] uppercase tracking-widest text-slate-500">Effets</h4>
      <ul v-if="card.abilities.length" class="space-y-1.5 text-xs leading-relaxed text-slate-300">
        <li v-for="(ability, index) in card.abilities" :key="`${card.id}-ability-${index}`">{{ ability }}</li>
      </ul>
      <p v-else class="text-xs leading-relaxed text-slate-300">{{ card.text || 'Aucun effet.' }}</p>
    </section>

    <ul v-if="card.keywords.length" class="flex flex-wrap gap-1 font-mono text-[0.6rem]" aria-label="Mots-clés">
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
