<script setup lang="ts">
/** Carte officielle en jeu. Les règles restent entièrement gérées par son parent. */
import { computed, ref, watch } from 'vue'

import CardZoomModal from '@/components/CardZoomModal.vue'
import type { GameCard } from '@/types/card'
import { isHiddenCard, type CardInstance } from '@/types/game'

type CardSize = 'xs' | 'sm' | 'md' | 'lg'

const props = withDefaults(
  defineProps<{
    card: CardInstance
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

const emit = defineEmits<{
  (event: 'click', card: CardInstance): void
}>()

const SIZE: Record<CardSize, string> = {
  xs: 'w-[74px]',
  sm: 'w-[104px]',
  md: 'w-[136px]',
  lg: 'w-[176px]',
}

// Le serveur ne fournit pas d'URL pour une carte cachée. Ce SVG reste une image,
// afin que le visuel de la carte ne contienne jamais de texte HTML superposé.
const CARD_BACK = `data:image/svg+xml,${encodeURIComponent(`
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 600 900">
  <defs><linearGradient id="b" x2="1" y2="1"><stop stop-color="#15152a"/><stop offset="1" stop-color="#080810"/></linearGradient></defs>
  <rect width="600" height="900" rx="35" fill="url(#b)"/>
  <rect x="24" y="24" width="552" height="852" rx="24" fill="none" stroke="#05d9e8" stroke-width="8"/>
  <path d="M70 175h460M70 725h460M150 100v700M450 100v700" stroke="#ff2a6d" stroke-width="3" opacity=".45"/>
  <path d="M300 250l145 200-145 200-145-200z" fill="none" stroke="#05d9e8" stroke-width="15"/>
  <circle cx="300" cy="450" r="82" fill="#ff2a6d" opacity=".75"/>
</svg>`)} `

const imageFailed = ref(false)
const zoomed = ref(false)
const hidden = computed(() => isHiddenCard(props.card) || props.card.faceDown)
const officialImage = computed(() => (hidden.value ? null : props.definition?.imageUrl ?? null))
const displayedImage = computed(() => (!imageFailed.value && officialImage.value ? officialImage.value : CARD_BACK))
const canInspect = computed(() => Boolean(officialImage.value && !imageFailed.value))

watch(officialImage, () => {
  imageFailed.value = false
  zoomed.value = false
})

function inspect(): void {
  if (canInspect.value) zoomed.value = true
}
</script>

<template>
  <article
    :data-instance-id="card.instanceId"
    :data-card-zone="card.zone"
    :data-card-side="side"
    class="group relative aspect-[2/3] shrink-0 select-none transition-[transform,box-shadow,opacity] duration-200"
    :class="[
      SIZE[size],
      selectable ? 'cursor-pointer rounded-xl shadow-[0_0_8px_2px_rgba(5,217,232,0.8),0_0_22px_rgba(255,42,109,0.55)] hover:-translate-y-1 hover:shadow-[0_0_12px_3px_rgba(5,217,232,0.95),0_0_30px_rgba(255,42,109,0.7)]' : '',
      selected ? 'rounded-xl ring-2 ring-cyber-cyan shadow-[0_0_30px_rgba(5,217,232,0.8)]' : '',
      targetable ? 'cursor-crosshair rounded-xl ring-2 ring-cyber-magenta shadow-[0_0_28px_rgba(255,42,109,0.8)] animate-pulse-slow' : '',
      dimmed ? 'opacity-45' : '',
      card.exhausted ? 'rotate-90' : '',
    ]"
    :title="hidden ? 'Carte masquée' : `${card.name} — clic droit pour inspecter`"
    :aria-label="hidden ? 'Carte masquée' : card.name"
    @click="emit('click', card)"
    @contextmenu.prevent="inspect"
  >
    <img
      :src="displayedImage"
      :alt="hidden ? 'Dos de carte' : card.name"
      class="h-full w-full rounded-xl object-contain"
      loading="lazy"
      draggable="false"
      @error="imageFailed = true"
    />
    <button
      v-if="canInspect"
      type="button"
      class="absolute right-1.5 top-1.5 grid h-7 w-7 place-items-center rounded-full border border-cyber-cyan/80 bg-black/80 text-sm text-white opacity-0 shadow-[0_0_10px_rgba(5,217,232,0.7)] transition-opacity hover:text-cyber-cyan focus:opacity-100 group-hover:opacity-100"
      :aria-label="`Inspecter ${card.name}`"
      title="Inspecter la carte"
      @click.stop="inspect"
    >
      🔍
    </button>
  </article>

  <CardZoomModal
    v-if="zoomed && officialImage"
    :image-url="officialImage"
    :alt="card.name"
    @close="zoomed = false"
  />
</template>
