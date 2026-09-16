<script setup lang="ts">
import { ref, watch } from 'vue'

import CardZoomModal from '@/components/CardZoomModal.vue'
import type { GameCard } from '@/types/card'

const props = defineProps<{ card: GameCard }>()
const imageFailed = ref(false)
const zoomed = ref(false)

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
    :aria-label="card.name"
    :title="`${card.name} — clic droit pour inspecter`"
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
    <button
      v-if="card.imageUrl && !imageFailed"
      type="button"
      class="absolute right-2 top-2 grid h-8 w-8 place-items-center rounded-full border border-cyber-cyan/80 bg-black/80 text-sm text-white opacity-0 shadow-[0_0_10px_rgba(5,217,232,0.7)] transition-opacity hover:text-cyber-cyan focus:opacity-100 group-hover:opacity-100"
      :aria-label="`Inspecter ${card.name}`"
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
