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
import { KEYWORD_LABELS, isHiddenCard, type CardInstance } from '@/types/game'

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
const attachmentCount = computed(() => props.card.attachments?.length ?? 0)
const keywords = computed(() => props.card.keywords.slice(0, 3))

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
    class="relative shrink-0 select-none rounded-xl overflow-hidden shadow-[inset_0_0_30px_rgba(255,215,0,0.15)] border-2 bg-gradient-to-b from-[#18182b] to-[#0d0d18] transition-[transform,box-shadow,opacity] duration-150"
    :class="[
      size.box,
      FRAME[card.color],
      selected ? HALO[card.color] : '',
      selectable ? 'cursor-pointer hover:-translate-y-1 hover:brightness-110' : '',
      targetable ? 'cursor-crosshair ring-2 ring-cyber-magenta animate-pulse-slow' : '',
      dimmed ? 'opacity-45 grayscale' : '',
      tapped ? '-rotate-6 opacity-75 saturate-50' : '',
    ]"
    :title="hidden ? 'Carte masquée' : card.name"
    :aria-label="hidden ? 'Carte masquée' : card.name"
    :aria-pressed="selectable ? selected : undefined"
    @click="onClick"
  >
    <!-- Dos de carte -->
    <div
      v-if="hidden"
      class="cyber-cardback flex flex-col items-center justify-center gap-1 overflow-hidden rounded-sm"
      :class="[size.box, size.art]"
    >
      <span class="font-mono text-[0.55rem] uppercase tracking-[0.25em] text-cyber-cyan/80">CP</span>
      <span class="text-lg" :class="ACCENT[card.color]">{{ GLYPH[card.type] ?? '?' }}</span>
      <span class="font-mono text-[0.5rem] uppercase tracking-widest text-slate-500">
        {{ CARD_TYPE_LABELS[card.type] }}
      </span>
    </div>

    <template v-else>
      <!-- Illustration -->
      <div class="relative overflow-hidden rounded-t-sm border-b" :class="[size.art, FRAME[card.color]]">
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
          class="grid h-full w-full place-items-center bg-[radial-gradient(circle_at_30%_20%,rgba(5,217,232,0.25),transparent_60%),linear-gradient(160deg,#14141f,#0a0a12)]"
        >
          <span class="text-xl" :class="ACCENT[card.color]">{{ GLYPH[card.type] ?? '?' }}</span>
        </div>

        <!-- Coût -->
        <span
          v-if="effectiveCost !== null"
          class="absolute left-1 top-1 grid h-5 min-w-5 place-items-center rounded-sm border border-cyber-yellow/70 bg-black/75 px-1 font-mono text-[0.6rem] font-bold text-cyber-yellow"
          :title="`Coût : ${effectiveCost} Eddies`"
        >
          {{ effectiveCost }}
        </span>

        <!-- Type -->
        <span
          class="absolute right-1 top-1 rounded-sm border border-cyber-line bg-black/70 px-1 font-mono text-[0.5rem] uppercase tracking-wider"
          :class="ACCENT[card.color]"
        >
          {{ CARD_TYPE_LABELS[card.type] }}
        </span>
      </div>

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
        </div>

        <ul v-if="showKeywords && keywords.length" class="flex flex-wrap gap-0.5">
          <li
            v-for="keyword in keywords"
            :key="keyword"
            class="rounded-sm border border-cyber-yellow/40 px-1 font-mono text-[0.45rem] uppercase text-cyber-yellow"
            :title="keyword"
          >
            {{ KEYWORD_LABELS[keyword] ?? keyword }}
          </li>
        </ul>

        <p
          v-if="showAbilities && card.abilities.length"
          class="line-clamp-3 text-[0.55rem] leading-snug text-slate-400"
        >
          {{ card.abilities.join(' · ') }}
        </p>
      </div>
    </template>

    <!-- Marqueurs d'état -->
    <span
      v-if="tapped"
      class="absolute -right-1 top-1/2 -translate-y-1/2 rotate-90 rounded-sm border border-cyber-magenta/60 bg-black/85 px-1 font-mono text-[0.45rem] uppercase tracking-widest text-cyber-magenta"
    >
      épuisée
    </span>
    <span
      v-else-if="sick"
      class="absolute right-1 bottom-1 rounded-sm border border-cyber-line bg-black/80 px-1 font-mono text-[0.45rem] uppercase text-slate-300"
      title="Ne peut pas attaquer ce tour (sauf Go solo)"
    >
      zZ
    </span>

    <span
      v-if="selected"
      class="pointer-events-none absolute inset-0 rounded border-2"
      :class="[FRAME[card.color], HALO[card.color]]"
    />
  </article>
</template>
