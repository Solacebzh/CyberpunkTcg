<script setup lang="ts">
/** Fin de partie : vainqueur, raison (texte serveur en français), retour au lobby. */
defineProps<{
  winnerId: string | null
  endReason: string | null
  iWon: boolean
  playerName: string
  opponentName: string
}>()

const emit = defineEmits<{ leave: []; concede: [] }>()
</script>

<template>
  <div
    class="fixed inset-0 z-50 grid place-items-center bg-black/80 px-4 backdrop-blur-sm"
    role="dialog"
    aria-modal="true"
    aria-label="Fin de partie"
  >
    <div
      class="cyber-panel w-full max-w-lg border-2 p-6 text-center"
      :class="iWon ? 'border-cyber-green/80 shadow-[0_0_40px_rgba(57,255,136,0.35)]' : 'border-cyber-magenta/80 shadow-[0_0_40px_rgba(255,42,109,0.35)]'"
    >
      <p class="font-mono text-[0.65rem] uppercase tracking-[0.35em]" :class="iWon ? 'text-cyber-green' : 'text-cyber-magenta'">
        // game over
      </p>
      <h2 class="cyber-title mt-2 text-3xl" :class="iWon ? 'text-cyber-green' : 'text-cyber-magenta'">
        {{ iWon ? 'Victoire' : 'Défaite' }}
      </h2>
      <p class="mt-3 text-sm text-slate-200">
        <span class="font-semibold" :class="iWon ? 'text-cyber-green' : 'text-cyber-magenta'">
          {{ winnerId ?? '—' }}
        </span>
        remporte la partie.
      </p>
      <p class="mt-1 font-mono text-[0.7rem] text-slate-400">{{ endReason ?? 'Raison non précisée par le serveur' }}</p>

      <p class="mt-4 font-mono text-[0.65rem] text-slate-500">
        {{ playerName }} vs {{ opponentName }} — le salon est fermé côté serveur.
      </p>

      <div class="mt-6 flex flex-wrap justify-center gap-3">
        <button type="button" class="cyber-btn cyber-btn--accent" @click="emit('leave')">Retour au lobby</button>
      </div>
    </div>
  </div>
</template>
