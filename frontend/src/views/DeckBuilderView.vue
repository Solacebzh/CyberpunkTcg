<script setup lang="ts">
/**
 * Deck builder officiel (Mini-Feature 8) :
 * - Catalogue `GET /api/cards` + ajout par clic ou glisser-déposer.
 * - Validation en temps réel selon les règles officielles (3 Legends uniques,
 *   Main Deck 40-50, max 3 copies, plafonds RAM par couleur).
 * - Importation textuelle avec modale ([Quantité] [Nom de la carte], commentaires ignorés).
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import CardPreview from '@/components/CardPreview.vue'
import {
  MAIN_DECK_MAX,
  MAIN_DECK_MIN,
  MAX_COPIES_PER_CARD,
  REQUIRED_LEGENDS,
  useDeckStore,
} from '@/stores/deck'
import { useUiStore } from '@/stores/ui'
import { CARD_COLOR_LABELS, CARD_TYPE_LABELS, type CardColor, type CardType } from '@/types/card'

const router = useRouter()
const decks = useDeckStore()
const ui = useUiStore()

const previewId = ref<string | null>(null)
const dropActive = ref<'legends' | 'main' | null>(null)

// --- État de la modale d'import ---
const showImportModal = ref(false)
const importText = ref('')
const importWarnings = ref<string[]>([])

const TYPES: Array<CardType | 'all'> = ['all', 'legend', 'unit', 'program', 'gear']
const COLORS: Array<CardColor | 'all'> = ['all', 'red', 'green', 'blue', 'yellow']

const legendIds = computed(() => decks.deck.filter((id) => decks.byId.get(id)?.type === 'legend'))
const mainCards = computed(() => decks.groupedDeck.filter((item) => item.card?.type !== 'legend'))
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
  const card = decks.byId.get(cardId)
  if (!card) return

  if (decks.canAdd(cardId)) {
    decks.add(cardId)
    ui.success(`${card.name} ajouté au deck`)
    return
  }

  // Diagnostics explicites en cas de refus d'ajout
  if (card.type === 'legend') {
    if (decks.legendCount >= REQUIRED_LEGENDS) {
      ui.warn(`Déjà ${REQUIRED_LEGENDS} Legends dans le deck`)
    } else {
      ui.warn(`${card.name} est déjà dans le deck (les Legends doivent être uniques)`)
    }
  } else {
    const currentCopies = decks.deck.filter((id) => id === cardId).length
    if (currentCopies >= MAX_COPIES_PER_CARD) {
      ui.warn(`Maximum ${MAX_COPIES_PER_CARD} copies atteint pour ${card.name}`)
    } else {
      ui.warn('Ajout impossible')
    }
  }
}

function removeCard(cardId: string): void {
  if (decks.remove(cardId)) {
    ui.info(`${decks.byId.get(cardId)?.name ?? cardId} retiré du deck`)
  }
}

function sample(): void {
  decks.buildSampleDeck()
  ui.success('Deck d’exemple officiel généré depuis le catalogue')
}

function openImportModal(): void {
  importText.value = ''
  importWarnings.value = []
  showImportModal.value = true
}

function executeImport(): void {
  if (!importText.value.trim()) return

  const result = decks.importFromText(importText.value)
  importWarnings.value = result.unknownLines

  if (result.totalAdded > 0) {
    if (result.unknownLines.length === 0) {
      ui.success(`Deck importé avec succès : ${result.totalAdded} cartes ajoutées`)
      showImportModal.value = false
    } else {
      ui.warn(
        `Deck importé partiellement : ${result.totalAdded} cartes ajoutées, ${result.unknownLines.length} ligne(s) non reconnue(s)`,
      )
    }
  } else {
    ui.error('Aucune carte reconnue dans le texte fourni')
  }
}
</script>

<template>
  <div class="flex flex-col gap-5">
    <header class="cyber-panel flex flex-wrap items-end justify-between gap-4 p-5">
      <div>
        <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-magenta">// deck builder</p>
        <h1 class="cyber-title mt-1 text-2xl text-slate-50">Construis ton deck</h1>
        <p class="mt-2 max-w-2xl text-xs text-slate-400">
          Règles officielles : exactement {{ REQUIRED_LEGENDS }} Legends uniques, entre {{ MAIN_DECK_MIN }} et
          {{ MAIN_DECK_MAX }} cartes Main Deck (max {{ MAX_COPIES_PER_CARD }} exemplaires), respect strict des
          plafonds de RAM par couleur.
        </p>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <button type="button" class="cyber-btn cyber-btn--cyan" @click="openImportModal">
          Importer un Deck
        </button>
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
    <p
      v-else-if="decks.catalogState === 'error'"
      class="cyber-panel border-cyber-magenta/60 p-4 text-xs text-cyber-magenta"
    >
      Catalogue indisponible : {{ decks.catalogError }} — le backend doit tourner sur
      <span class="font-mono">/api/cards</span>.
      <button type="button" class="cyber-btn ml-3" @click="decks.loadCatalog(true)">Réessayer</button>
    </p>

    <div class="grid gap-4 lg:grid-cols-[minmax(0,1fr)_24rem]">
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

        <div class="cyber-scroll mt-3 grid max-h-[36rem] gap-3 overflow-y-auto pr-1 sm:grid-cols-2 xl:grid-cols-3">
          <div
            v-for="card in decks.filteredCards"
            :key="card.id"
            draggable="true"
            class="cursor-grab transition active:cursor-grabbing"
            :class="!decks.canAdd(card.id) ? 'opacity-50' : 'hover:-translate-y-0.5'"
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

      <!-- Deck & Validation -->
      <aside class="flex flex-col gap-3">
        <!-- Zone Legends -->
        <section
          class="cyber-panel p-4"
          :class="dropActive === 'legends' ? 'border-cyber-yellow shadow-[0_0_22px_rgba(252,238,10,0.35)]' : ''"
          @dragover.prevent="dropActive = 'legends'"
          @dragleave="dropActive = null"
          @drop="onDrop"
        >
          <div class="flex items-center justify-between">
            <h2 class="cyber-title text-sm text-cyber-yellow">
              Legends · {{ decks.legendCount }}/{{ REQUIRED_LEGENDS }}
            </h2>
            <span
              v-if="decks.legendCount === REQUIRED_LEGENDS && decks.legendDuplicates.length === 0"
              class="font-mono text-[0.65rem] text-cyber-green font-bold"
            >
              ✓ OK
            </span>
          </div>

          <ul class="mt-2 flex flex-col gap-1">
            <li
              v-for="id in legendIds"
              :key="id"
              class="flex items-center justify-between gap-2 rounded border border-cyber-line px-2 py-1 bg-black/30"
            >
              <div class="truncate text-xs text-slate-200">
                <span class="font-bold">{{ decks.byId.get(id)?.name ?? id }}</span>
                <span v-if="decks.byId.get(id)?.subtitle" class="text-slate-400 text-[0.68rem] block">
                  {{ decks.byId.get(id)?.subtitle }}
                </span>
              </div>
              <div class="flex items-center gap-2">
                <span class="font-mono text-[0.65rem] text-cyber-cyan">
                  +{{ decks.byId.get(id)?.ram ?? 0 }} {{ CARD_COLOR_LABELS[decks.byId.get(id)?.color as CardColor] ?? '' }}
                </span>
                <button
                  type="button"
                  class="font-mono text-xs text-cyber-magenta transition hover:text-slate-100"
                  :aria-label="`Retirer ${id}`"
                  @click="removeCard(id)"
                >
                  ×
                </button>
              </div>
            </li>
            <li v-if="legendIds.length === 0" class="font-mono text-[0.65rem] text-slate-600">
              Dépose ici tes {{ REQUIRED_LEGENDS }} Legends uniques
            </li>
          </ul>
        </section>

        <!-- Zone Main Deck -->
        <section
          class="cyber-panel flex min-h-[12rem] flex-1 flex-col p-4"
          :class="dropActive === 'main' ? 'border-cyber-cyan shadow-[0_0_22px_rgba(5,217,232,0.35)]' : ''"
          @dragover.prevent="dropActive = 'main'"
          @dragleave="dropActive = null"
          @drop="onDrop"
        >
          <div class="flex items-center justify-between">
            <h2 class="cyber-title text-sm text-cyber-cyan">
              Main Deck · {{ decks.mainCount }}<span class="text-slate-500">/{{ MAIN_DECK_MIN }}-{{ MAIN_DECK_MAX }}</span>
            </h2>
            <span
              v-if="decks.mainCount >= MAIN_DECK_MIN && decks.mainCount <= MAIN_DECK_MAX"
              class="font-mono text-[0.65rem] text-cyber-green font-bold"
            >
              ✓ OK
            </span>
          </div>

          <ul class="cyber-scroll mt-2 max-h-[18rem] flex-1 overflow-y-auto pr-1">
            <li
              v-for="entry in mainCards"
              :key="entry.id"
              class="flex items-center justify-between gap-2 border-b border-cyber-line/50 py-1 hover:bg-white/[0.02] px-1"
            >
              <span class="truncate font-mono text-[0.68rem] text-slate-300">
                <span class="font-bold text-cyber-cyan mr-1">{{ entry.count }}x</span>
                {{ entry.card?.name ?? entry.id }}
                <span class="text-slate-500 text-[0.62rem]">({{ entry.card?.type }})</span>
              </span>
              <div class="flex items-center gap-1">
                <button
                  type="button"
                  class="rounded bg-black/40 px-1 font-mono text-[0.65rem] text-slate-300 hover:text-cyber-cyan transition disabled:opacity-30"
                  :disabled="entry.count >= MAX_COPIES_PER_CARD"
                  :title="`Ajouter un exemplaire (max ${MAX_COPIES_PER_CARD})`"
                  @click="decks.add(entry.id)"
                >
                  +
                </button>
                <button
                  type="button"
                  class="rounded bg-black/40 px-1 font-mono text-[0.65rem] text-slate-300 hover:text-cyber-magenta transition"
                  :title="`Retirer un exemplaire`"
                  @click="decks.remove(entry.id)"
                >
                  -
                </button>
              </div>
            </li>
          </ul>
          <p v-if="mainCards.length === 0" class="mt-2 font-mono text-[0.65rem] text-slate-600">
            Dépose ici Units, Programs et Gears (40 à 50 cartes).
          </p>
        </section>

        <!-- Validation en temps réel & Plafonds RAM -->
        <section
          class="cyber-panel p-4"
          :class="decks.isValid ? 'border-cyber-green/50' : 'border-cyber-yellow/50'"
        >
          <!-- En-tête de validation temps réel -->
          <div class="flex items-center justify-between gap-2">
            <h2
              class="cyber-title text-sm truncate"
              :class="decks.isValid ? 'text-cyber-green' : 'text-cyber-yellow'"
            >
              {{ decks.isValid ? 'Deck Valide' : decks.validationStatus }}
            </h2>
            <span
              class="shrink-0 rounded px-2 py-0.5 font-mono text-[0.65rem] font-bold uppercase tracking-wider"
              :class="decks.isValid ? 'bg-cyber-green/20 text-cyber-green' : 'bg-cyber-yellow/20 text-cyber-yellow'"
            >
              {{ decks.isValid ? 'VALIDE' : 'INVALIDE' }}
            </span>
          </div>

          <!-- Plafonds de RAM par couleur -->
          <div class="mt-3 rounded border border-cyber-line/50 bg-black/40 p-2">
            <p class="font-mono text-[0.62rem] text-slate-400 font-bold uppercase tracking-wide">
              Plafonds RAM par couleur (Legends) :
            </p>
            <div class="mt-1 grid grid-cols-4 gap-1 text-center font-mono">
              <div class="rounded border border-red-500/30 bg-red-950/20 p-1 text-red-300">
                <span class="block text-[0.58rem] text-red-400 uppercase">Rouge</span>
                <span class="font-bold text-xs">{{ decks.ramCeilings.red }}</span>
              </div>
              <div class="rounded border border-green-500/30 bg-green-950/20 p-1 text-green-300">
                <span class="block text-[0.58rem] text-green-400 uppercase">Vert</span>
                <span class="font-bold text-xs">{{ decks.ramCeilings.green }}</span>
              </div>
              <div class="rounded border border-blue-500/30 bg-blue-950/20 p-1 text-blue-300">
                <span class="block text-[0.58rem] text-blue-400 uppercase">Bleu</span>
                <span class="font-bold text-xs">{{ decks.ramCeilings.blue }}</span>
              </div>
              <div class="rounded border border-yellow-500/30 bg-yellow-950/20 p-1 text-yellow-300">
                <span class="block text-[0.58rem] text-yellow-400 uppercase">Jaune</span>
                <span class="font-bold text-xs">{{ decks.ramCeilings.yellow }}</span>
              </div>
            </div>
          </div>

          <!-- Liste des infractions détectées -->
          <ul v-if="!decks.isValid && decks.problems.length > 0" class="cyber-scroll mt-3 max-h-36 overflow-y-auto flex flex-col gap-1 pr-1">
            <li
              v-for="problem in decks.problems"
              :key="problem"
              class="font-mono text-[0.65rem] text-cyber-yellow"
            >
              • {{ problem }}
            </li>
          </ul>

          <p v-else-if="decks.isValid" class="mt-3 font-mono text-[0.65rem] text-cyber-green">
            ✓ Deck légal et prêt pour le jeu ({{ decks.legendCount }} Legends + {{ decks.mainCount }} cartes = {{ decks.deck.length }} total).
          </p>
        </section>
      </aside>
    </div>

    <!-- Aperçu de carte survolée -->
    <section v-if="previewCard" class="cyber-panel max-w-md p-4">
      <h2 class="cyber-title mb-2 text-xs text-cyber-cyan">Aperçu</h2>
      <CardPreview :card="previewCard" />
    </section>

    <!-- Modale d'importation de deck en format texte -->
    <div
      v-if="showImportModal"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4"
      @click.self="showImportModal = false"
    >
      <div class="cyber-panel w-full max-w-xl p-6 border-cyber-cyan shadow-[0_0_30px_rgba(5,217,232,0.3)] flex flex-col gap-3">
        <div class="flex items-center justify-between pb-2 border-b border-cyber-line">
          <div>
            <p class="font-mono text-xs uppercase tracking-widest text-cyber-cyan">// Import textuel</p>
            <h2 class="cyber-title text-lg text-slate-100">Importer un Deck</h2>
          </div>
          <button
            type="button"
            class="font-mono text-lg text-slate-400 hover:text-cyber-magenta transition"
            @click="showImportModal = false"
          >
            ×
          </button>
        </div>

        <p class="text-xs text-slate-300">
          Colle ta liste au format <span class="font-mono text-cyber-yellow">[Quantité] [Nom de la carte]</span>.
          Les commentaires (<span class="font-mono text-slate-400">//</span> ou <span class="font-mono text-slate-400">#</span>)
          et lignes vides sont ignorés.
        </p>

        <textarea
          v-model="importText"
          rows="10"
          placeholder="// Legends (3)
1 Adam Smasher - Ender of Legends
1 Johnny Silverhand - Rocking Renegade
1 Royce - Psycho on the Edge

// Main deck (40)
3 The Heist
3 6th Street Recruits
3 Corporate Surveillance
..."
          class="cyber-scroll w-full rounded border border-cyber-line bg-black/60 p-3 font-mono text-xs text-slate-200 outline-none transition focus:border-cyber-cyan"
        ></textarea>

        <div
          v-if="importWarnings.length > 0"
          class="rounded border border-cyber-magenta/40 bg-cyber-magenta/10 p-2 font-mono text-xs text-cyber-magenta"
        >
          <p class="font-bold">Lignes non reconnues (ignorées) :</p>
          <ul class="cyber-scroll list-disc list-inside mt-1 max-h-20 overflow-y-auto">
            <li v-for="(warn, idx) in importWarnings" :key="idx">{{ warn }}</li>
          </ul>
        </div>

        <div class="mt-2 flex justify-end gap-2">
          <button type="button" class="cyber-btn" @click="showImportModal = false">
            Annuler
          </button>
          <button
            type="button"
            class="cyber-btn cyber-btn--accent"
            :disabled="!importText.trim()"
            @click="executeImport"
          >
            Importer
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
