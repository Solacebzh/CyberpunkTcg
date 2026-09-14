<script setup lang="ts">
/**
 * Lobby : pseudo, création/join de salon, attente du second joueur.
 *
 * Tout passe par le canal STOMP (doc §4) : `SEND /app/lobby.create|join|leave|list`
 * puis écoute de `LOBBY_STATE` (privé + topic du salon) et `ROOMS`. Dès que le
 * salon passe en `PLAYING`, le `gameId` est récupéré et l'écran de jeu prend le
 * relais (aucune logique de jeu ici).
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'

import { REQUIRED_LEGENDS, REQUIRED_NON_LEGENDS, useDeckStore } from '@/stores/deck'
import { useLobbyStore } from '@/stores/lobby'
import { useUiStore } from '@/stores/ui'

const router = useRouter()
const lobby = useLobbyStore()
const decks = useDeckStore()
const ui = useUiStore()

const { status, room, rooms, gameId, error, busy, isConnected, pseudo, isHost, opponent, deckCardCount } =
  storeToRefs(lobby)

const pseudoDraft = ref(pseudo.value)
const pseudoInvalid = ref(false)

const statusLabel = computed(() => {
  switch (status.value) {
    case 'connecting':
      return 'Connexion au serveur…'
    case 'waiting':
      return 'Salon ouvert — en attente du second joueur'
    case 'starting':
      return 'Démarrage de la partie…'
    case 'in-game':
      return 'Partie en cours'
    case 'ready':
      return 'Canal prêt'
    default:
      return 'Canal fermé'
  }
})

const seatLabels = computed(() => (room.value ? ['Hôte (siège 0)', 'Invité (siège 1)'] : []))

function applyPseudo(): boolean {
  const ok = lobby.setPseudo(pseudoDraft.value)
  pseudoInvalid.value = !ok
  if (ok) pseudoDraft.value = lobby.pseudo
  return ok
}

function connect(): void {
  if (!applyPseudo()) return
  lobby.connect()
}

function createRoom(): void {
  if (!applyPseudo()) return
  if (!lobby.createRoom()) return
  ui.info('Salon créé — partage le code à ton adversaire')
}

function joinRoom(code?: string): void {
  if (!applyPseudo()) return
  if (!lobby.joinRoom(code)) return
}

function leave(): void {
  lobby.leaveRoom()
  ui.info('Salon quitté')
}

function goToDeckBuilder(): void {
  void router.push({ name: 'deck' })
}

watch(gameId, (id) => {
  if (id) void router.push({ name: 'game', params: { gameId: id } })
})

onMounted(() => {
  lobby.init()
  void decks.loadCatalog()
  if (isConnected.value) lobby.refreshRooms()

  // Reprise après rechargement de page (doc §8 : le client conserve son état).
  const stored = lobby.resumeStoredGame()
  if (stored) ui.info('Partie précédente retrouvée — resynchronisation…')
})
</script>

<template>
  <div class="flex flex-col gap-6">
    <!-- Identité + canal -->
    <section class="cyber-panel p-6">
      <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-magenta">// feature 05</p>
      <h1 class="cyber-title mt-2 text-2xl text-slate-50 md:text-3xl">Lobby temps réel</h1>
      <p class="mt-3 max-w-2xl text-sm leading-relaxed text-slate-300">
        Le pseudo devient ton identité STOMP (en-tête <span class="font-mono text-cyber-cyan">pseudo</span> du
        CONNECT) : il n'est jamais envoyé dans les payloads, le serveur fait autorité.
      </p>

      <div class="mt-5 flex flex-wrap items-end gap-3">
        <label class="flex flex-col gap-1">
          <span class="font-mono text-[0.65rem] uppercase tracking-widest text-slate-400">Pseudo</span>
          <input
            v-model="pseudoDraft"
            type="text"
            maxlength="20"
            class="w-52 rounded border bg-black/40 px-3 py-2 font-mono text-sm text-slate-100 outline-none transition focus:border-cyber-cyan"
            :class="pseudoInvalid ? 'border-cyber-magenta' : 'border-cyber-line'"
            placeholder="Johnny"
            @keyup.enter="connect"
          />
        </label>

        <button type="button" class="cyber-btn" :disabled="isConnected" @click="connect">
          {{ isConnected ? 'Canal ouvert' : 'Ouvrir le canal' }}
        </button>

        <span class="cyber-chip" :class="isConnected ? 'border-cyber-green/60 text-cyber-green' : 'text-slate-400'">
          {{ statusLabel }}
        </span>
      </div>

      <p v-if="pseudoInvalid" class="mt-2 font-mono text-[0.68rem] text-cyber-magenta">
        Pseudo invalide : 2 à 20 caractères (lettres, chiffres, _ et -).
      </p>
      <p v-else-if="error" class="mt-2 font-mono text-[0.68rem] text-cyber-magenta">{{ error }}</p>
    </section>

    <!-- Deck -->
    <section class="cyber-panel flex flex-wrap items-center gap-x-6 gap-y-3 p-5">
      <div>
        <h2 class="cyber-title text-sm text-cyber-cyan">Deck</h2>
        <p class="font-mono text-[0.65rem] text-slate-400">
          Règles serveur : exactement {{ REQUIRED_LEGENDS }} Legends, au moins {{ REQUIRED_NON_LEGENDS }} cartes
          non-Legend, aucun doublon.
        </p>
      </div>

      <label class="flex items-center gap-2 font-mono text-[0.7rem] text-slate-300">
        <input v-model="lobby.useCustomDeck" type="checkbox" class="h-4 w-4 accent-cyber-cyan" />
        Utiliser mon deck ({{ deckCardCount ?? 0 }} cartes)
      </label>

      <p class="font-mono text-[0.65rem]" :class="decks.isValid ? 'text-cyber-green' : 'text-cyber-yellow'">
        {{ decks.legendCount }}/{{ REQUIRED_LEGENDS }} Legends · {{ decks.mainCount }} autres
        <template v-if="!decks.isValid && lobby.useCustomDeck"> — deck incomplet</template>
      </p>

      <button type="button" class="cyber-btn" @click="goToDeckBuilder">Ouvrir le deck builder</button>
    </section>

    <!-- Salon en attente -->
    <section v-if="room" class="cyber-panel border-cyber-cyan/60 p-6 shadow-[0_0_28px_rgba(5,217,232,0.18)]">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p class="font-mono text-[0.65rem] uppercase tracking-[0.3em] text-cyber-cyan">// salon</p>
          <h2 class="cyber-title mt-1 text-xl text-slate-50">{{ room.name }}</h2>
          <p class="font-mono text-[0.7rem] text-slate-400">
            statut {{ room.status }} · hôte {{ room.hostPseudo }}
            <span v-if="isHost"> (toi)</span>
          </p>
        </div>

        <div class="text-right">
          <p class="font-mono text-[0.6rem] uppercase tracking-widest text-slate-500">Code d'invitation</p>
          <p class="font-mono text-3xl font-bold tracking-[0.35em] text-cyber-yellow">{{ room.code }}</p>
        </div>
      </div>

      <ul class="mt-5 grid gap-2 md:grid-cols-2">
        <li
          v-for="seat in [0, 1]"
          :key="seat"
          class="flex items-center justify-between rounded border px-3 py-2"
          :class="room.players[seat] ? 'border-cyber-green/50 bg-cyber-green/5' : 'border-dashed border-cyber-line'"
        >
          <span class="font-mono text-[0.65rem] uppercase tracking-widest text-slate-500">{{ seatLabels[seat] }}</span>
          <span v-if="room.players[seat]" class="text-sm font-semibold text-slate-100">
            {{ room.players[seat]?.pseudo }}
            <span class="font-mono text-[0.65rem] text-slate-400">
              · {{ room.players[seat]?.deckCardCount }} cartes
            </span>
          </span>
          <span v-else class="font-mono text-[0.7rem] text-slate-600">
            {{ seat === 1 ? 'en attente du 2e joueur…' : 'libre' }}
          </span>
        </li>
      </ul>

      <div class="mt-5 flex flex-wrap items-center gap-3">
        <span v-if="busy" class="cyber-chip border-cyber-yellow/60 text-cyber-yellow animate-pulse">
          échange avec le serveur…
        </span>
        <span v-else-if="room.status === 'WAITING'" class="cyber-chip text-slate-400">
          La partie démarre dès que le second joueur s'assied.
        </span>
        <button type="button" class="cyber-btn cyber-btn--danger" :disabled="busy" @click="leave">Quitter le salon</button>
        <button type="button" class="cyber-btn" @click="lobby.refreshRooms()">Rafraîchir les salons</button>
      </div>

      <p v-if="opponent" class="mt-3 font-mono text-[0.68rem] text-slate-400">
        Adversaire : <span class="text-cyber-magenta">{{ opponent.pseudo }}</span> (siège {{ opponent.seat }})
      </p>
    </section>

    <!-- Créer / rejoindre -->
    <div v-else class="grid gap-4 md:grid-cols-2">
      <section class="cyber-panel p-5">
        <h2 class="cyber-title text-sm text-cyber-cyan">Créer un salon</h2>
        <p class="mt-2 text-xs text-slate-400">
          Tu occupies le siège 0 (hôte) et commences la partie.
        </p>
        <label class="mt-4 flex flex-col gap-1">
          <span class="font-mono text-[0.65rem] uppercase tracking-widest text-slate-400">Nom du salon</span>
          <input
            v-model="lobby.roomNameDraft"
            type="text"
            maxlength="40"
            class="rounded border border-cyber-line bg-black/40 px-3 py-2 text-sm text-slate-100 outline-none transition focus:border-cyber-cyan"
            :placeholder="`Salon de ${pseudo}`"
            @keyup.enter="createRoom"
          />
        </label>
        <button type="button" class="cyber-btn cyber-btn--accent mt-4" :disabled="busy" @click="createRoom">
          Créer le salon
        </button>
      </section>

      <section class="cyber-panel p-5">
        <h2 class="cyber-title text-sm text-cyber-cyan">Rejoindre un salon</h2>
        <p class="mt-2 text-xs text-slate-400">Code à 6 caractères, insensible à la casse.</p>
        <label class="mt-4 flex flex-col gap-1">
          <span class="font-mono text-[0.65rem] uppercase tracking-widest text-slate-400">Code</span>
          <input
            v-model="lobby.roomCodeDraft"
            type="text"
            maxlength="6"
            class="rounded border border-cyber-line bg-black/40 px-3 py-2 font-mono text-sm uppercase tracking-[0.3em] text-slate-100 outline-none transition focus:border-cyber-cyan"
            placeholder="6SQX4Z"
            @keyup.enter="joinRoom()"
          />
        </label>
        <button type="button" class="cyber-btn cyber-btn--accent mt-4" :disabled="busy" @click="joinRoom()">
          Rejoindre
        </button>
      </section>
    </div>

    <!-- Salons ouverts -->
    <section class="cyber-panel p-5">
      <header class="flex items-center justify-between">
        <h2 class="cyber-title text-sm text-cyber-cyan">Salons en attente</h2>
        <button type="button" class="cyber-btn" @click="lobby.refreshRooms()">Rafraîchir</button>
      </header>

      <p v-if="rooms.length === 0" class="mt-3 font-mono text-[0.7rem] text-slate-500">
        Aucun salon ouvert pour l'instant — crée le tien.
      </p>
      <ul v-else class="mt-3 grid gap-2 md:grid-cols-2">
        <li
          v-for="entry in rooms"
          :key="entry.code"
          class="flex flex-wrap items-center justify-between gap-3 rounded border border-cyber-line px-3 py-2"
        >
          <div>
            <p class="text-sm font-semibold text-slate-100">{{ entry.name }}</p>
            <p class="font-mono text-[0.65rem] text-slate-500">
              {{ entry.code }} · hôte {{ entry.hostPseudo }} · {{ entry.playerCount }}/2
            </p>
          </div>
          <button
            type="button"
            class="cyber-btn"
            :disabled="busy || entry.playerCount >= 2"
            @click="joinRoom(entry.code)"
          >
            Rejoindre
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>
