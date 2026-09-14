<script setup lang="ts">
/**
 * Deck builder : catalogue `GET /api/cards` + glisser-déposer dans le deck.
 *
 * Le deck produit est une simple liste d'identifiants envoyée au lobby
 * (`deckCardIds`, doc §4.1) — la validation finale reste serveur. Les règles
 * affichées ici sont les mêmes : 3 Legends, ≥ 10 cartes non-Legend, sans doublon.
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import CardPreview from '@/components/CardPreview.vue'
import { REQUIRED_LEGENDS, REQUIRED_NON_LEGENDS, useDeckStore } from '@/stores/deck'
import { useUiStore } from '@/stores/ui'
import { CARD_COLOR_LABELS, CARD_TYPE_LABELS, type CardColor, type CardType } from '@/types/card'

const router = useRouter()
const decks = useDeckStore()
const ui = useUiStore()

const previewId = ref<string | null>(null)
const dropActive = ref<'legends' | 'main' | null>(null)

const TYPES: Array<CardType | 'all'> = ['all', 'legend', 'unit', 'program', 'gear']
const COLORS: Array<CardColor | 'all'> = ['all', 'red', 'green', 'blue', 'yellow']

const legendIds = computed(() => decks.deck.filter((id) => decks.byId.get(id)?.type === 'legend'))
const mainIds = computed(() => decks.deck.filter((id) => decks.byId.get(id)?.type !== 'legend'))
const previewCard = computed(() => (previewId.value ? decks.byId.get(previewId.value) ?? null : null))

onMounted(() => {
  void decks.loadCatalog()
})

function onDragStart(event: DragEvent, cardId: string): void {
  event.dataTransfer?.setData('text/plain', cardId)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copyMove'
}

function onDrop(event: DragEvent): void {
  const cardId = event.dataTransfer?.getData('text/plain')
  dropActive.value = null
  if (!cardId) return
  event.preventDefault()
  addCard(cardId)
}

function addCard(cardId: string): void {
  if (decks.add(cardId)) {
    const card = decks.byId.get(cardId)
    ui.success(`${card?.name ?? cardId} ajouté au deck`)
    return
  }
  const card = decks.byId.get(cardId)
  const legendFull = card?.type === 'legend' && decks.legendCount >= REQUIRED_LEGENDS
  ui.warn(
    legendFull
      ? `Déjà ${REQUIRED_LEGENDS} Legends dans le deck`
      : decks.deck.includes(cardId)
        ? `${card?.name ?? cardId} est déjà dans le deck (doublon interdit)`
        : 'Ajout impossible',
  )
}

function removeCard(cardId: string): void {
  if (decks.remove(cardId)) ui.info(`${decks.byId.get(cardId)?.name ?? cardId} retiré du deck`)
}

function sample(): void {
  decks.buildSampleDeck()
  ui.success('Deck d’exemple généré depuis le catalogue')
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <header class="cyber-panel flex flex-wrap items-end justify-between gap-4 p-5">
      <div>
        <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-magenta">// deck builder</p>
        <h1 class="cyber-title mt-1 text-2xl text-slate-50">Construis ton deck</h1>
        <p class="mt-2 max-w-2xl text-xs text-slate-400">
          Glisse une carte du catalogue vers le deck (ou clique dessus). Règles serveur : exactement
          {{ REQUIRED_LEGENDS }} Legends, au moins {{ REQUIRED_NON_LEGENDS }} cartes non-Legend, aucun doublon.
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <button type="button" class="cyber-btn" @click="sample">Deck d’exemple</button>
        <button type="button" class="cyber-btn cyber-btn--danger" @click="decks.clear()">Vider</button>
        <button type="button" class="cyber-btn cyber-btn--accent" @click="router.push({ name: 'lobby' })">
          Aller au lobby
        </button>
      </div>
    </header>

    <p v-if="decks.catalogState === 'loading'" class="cyber-panel p-4 font-mono text-xs text-slate-400">
      Chargement du catalogue (/api/cards)…
    </p>
    <p v-else-if="decks.catalogState === 'error'" class="cyber-panel border-cyber-magenta/60 p-4 text-xs text-cyber-magenta">
      Catalogue indisponible : {{ decks.catalogError }} — le backend doit tourner sur
      <span class="font-mono">/api/cards</span>.
      <button type="button" class="cyber-btn ml-3" @click="decks.loadCatalog(true)">Réessayer</button>
    </p>

    <div class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <!-- Catalogue -->
      <section class="cyber-panel flex min-w-0 flex-col p-4">
        <div class="flex flex-wrap items-center gap-2">
          <input
            v-model="decks.search"
            type="search"
            placeholder="Rechercher une carte…"
            class="min-w-[12rem] flex-1 rounded border border-cyber-line bg-black/40 px-3 py-2 text-sm text-slate-100 outline-none transition focus:border-cyber-cyan"
          />
          <select
            v-model="decks.typeFilter"
            class="rounded border border-cyber-line bg-black/40 px-2 py-2 font-mono text-xs text-slate-200 outline-none focus:border-cyber-cyan"
          >
            <option v-for="type in TYPES" :key="type" :value="type">
              {{ type === 'all' ? 'Tous les types' : CARD_TYPE_LABELS[type] }}
            </option>
          </select>
          <select
            v-model="decks.colorFilter"
            class="rounded border border-cyber-line bg-black/40 px-2 py-2 font-mono text-xs text-slate-200 outline-none focus:border-cyber-cyan"
          >
            <option v-for="color in COLORS" :key="color" :value="color">
              {{ color === 'all' ? 'Toutes les RAM' : CARD_COLOR_LABELS[color] }}
            </option>
          </select>
          <span class="font-mono text-[0.65rem] text-slate-500">{{ decks.filteredCards.length }} carte(s)</span>
        </div>

        <div class="cyber-scroll mt-3 grid max-h-[34rem] gap-3 overflow-y-auto pr-1 sm:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="card in decks.filteredCards"
            :key="card.id"
            draggable="true"
            class="cursor-grab transition active:cursor-grabbing"
            :class="decks.deck.includes(card.id) ? 'opacity-45' : 'hover:-translate-y-0.5'"
            @dragstart="onDragStart($event, card.id)"
            @click="addCard(card.id)"
            @mouseenter="previewId = card.id"
          >
            <CardPreview :card="card" />
          </div>
          <p v-if="decks.filteredCards.length === 0" class="font-mono text-xs text-slate-500">
            Aucune carte ne correspond aux filtres.
          </p>
        </div>
      </section>

      <!-- Deck -->
      <aside class="flex flex-col gap-3">
        <section
          class="cyber-panel p-4"
          :class="dropActive === 'legends' ? 'border-cyber-yellow shadow-[0_0_22px_rgba(252,238,10,0.35)]' : ''"
          @dragover.prevent="dropActive = 'legends'"
          @dragleave="dropActive = null"
          @drop="onDrop"
        >
          <h2 class="cyber-title text-sm text-cyber-yellow">
            Legends · {{ decks.legendCount }}/{{ REQUIRED_LEGENDS }}
          </h2>
          <ul class="mt-2 flex flex-col gap-1">
            <li
              v-for="id in legendIds"
              :key="id"
              class="flex items-center justify-between gap-2 rounded border border-cyber-line px-2 py-1"
            >
              <span class="truncate text-xs text-slate-200">{{ decks.byId.get(id)?.name ?? id }}</span>
              <button
                type="button"
                class="font-mono text-xs text-cyber-magenta transition hover:text-slate-100"
                :aria-label="`Retirer ${id}`"
                @click="removeCard(id)"
              >
                ×
              </button>
            </li>
            <li v-if="legendIds.length === 0" class="font-mono text-[0.65rem] text-slate-600">
              Dépose ici tes {{ REQUIRED_LEGENDS }} Legends
            </li>
          </ul>
        </section>

        <section
          class="cyber-panel flex min-h-[12rem] flex-1 flex-col p-4"
          :class="dropActive === 'main' ? 'border-cyber-cyan shadow-[0_0_22px_rgba(5,217,232,0.35)]' : ''"
          @dragover.prevent="dropActive = 'main'"
          @dragleave="dropActive = null"
          @drop="onDrop"
        >
          <h2 class="cyber-title text-sm text-cyber-cyan">
            Deck · {{ decks.mainCount }}<span class="text-slate-500">/{{ REQUIRED_NON_LEGENDS }} min</span>
          </h2>
          <ul class="cyber-scroll mt-2 max-h-[22rem] flex-1 overflow-y-auto pr-1">
            <li
              v-for="entry in decks.groupedDeck.filter((item) => item.card?.type !== 'legend')"
              :key="entry.id"
              class="flex items-center justify-between gap-2 border-b border-cyber-line/50 py-1"
            >
              <span class="truncate font-mono text-[0.68rem] text-slate-300">
                {{ entry.card?.name ?? entry.id }}
                <span class="text-slate-500">{{ entry.card?.type }}</span>
              </span>
              <button
                type="button"
                class="font-mono text-xs text-cyber-magenta transition hover:text-slate-100"
                :aria-label="`Retirer ${entry.id}`"
                @click="removeCard(entry.id)"
              >
                ×
              </button>
            </li>
          </ul>
          <p v-if="mainIds.length === 0" class="mt-2 font-mono text-[0.65rem] text-slate-600">
            Dépose ici Units, Programs et Gears.
          </p>
        </section>

        <section class="cyber-panel p-4">
          <h2 class="cyber-title text-sm" :class="decks.isValid ? 'text-cyber-green' : 'text-cyber-yellow'">
            {{ decks.isValid ? 'Deck valide' : 'Deck incomplet' }}
          </h2>
          <ul class="mt-2 flex flex-col gap-1">
            <li v-for="problem in decks.problems" :key="problem" class="font-mono text-[0.65rem] text-cyber-yellow">
              • {{ problem }}
            </li>
            <li v-if="decks.isValid" class="font-mono text-[0.65rem] text-cyber-green">
              Prêt pour le lobby ({{ decks.deck.length }} cartes).
            </li>
          </ul>
          <p v-if="decks.duplicates.length" class="mt-2 font-mono text-[0.62rem] text-cyber-magenta">
            Doublons : {{ decks.duplicates.length }}
          </p>
        </section>
      </aside>
    </div>

    <!-- Aperçu -->
    <section v-if="previewCard" class="cyber-panel max-w-md p-4">
      <h2 class="cyber-title mb-2 text-xs text-cyber-cyan">Aperçu</h2>
      <CardPreview :card="previewCard" />
    </section>
  </div>
</template>
