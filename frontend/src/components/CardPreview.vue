<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import CardZoomModal from '@/components/CardZoomModal.vue'
import type { GameCard } from '@/types/card'

const props = defineProps<{ card: GameCard }>()
const imageFailed = ref(false)
const zoomed = ref(false)

/** Nom + sous-titre : plusieurs cartes partagent le même nom au catalogue. */
const cardLabel = computed(() => (props.card.subtitle ? `${props.card.name} — ${props.card.subtitle}` : props.card.name))

watch(
  () => props.card.imageUrl,
  () => {
    imageFailed.value = false
    zoomed.value = false
  },
)

function inspect(): void {
  if (props.card.imageUrl && !imageFailed.value) zoomed.value = true
}
</script>

<template>
  <article
    class="group relative aspect-[2/3] overflow-hidden rounded-xl bg-[#0d0d18] shadow-lg transition-shadow hover:shadow-[0_0_24px_rgba(5,217,232,0.4)]"
    :aria-label="cardLabel"
    :title="`${cardLabel} — clic droit pour inspecter`"
    @contextmenu.prevent="inspect"
  >
    <img
      v-if="card.imageUrl && !imageFailed"
      :src="card.imageUrl"
      :alt="`${card.name}${card.subtitle ? ` — ${card.subtitle}` : ''}`"
      class="h-full w-full object-contain"
      loading="lazy"
      draggable="false"
      @error="imageFailed = true"
    />
    <div v-else class="grid h-full place-items-center border border-cyber-line p-4 text-center text-xs text-slate-500">
      Image officielle indisponible
    </div>
    <!--
      Mini-Feature 10B : le bouton invisible (`opacity-0`) restait cliquable et
      interceptait le clic du coin haut-droit de la carte — cf. CardComponent.
    -->
    <button
      v-if="card.imageUrl && !imageFailed"
      type="button"
      class="pointer-events-none absolute right-2 top-2 grid h-8 w-8 place-items-center rounded-full border border-cyber-cyan/80 bg-black/80 text-sm text-white opacity-0 shadow-[0_0_10px_rgba(5,217,232,0.7)] transition-opacity hover:text-cyber-cyan focus:pointer-events-auto focus:opacity-100 focus-visible:pointer-events-auto group-hover:pointer-events-auto group-hover:opacity-100"
      :aria-label="`Inspecter ${cardLabel}`"
      title="Inspecter la carte"
      @click.stop="inspect"
    >
      🔍
    </button>
  </article>

  <CardZoomModal
    v-if="zoomed && card.imageUrl"
    :image-url="card.imageUrl"
    :alt="card.name"
    @close="zoomed = false"
  />
</template>
