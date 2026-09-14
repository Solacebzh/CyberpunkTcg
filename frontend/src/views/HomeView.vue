<script setup lang="ts">
/**
 * Page d'accueil — sert aussi de « smoke test » visuel de la stack :
 * REST (/api/health), STOMP (/app/ping → /topic/pong), rendu data-driven des cartes
 * et animations GSAP.
 */
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { storeToRefs } from 'pinia'
import gsap from 'gsap'

import CardPreview from '@/components/CardPreview.vue'
import { useConnectionStore } from '@/stores/connection'
import sampleCardsJson from '@/data/sample-cards.json'
import type { GameCard } from '@/types/card'

const store = useConnectionStore()
const { apiState, apiError, health, wsState, wsDetail, log, isApiUp, isWsConnected, databaseUp } = storeToRefs(store)

const sampleCards = sampleCardsJson as GameCard[]
const root = ref<HTMLElement | null>(null)

const stack = [
  { label: 'Backend', value: 'Java 21 · Spring Boot 3.5' },
  { label: 'Temps réel', value: 'WebSocket STOMP' },
  { label: 'Données', value: 'PostgreSQL 16 · JPA' },
  { label: 'Frontend', value: 'Vue 3 · TS · Pinia' },
  { label: 'UI', value: 'TailwindCSS v4 · GSAP' },
  { label: 'Cartes', value: 'JSON data-driven' },
]

const nextSteps = [
  { feature: '02', label: 'Scraper complet des cartes + import en base' },
  { feature: '03', label: 'Comptes joueurs & deck builder (RAM, 3 Legends, 40-50 cartes)' },
  { feature: '04', label: 'Lobby temps réel et partie 1v1 (serveur autoritaire)' },
  { feature: '05', label: 'Moteur de règles : phases, combat, Gig dice, React window' },
  { feature: '06', label: 'Plateau de jeu animé (GSAP) et journal de partie' },
]

let ctx: gsap.Context | null = null

onMounted(() => {
  // Un seul contexte GSAP : les animations sont annulées proprement au démontage.
  ctx = gsap.context(() => {
    gsap.from('[data-animate="hero"]', {
      y: 18,
      opacity: 0,
      duration: 0.6,
      ease: 'power3.out',
      stagger: 0.08,
    })
    gsap.from('[data-animate="card"]', {
      y: 24,
      opacity: 0,
      duration: 0.5,
      delay: 0.25,
      ease: 'power2.out',
      stagger: 0.1,
    })
  }, root.value ?? undefined)

  void store.checkApi()
})

onBeforeUnmount(() => {
  ctx?.revert()
  // La connexion reste ouverte pendant la navigation : c'est le store qui la pilote.
})

const logColor = (kind: 'info' | 'success' | 'error'): string =>
  kind === 'success' ? 'text-cyber-green' : kind === 'error' ? 'text-cyber-magenta' : 'text-slate-400'
</script>

<template>
  <div ref="root" class="flex flex-col gap-8">
    <!-- Hero -->
    <section class="cyber-panel overflow-hidden p-6 md:p-8">
      <p data-animate="hero" class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-magenta">
        // Night City · serveur autoritaire
      </p>
      <h1 data-animate="hero" class="cyber-title mt-2 text-3xl text-slate-50 md:text-5xl">
        Cyberpunk <span class="text-cyber-yellow">TCG</span> Online
      </h1>
      <p data-animate="hero" class="mt-3 max-w-2xl text-sm leading-relaxed text-slate-300">
        Monorepo prêt à jouer : backend Spring Boot autoritaire, client Vue 3 temps réel, cartes
        entièrement pilotées par des données JSON. Objectif — jouer entre amis, sans chercher ses cartes
        au fond d'un placard.
      </p>

      <ul data-animate="hero" class="mt-5 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        <li
          v-for="item in stack"
          :key="item.label"
          class="flex items-center justify-between rounded border border-cyber-line/80 bg-white/[0.02] px-3 py-2"
        >
          <span class="font-mono text-[0.65rem] uppercase tracking-widest text-slate-500">{{ item.label }}</span>
          <span class="text-xs font-semibold text-slate-200">{{ item.value }}</span>
        </li>
      </ul>
    </section>

    <!-- Diagnostics -->
    <section class="grid gap-4 lg:grid-cols-2">
      <div class="cyber-panel p-5">
        <h2 class="cyber-title text-sm text-cyber-cyan">Diagnostic de connexion</h2>
        <p class="mt-1 font-mono text-[0.7rem] text-slate-500">
          Backend attendu sur :8080 · frontend sur :5173 (proxy /api et /ws)
        </p>

        <dl class="mt-4 grid gap-2 font-mono text-xs">
          <div class="flex items-center justify-between gap-3 rounded border border-cyber-line/70 px-3 py-2">
            <dt class="text-slate-400">GET /api/health</dt>
            <dd :class="isApiUp ? 'text-cyber-green' : 'text-cyber-magenta'">
              <template v-if="apiState === 'checking'">…</template>
              <template v-else-if="isApiUp">{{ health?.service }} v{{ health?.version }}</template>
              <template v-else>{{ apiError ?? 'non vérifié' }}</template>
            </dd>
          </div>
          <div class="flex items-center justify-between gap-3 rounded border border-cyber-line/70 px-3 py-2">
            <dt class="text-slate-400">Base PostgreSQL</dt>
            <dd :class="databaseUp ? 'text-cyber-green' : 'text-slate-400'">
              {{ databaseUp ? 'joignable' : 'injoignable (docker compose up -d db)' }}
            </dd>
          </div>
          <div class="flex items-center justify-between gap-3 rounded border border-cyber-line/70 px-3 py-2">
            <dt class="text-slate-400">STOMP /ws</dt>
            <dd :class="isWsConnected ? 'text-cyber-green' : 'text-slate-400'">
              {{ isWsConnected ? 'connecté' : wsDetail ?? 'non connecté' }}
            </dd>
          </div>
        </dl>

        <div class="mt-4 flex flex-wrap gap-2">
          <button type="button" class="cyber-btn" :disabled="apiState === 'checking'" @click="store.checkApi()">
            Revérifier l’API
          </button>
          <button v-if="!isWsConnected" type="button" class="cyber-btn cyber-btn--accent" @click="store.connect()">
            Ouvrir le WebSocket
          </button>
          <template v-else>
            <button type="button" class="cyber-btn cyber-btn--accent" @click="store.ping()">Envoyer /app/ping</button>
            <button type="button" class="cyber-btn" @click="store.disconnect()">Fermer</button>
          </template>
        </div>

        <p class="mt-2 font-mono text-[0.65rem] text-slate-500">
          état ws : {{ wsState }} · ping → <span class="text-slate-300">/app/ping</span> · pong ←
          <span class="text-slate-300">/topic/pong</span>
        </p>
      </div>

      <div class="cyber-panel flex flex-col p-5">
        <div class="flex items-center justify-between">
          <h2 class="cyber-title text-sm text-cyber-cyan">Journal temps réel</h2>
          <button
            type="button"
            class="font-mono text-[0.65rem] uppercase tracking-widest text-slate-500 transition hover:text-cyber-cyan"
            @click="store.clearLog()"
          >
            effacer
          </button>
        </div>

        <ul class="mt-3 max-h-64 flex-1 space-y-1 overflow-y-auto font-mono text-[0.7rem]">
          <li v-if="!log.length" class="text-slate-500">Aucun événement pour le moment.</li>
          <li v-for="(entry, index) in log" :key="`${entry.at}-${index}`" :class="logColor(entry.kind)">
            <span class="text-slate-600">[{{ entry.at }}]</span> {{ entry.message }}
          </li>
        </ul>
      </div>
    </section>

    <!-- Rendu data-driven -->
    <section>
      <div class="mb-4 flex flex-wrap items-end justify-between gap-2">
        <div>
          <h2 class="cyber-title text-sm text-cyber-yellow">Cartes pilotées par les données</h2>
          <p class="font-mono text-[0.7rem] text-slate-500">
            Échantillon de démonstration — schéma réel dans docs/schemas/card.schema.json
          </p>
        </div>
        <span class="cyber-chip text-slate-400">{{ sampleCards.length }} cartes rendues</span>
      </div>

      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <div v-for="card in sampleCards" :key="card.id" data-animate="card">
          <CardPreview :card="card" />
        </div>
      </div>
    </section>

    <!-- Suite -->
    <section class="cyber-panel p-5">
      <h2 class="cyber-title text-sm text-cyber-cyan">Prochaines étapes</h2>
      <ol class="mt-4 grid gap-2 md:grid-cols-2">
        <li
          v-for="step in nextSteps"
          :key="step.feature"
          class="flex items-center gap-3 rounded border border-cyber-line/70 px-3 py-2"
        >
          <span class="font-mono text-xs font-bold text-cyber-magenta">{{ step.feature }}</span>
          <span class="text-xs text-slate-300">{{ step.label }}</span>
        </li>
      </ol>
    </section>
  </div>
</template>
