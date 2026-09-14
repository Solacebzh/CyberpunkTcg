<script setup lang="ts">
/**
 * Placeholder du lobby (feature 04).
 *
 * Le contrat de destination est déjà figé (docs/websocket-protocol.md) ; cet écran
 * sera branché dessus sans modifier le reste du client.
 */
import { useConnectionStore } from '@/stores/connection'
import { storeToRefs } from 'pinia'

const store = useConnectionStore()
const { isWsConnected } = storeToRefs(store)
</script>

<template>
  <div class="flex flex-col gap-6">
    <section class="cyber-panel p-6">
      <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-magenta">// feature 04</p>
      <h1 class="cyber-title mt-2 text-2xl text-slate-50 md:text-3xl">Lobby temps réel</h1>
      <p class="mt-3 max-w-2xl text-sm leading-relaxed text-slate-300">
        Création de partie, code d'invitation à partager aux amis, choix du deck et démarrage de la
        manche. Aucune logique de jeu ici : le lobby ne fait que remplir et diffuser l'état de la salle.
      </p>

      <ul class="mt-5 grid gap-2 font-mono text-xs md:grid-cols-2">
        <li class="rounded border border-cyber-line/70 px-3 py-2 text-slate-300">
          <span class="text-cyber-cyan">SEND</span> /app/lobby.create
        </li>
        <li class="rounded border border-cyber-line/70 px-3 py-2 text-slate-300">
          <span class="text-cyber-cyan">SEND</span> /app/lobby.join
        </li>
        <li class="rounded border border-cyber-line/70 px-3 py-2 text-slate-300">
          <span class="text-cyber-green">SUB</span> /topic/lobby.{id}
        </li>
        <li class="rounded border border-cyber-line/70 px-3 py-2 text-slate-300">
          <span class="text-cyber-green">SUB</span> /user/queue/errors
        </li>
      </ul>

      <div class="mt-5 flex flex-wrap items-center gap-3">
        <button v-if="!isWsConnected" type="button" class="cyber-btn cyber-btn--accent" @click="store.connect()">
          Ouvrir le canal temps réel
        </button>
        <span v-else class="cyber-chip text-cyber-green">canal prêt — en attente de la feature 04</span>
      </div>
    </section>

    <section class="cyber-panel p-6">
      <h2 class="cyber-title text-sm text-cyber-cyan">Écrans prévus</h2>
      <ul class="mt-3 grid gap-2 text-xs text-slate-300 md:grid-cols-3">
        <li class="rounded border border-cyber-line/70 px-3 py-3">
          <p class="font-semibold text-slate-100">Deck builder</p>
          <p class="mt-1 text-slate-400">3 Legends uniques, 40-50 cartes, plafonds de RAM par couleur.</p>
        </li>
        <li class="rounded border border-cyber-line/70 px-3 py-3">
          <p class="font-semibold text-slate-100">Table de jeu</p>
          <p class="mt-1 text-slate-400">Fixer, Field, Eddies, Gigs alliés et adverses, journal.</p>
        </li>
        <li class="rounded border border-cyber-line/70 px-3 py-3">
          <p class="font-semibold text-slate-100">Fin de partie</p>
          <p class="mt-1 text-slate-400">6 Gigs au début d'un tour = victoire ; récapitulatif.</p>
        </li>
      </ul>
    </section>
  </div>
</template>
