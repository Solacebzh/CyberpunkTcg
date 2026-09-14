<script setup lang="ts">
/**
 * Indicateur permanent : état de l'API REST et du canal WebSocket.
 * Cliquer sur « reconnecter » ouvre (ou relance) la connexion STOMP.
 */
import { computed } from 'vue'
import { storeToRefs } from 'pinia'

import { useConnectionStore } from '@/stores/connection'

const store = useConnectionStore()
const { apiState, wsState, isApiUp, isWsConnected, isWsBusy, databaseUp } = storeToRefs(store)

const apiLabel = computed(() => {
  switch (apiState.value) {
    case 'up':
      return 'API en ligne'
    case 'checking':
      return 'API…'
    case 'down':
      return 'API hors ligne'
    default:
      return 'API inconnue'
  }
})

const wsLabel = computed(() => {
  switch (wsState.value) {
    case 'connected':
      return 'WebSocket actif'
    case 'connecting':
      return 'Connexion…'
    case 'error':
      return 'WebSocket en erreur'
    default:
      return 'WebSocket inactif'
  }
})

const dotClass = (state: boolean, pending = false): string =>
  state ? 'bg-cyber-green' : pending ? 'bg-cyber-yellow animate-pulse' : 'bg-cyber-line'
</script>

<template>
  <div class="flex items-center gap-2 font-mono text-[0.65rem] uppercase tracking-widest">
    <button
      type="button"
      class="cyber-chip transition hover:border-cyber-cyan/60"
      :title="databaseUp ? 'PostgreSQL joignable' : 'Base de données non joignable'"
      @click="store.checkApi()"
    >
      <span class="h-2 w-2 rounded-full" :class="dotClass(isApiUp, apiState === 'checking')" />
      {{ apiLabel }}
    </button>

    <button
      type="button"
      class="cyber-chip transition hover:border-cyber-cyan/60"
      :title="isWsConnected ? 'Canal STOMP ouvert sur /ws' : 'Cliquer pour ouvrir le canal STOMP'"
      @click="isWsConnected ? store.ping() : store.connect()"
    >
      <span class="h-2 w-2 rounded-full" :class="dotClass(isWsConnected, isWsBusy)" />
      {{ wsLabel }}
    </button>

    <button
      v-if="isWsConnected"
      type="button"
      class="cyber-chip border-cyber-magenta/50 text-cyber-magenta transition hover:border-cyber-magenta"
      @click="store.disconnect()"
    >
      Quitter
    </button>
  </div>
</template>
