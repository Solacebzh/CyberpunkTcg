<script setup lang="ts">
/**
 * Surcouche de ciblage (attaques, équipement de Gear).
 *
 * Le conteneur est en `pointer-events-none` : seules la bannière et ses boutons
 * captent les clics, les cartes du plateau restent donc cliquables en dessous.
 * Les cibles légales sont mises en évidence par `CardComponent` (`targetable`),
 * la liste venant de `gameStore.targeting.candidates`.
 *
 * `Échap` ou un clic sur « Annuler » sort du mode ciblage.
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

import type { TargetingKind } from '@/stores/game'

defineProps<{
  kind: TargetingKind
  sourceName: string
  candidates: string[]
  allowDirect?: boolean
}>()

const emit = defineEmits<{ cancel: []; direct: [] }>()

const reticle = ref<HTMLElement | null>(null)

const TITLE: Record<TargetingKind, string> = {
  attack: 'Choisis une Unit rivale à attaquer',
  gear: 'Choisis l’Unit alliée à équiper',
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('cancel')
  }
}

function onPointerMove(event: PointerEvent): void {
  const node = reticle.value
  if (!node) return
  node.style.transform = `translate3d(${event.clientX - 22}px, ${event.clientY - 22}px, 0)`
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('pointermove', onPointerMove)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('pointermove', onPointerMove)
})
</script>

<template>
  <div class="pointer-events-none fixed inset-0 z-40">
    <!-- Voile léger : la cible reste visible et cliquable -->
    <div class="absolute inset-0 bg-[radial-gradient(circle_at_center,transparent_35%,rgba(255,42,109,0.12))]" />

    <!-- Réticule -->
    <div
      ref="reticle"
      class="absolute left-0 top-0 h-11 w-11 rounded-full border-2 border-cyber-magenta/80 shadow-[0_0_18px_rgba(255,42,109,0.55)]"
      aria-hidden="true"
    >
      <span class="absolute left-1/2 top-1/2 h-1 w-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-cyber-magenta" />
    </div>

    <!-- Bannière -->
    <div class="pointer-events-auto absolute inset-x-0 top-20 mx-auto w-fit max-w-[92vw]">
      <div
        class="cyber-panel flex flex-wrap items-center gap-x-4 gap-y-2 border-cyber-magenta/70 px-4 py-3 shadow-[0_0_28px_rgba(255,42,109,0.35)]"
        role="dialog"
        aria-modal="false"
        aria-label="Sélection de cible"
      >
        <div class="min-w-0">
          <p class="font-mono text-[0.6rem] uppercase tracking-[0.3em] text-cyber-magenta">// ciblage</p>
          <p class="text-sm font-semibold text-slate-100">{{ TITLE[kind] }}</p>
          <p class="font-mono text-[0.65rem] text-slate-400">
            source : <span class="text-cyber-yellow">{{ sourceName }}</span> ·
            {{ candidates.length }} cible(s) légale(s)
          </p>
        </div>

        <div class="flex flex-wrap items-center gap-2">
          <button v-if="allowDirect" type="button" class="cyber-btn cyber-btn--accent" @click="emit('direct')">
            Vol direct de Gig
          </button>
          <button type="button" class="cyber-btn" @click="emit('cancel')">Annuler (Échap)</button>
        </div>
      </div>
    </div>
  </div>
</template>
