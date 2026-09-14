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
    :data-card-zone="card.zone"
    :data-card-side="side"
<<<<<<< HEAD
    class="relative shrink-0 select-none rounded-xl overflow-hidden shadow-[inset_0_0_30px_rgba(255,215,0,0.15)] border-2 bg-gradient-to-b from-[#18182b] to-[#0d0d18] transition-[transform,box-shadow,opacity] duration-150"
=======
    class="relative shrink-0 select-none overflow-hidden rounded-xl shadow-[0_0_25px_rgba(5,217,232,0.25)] border-[1.5px] bg-gradient-to-b from-[#171720] via-[#12121e] to-[#0a0a14] transition-all duration-200"
>>>>>>> bf258eb21253dbc7f528e11dcce637352b224c38
    :class="[
      size.box,
      selected ? 'ring-2 ring-cyber-cyan shadow-[0_0_30px_rgba(5,217,232,0.5)]' : '',
      selectable ? 'cursor-pointer hover:-translate-y-1 hover:brightness-110' : '',
      targetable ? 'cursor-crosshair ring-2 ring-cyber-magenta animate-pulse-slow' : '',
      dimmed ? 'opacity-40 grayscale' : '',
      tapped ? '-rotate-6 opacity-70 saturate-50' : '',
    ]"
    :title="hidden ? 'Carte masquée' : card.name"
    :aria-label="hidden ? 'Carte masquée' : card.name"
    @click="onClick"
  >
    <!-- DOS -->
    <div
      v-if="hidden"
      class="cyber-cardback flex h-full w-full flex-col items-center justify-center gap-2"
    >
      <span class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-cyan">CP</span>
      <span class="text-2xl" :class="ACCENT[card.color]">{{ GLYPH[card.type] ?? '?' }}</span>
      <span class="font-mono text-[0.6rem] uppercase tracking-widest text-slate-500">{{ CARD_TYPE_LABELS[card.type] }}</span>
    </div>

    <template v-else>
      <!-- EN-TÊTE : Nom + Sous-titre + Cost + Type + RAM -->
      <div class="relative z-10 flex items-start gap-2 px-2 pt-2 pb-1">
        <!-- Coût (cercle rouge/orange style officiel) -->
        <div
          v-if="effectiveCost !== null"
          class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full border-2 border-cyber-red bg-gradient-to-br from-cyber-red to-red-900 shadow-[0_0_8px_rgba(255,42,42,0.6)] font-mono text-sm font-extrabold text-white"
          :title="`Coût : ${effectiveCost} Eddies`"
        >
          {{ effectiveCost }}
        </div>
        <div v-else class="h-7 w-7 shrink-0" />

        <!-- Nom + Sous-titre -->
        <div class="min-w-0 flex-1">
          <h3 class="truncate text-sm font-black leading-tight tracking-tight text-white drop-shadow-[0_1px_2px_rgba(0,0,0,0.9)]" :class="size.name">
            {{ card.name }}
          </h3>
          <p v-if="definition?.subtitle" class="truncate text-[0.6rem] leading-none text-cyber-cyan/90 font-medium tracking-tight">
            {{ definition?.subtitle }}
          </p>
        </div>

        <!-- Type + RAM -->
        <div class="flex flex-col items-end gap-0.5 text-[0.55rem] font-mono leading-none">
          <span class="rounded border border-cyber-cyan/60 bg-cyber-cyan/10 px-1 text-cyber-cyan uppercase tracking-wide" title="Type">
            {{ CARD_TYPE_LABELS[card.type] }}
          </span>
          <span v-if="definition?.ram != null" class="rounded border border-cyber-yellow/60 bg-cyber-yellow/10 px-1 text-cyber-yellow" title="RAM">
            RAM {{ definition?.ram }}
          </span>
        </div>
      </div>

      <!-- ILLUSTRATION (centre, grande) -->
      <div class="relative overflow-hidden" :class="[size.art]">
        <img
          v-if="imageUrl && !imageFailed"
          :src="imageUrl ?? undefined"
          :alt="card.name"
          class="h-full w-full object-cover object-top"
          loading="lazy"
          @error="imageFailed = true"
        />
        <div
          v-else
          class="grid h-full w-full place-items-center bg-gradient-to-br from-[#1a1a2e] to-[#0f0f18]"
        >
          <span class="text-3xl drop-shadow-lg" :class="ACCENT[card.color]">{{ GLYPH[card.type] ?? '?' }}</span>
        </div>
        <!-- Dégradé de fond pour lisibilité du texte -->
        <div class="absolute inset-x-0 bottom-0 h-1/3 bg-gradient-to-t from-[#0a0a14]/90 via-[#0a0a14]/40 to-transparent pointer-events-none" />
      </div>

<<<<<<< HEAD
      <!-- Nom + stats -->
      <div class="flex flex-col gap-1 px-1.5 py-1">
        <p class="truncate font-semibold leading-tight text-slate-100" :class="size.name">{{ card.name }}</p>

        <div class="flex items-end justify-between gap-2 font-mono" :class="size.stat">
          <span v-if="card.power !== undefined" class="flex items-center gap-1">
            <span class="text-slate-400">PWR</span>
            <span
              class="font-bold"
              :class="damaged ? 'text-cyber-red' : buffed ? 'text-cyber-green' : 'text-slate-100'"
            >
              {{ card.power }}
            </span>
          </span>
          <span v-else class="text-slate-500">—</span>

          <span v-if="card.streetCredThreshold != null" class="text-cyber-cyan" title="Seuil de Street Cred">
            SC {{ card.streetCredThreshold }}
          </span>
          <span v-if="attachmentCount > 0" class="text-cyber-green" :title="`${attachmentCount} Gear(s) équipé(s)`">
            ⚙{{ attachmentCount }}
          </span>
=======
      <!-- TEXTE (Textbox officiel) -->
      <div class="relative z-10 mx-2 mb-1 rounded border border-cyber-yellow/30 bg-gradient-to-b from-[#11111e]/95 to-[#0d0d18]/95 px-2 py-1.5 shadow-inner shadow-cyber-yellow/5">
        <!-- Tags / Attributs -->
        <div class="mb-1 flex flex-wrap gap-0.5">
          <span v-if="definition?.tags?.includes('quick')" class="rounded-sm bg-cyber-yellow/20 px-1 text-[0.45rem] font-mono uppercase text-cyber-yellow">QUICK</span>
          <span v-if="definition?.tags?.includes('blocker')" class="rounded-sm bg-cyber-cyan/20 px-1 text-[0.45rem] font-mono uppercase text-cyber-cyan">BLOCKER</span>
          <span v-if="definition?.tags?.includes('go_solo')" class="rounded-sm bg-cyber-green/20 px-1 text-[0.45rem] font-mono uppercase text-cyber-green">GO SOLO</span>
>>>>>>> bf258eb21253dbc7f528e11dcce637352b224c38
        </div>

        <!-- Règles / Abilities -->
        <p v-if="showAbilities && card.abilities.length" class="line-clamp-3 text-[0.6rem] font-medium leading-snug text-slate-200">
          {{ card.abilities.join(' · ') }}
        </p>
        <p v-else-if="definition?.text" class="line-clamp-3 text-[0.6rem] leading-snug text-slate-200">
          {{ definition?.text }}
        </p>
        <p v-else class="text-[0.6rem] italic text-slate-400">—</p>
      </div>

      <!-- BAS DE CARTE : Stats + Métadonnées -->
      <div class="relative z-10 flex items-end justify-between px-2 pb-1.5 pt-0.5">
        <!-- Gauche : Numéro + Set + Illustrateur -->
        <div class="flex flex-col gap-0.5 text-[0.5rem] font-mono leading-none text-slate-400">
          <div class="flex items-center gap-1.5">
            <span v-if="definition?.collectorNumber" class="font-extrabold text-cyber-yellow/90">#{{ definition?.collectorNumber }}</span>
            <span v-if="definition?.setCode" class="text-[0.45rem] text-slate-500">{{ definition?.setCode }}</span>
          </div>
        </div>

        <!-- Droite : Power + Rareté -->
        <div class="flex items-center gap-2 text-right">
          <div v-if="card.power !== undefined" class="flex flex-col items-end leading-none">
            <span class="text-[0.45rem] font-mono text-slate-500 uppercase tracking-widest">PWR</span>
            <span class="text-sm font-black leading-none drop-shadow-[0_1px_2px_rgba(0,0,0,0.8)]" :class="damaged ? 'text-cyber-red' : buffed ? 'text-cyber-green' : 'text-white'">
              {{ card.power }}
            </span>
          </div>
          <div v-if="definition?.rarity" class="rounded-full border border-cyber-line bg-black/60 px-1.5 py-0.5 text-[0.5rem] font-mono font-bold uppercase tracking-wider text-cyber-yellow shadow-inner">
            {{ definition?.rarity }}
          </div>
        </div>
      </div>

      <!-- Marqueurs d'état absolus -->
      <span v-if="tapped" class="pointer-events-none absolute -right-1 top-1/2 z-50 -translate-y-1/2 rotate-90 rounded bg-cyber-magenta px-1 py-0.5 font-mono text-[0.45rem] font-bold uppercase tracking-widest text-white shadow-lg">ÉPUISÉE</span>
      <span v-else-if="sick" class="pointer-events-none absolute right-1 bottom-1 z-50 rounded bg-black/80 px-1 py-0.5 font-mono text-[0.45rem] text-slate-300 shadow-md" title="Mal d'invocation">Zz</span>

      <span v-if="selected" class="pointer-events-none absolute inset-0 rounded-xl border-2" :class="[FRAME[card.color], HALO[card.color]]" />
    </template>
  </article>
</template>
