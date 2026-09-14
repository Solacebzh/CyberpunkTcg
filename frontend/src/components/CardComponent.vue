<script setup lang="ts">
/**
 * Carte en jeu (main, Field, Legends Area…).
 *
 * Composant de présentation : il ne connaît aucune règle. Les états
 * (`selectable`, `selected`, `targetable`, `dimmed`, `tapped`) sont décidés par
 * l'écran de jeu à partir du store, qui reflète lui-même l'état serveur.
 *
 * L'attribut `data-instance-id` sert de point d'accroche aux animations GSAP
 * (`useGameAnimations`) — ne pas le retirer.
 */
import { computed, ref } from 'vue'

import { CARD_TYPE_LABELS, type CardColor, type GameCard } from '@/types/card'
import { isHiddenCard, type CardInstance } from '@/types/game'

type CardSize = 'xs' | 'sm' | 'md' | 'lg'

const props = withDefaults(
  defineProps<{
    card: CardInstance
    /** Définition du catalogue (`GET /api/cards`) : illustration, texte complet. */
    definition?: GameCard | null
    side?: 'me' | 'opponent'
    size?: CardSize
    selectable?: boolean
    selected?: boolean
    targetable?: boolean
    dimmed?: boolean
    showKeywords?: boolean
    showAbilities?: boolean
  }>(),
  {
    definition: null,
    side: 'me',
    size: 'md',
    selectable: false,
    selected: false,
    targetable: false,
    dimmed: false,
    showKeywords: true,
    showAbilities: false,
  },
)

const emit = defineEmits<{ click: [card: CardInstance] }>()

const imageFailed = ref(false)

const FRAME: Record<CardColor, string> = {
  red: 'border-cyber-magenta/80',
  green: 'border-cyber-green/80',
  blue: 'border-cyber-cyan/80',
  yellow: 'border-cyber-yellow/80',
}

const ACCENT: Record<CardColor, string> = {
  red: 'text-cyber-magenta',
  green: 'text-cyber-green',
  blue: 'text-cyber-cyan',
  yellow: 'text-cyber-yellow',
}

const HALO: Record<CardColor, string> = {
  red: 'shadow-[0_0_18px_rgba(255,42,109,0.45)]',
  green: 'shadow-[0_0_18px_rgba(57,255,136,0.45)]',
  blue: 'shadow-[0_0_18px_rgba(5,217,232,0.45)]',
  yellow: 'shadow-[0_0_18px_rgba(252,238,10,0.45)]',
}

const GLYPH: Record<string, string> = {
  unit: '⚔',
  gear: '⚙',
  program: '▤',
  legend: '★',
}

const SIZE: Record<CardSize, { box: string; name: string; stat: string; art: string }> = {
  xs: { box: 'w-[74px]', name: 'text-[0.5rem]', stat: 'text-[0.55rem]', art: 'h-10' },
  sm: { box: 'w-[104px]', name: 'text-[0.6rem]', stat: 'text-[0.6rem]', art: 'h-14' },
  md: { box: 'w-[136px]', name: 'text-[0.7rem]', stat: 'text-[0.7rem]', art: 'h-20' },
  lg: { box: 'w-[176px]', name: 'text-[0.8rem]', stat: 'text-[0.75rem]', art: 'h-28' },
}

const hidden = computed(() => isHiddenCard(props.card) || props.card.faceDown)
const tapped = computed(() => props.card.exhausted)
const sick = computed(() => props.card.summoningSickness && props.card.type === 'unit')
const size = computed(() => SIZE[props.size])
const imageUrl = computed(() => (hidden.value ? null : props.definition?.imageUrl ?? null))
const effectiveCost = computed(() => props.card.cost ?? props.card.baseCost ?? null)
const buffed = computed(() => (props.card.powerBonus ?? 0) > 0)
const damaged = computed(() => (props.card.damage ?? 0) > 0)

function onClick(): void {
  if (!props.selectable && !props.targetable) return
  emit('click', props.card)
}
</script>

<template>
  <article
    :data-instance-id="card.instanceId"
    class="relative shrink-0 select-none overflow-hidden rounded-lg border-2 bg-gradient-to-b from-[#18182b] to-[#0d0d18] shadow-[0_0_15px_rgba(5,217,232,0.2)] transition-all duration-150"
    :class="[
      size.box,
      selected ? 'ring-2 ring-cyber-cyan shadow-[0_0_25px_rgba(5,217,232,0.5)]' : '',
      selectable ? 'cursor-pointer hover:-translate-y-1 hover:brightness-110' : '',
      targetable ? 'cursor-crosshair ring-2 ring-cyber-magenta animate-pulse-slow' : '',
      dimmed ? 'opacity-45 grayscale' : '',
      tapped ? '-rotate-6 opacity-75 saturate-50' : '',
    ]"
    @click="onClick"
  >
    <div v-if="hidden" class="cyber-cardback flex h-full w-full flex-col items-center justify-center gap-1 bg-gradient-to-br from-[#1a1a2e] to-[#0a0a14]">
      <span class="font-mono text-xs uppercase tracking-widest text-cyber-cyan">CP</span>
      <span class="text-xl text-cyber-cyan">{{ GLYPH[card.type] ?? '?' }}</span>
    </div>
    <template v-else>
      <div class="relative overflow-hidden" :class="size.art">
        <img
          v-if="imageUrl && !imageFailed"
          :src="imageUrl ?? undefined"
          :alt="card.name"
          class="h-full w-full object-cover"
          loading="lazy"
          @error="imageFailed = true"
        />
        <div v-else class="grid h-full w-full place-items-center bg-gradient-to-br from-[#1a1a2e] to-[#0a0a14]">
          <span class="text-3xl text-cyber-cyan">{{ GLYPH[card.type] ?? '?' }}</span>
        </div>
        <!-- Cost badge -->
        <div v-if="effectiveCost !== null" class="absolute left-2 top-2 flex h-7 w-7 items-center justify-center rounded-full bg-cyber-red text-xs font-extrabold text-white shadow-lg border border-white/20">
          {{ effectiveCost }}
        </div>
      </div>
      <!-- Minimal info bar -->
      <div class="px-2 py-1.5 bg-[#0f0f14]/90 border-t border-white/5">
        <p class="truncate font-semibold text-xs text-slate-100">{{ card.name }}</p>
        <div class="flex items-center justify-between font-mono text-[0.6rem] text-slate-400 mt-0.5">
          <span>{{ CARD_TYPE_LABELS[card.type] }}</span>
          <span v-if="card.power !== undefined">PWR {{ card.power }}</span>
          <span v-if="definition?.ram != null">RAM {{ definition.ram }}</span>
        </div>
      </div>
    </template>
  </article>
</template>

