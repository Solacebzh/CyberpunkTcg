<script setup lang="ts">
/**
 * Écran de partie : plateau 2 joueurs, compteur de Gigs (objectif 7), phase,
 * fin de tour, vente, ciblage et animations.
 *
 * Le frontend est passif : tout ce qui est affiché vient de la dernière `STATE`
 * reçue (`gameStore.state`), et chaque bouton envoie une intention au serveur.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import GameOverOverlay from '@/components/game/GameOverOverlay.vue'
import GameLogPanel from '@/components/game/GameLogPanel.vue'
import PhaseIndicator from '@/components/game/PhaseIndicator.vue'
import PlayerArea from '@/components/game/PlayerArea.vue'
import TargetingOverlay from '@/components/TargetingOverlay.vue'
import { useGameAnimations } from '@/composables/useGameAnimations'
import { useConnectionStore } from '@/stores/connection'
import { useDeckStore } from '@/stores/deck'
import { useGameStore } from '@/stores/game'
import { useLobbyStore } from '@/stores/lobby'
import { useUiStore } from '@/stores/ui'
import type { CardInstance } from '@/types/game'

const route = useRoute()
const router = useRouter()
const game = useGameStore()
const lobby = useLobbyStore()
const ui = useUiStore()
const connection = useConnectionStore()
const decks = useDeckStore()

const board = ref<HTMLElement | null>(null)
const animations = useGameAnimations(board)

const gameId = computed(() => (route.params.gameId as string | undefined) ?? lobby.gameId)

const me = computed(() => game.me)
const opponent = computed(() => game.opponent)

/** Cartes sur lesquelles un clic a un sens pour moi. */
const actionableIds = computed<string[]>(() => {
  const player = me.value
  if (!player || game.isGameOver) return []
  const ids: string[] = []

  for (const card of player.hand) {
    if (game.canPlayCard(card) === null) ids.push(card.instanceId)
  }
  for (const card of player.legendsArea) {
    if (game.canPlayCard(card) === null) ids.push(card.instanceId)
  }
  for (const card of player.field) {
    if (card.type === 'unit' && !card.attachedTo && game.canAttackWith(card) === null) ids.push(card.instanceId)
  }
  return ids
})

const selected = computed(() => game.selectedCard)
const selectedReason = computed(() => (selected.value ? game.canPlayCard(selected.value) : null))
const selectedIsFieldUnit = computed(
  () => selected.value?.zone === 'FIELD' && selected.value.type === 'unit' && !selected.value.attachedTo,
)
const selectedAttackReason = computed(() => (selected.value ? game.canAttackWith(selected.value) : null))
const targetingCandidates = computed(() => game.targeting?.candidates ?? [])
const disconnection = computed(() => game.disconnection)

function isMine(instanceId: string): boolean {
  return !!me.value?.field.some((card) => card.instanceId === instanceId)
}

function sideOf(playerId?: string | null): 'me' | 'opponent' {
  return playerId && playerId === game.myPlayerId ? 'me' : 'opponent'
}

function onCardClick(card: CardInstance, fromMyBoard: boolean): void {
  // En mode ciblage, la cible est forcément une carte adverse (ou une Unit alliée
  // pour un Gear) : on traite donc le clic avant le filtre « mon plateau ».
  if (game.targeting) {
    game.chooseTarget(card.instanceId)
    return
  }
  if (!fromMyBoard) return
  // Second clic sur une carte déjà sélectionnée = on la joue.
  if (game.selectedInstanceId === card.instanceId && game.canPlayCard(card) === null) {
    game.playCard(card.instanceId)
    return
  }
  game.selectCard(game.selectedInstanceId === card.instanceId ? null : card.instanceId)
}

function onPrimaryAction(): void {
  const card = selected.value
  if (!card) return

  if (card.zone === 'HAND') {
    if (card.type === 'gear') game.beginEquip(card.instanceId)
    else game.playCard(card.instanceId)
    return
  }
  if (card.zone === 'LEGENDS_AREA') {
    game.playCard(card.instanceId)
    return
  }
  if (selectedIsFieldUnit.value) game.beginAttack(card.instanceId)
}

const primaryLabel = computed(() => {
  const card = selected.value
  if (!card) return 'Aucune carte sélectionnée'
  if (card.zone === 'HAND') return card.type === 'gear' ? 'Équiper un Gear' : 'Jouer la carte'
  if (card.zone === 'LEGENDS_AREA') return 'Retourner la Legend'
  return selectedIsFieldUnit.value ? 'Attaquer' : 'Carte en jeu'
})

const primaryDisabled = computed(() => {
  const card = selected.value
  if (!card) return true
  if (selectedIsFieldUnit.value) return selectedAttackReason.value !== null
  return selectedReason.value !== null
})

const primaryHint = computed(() => {
  const card = selected.value
  if (!card) return 'Clique une carte de ta main ou une Unit du Field'
  return selectedIsFieldUnit.value ? selectedAttackReason.value : selectedReason.value
})

// --- Animations pilotées par les différences d'état ---
watch(
  () => game.changes,
  (changes) => {
    if (!changes.appliedAt) return

    if (changes.drawn.length > 0) animations.drawCards(changes.drawn, 'me')
    if (changes.opponentDrew) animations.opponentDraw()

    const minePlayed = changes.played.filter((id) => isMine(id))
    const theirsPlayed = changes.played.filter((id) => !minePlayed.includes(id))
    if (minePlayed.length > 0) animations.playCards(minePlayed, 'me')
    if (theirsPlayed.length > 0) animations.playCards(theirsPlayed, 'opponent')

    if (changes.flipped.length > 0) animations.flipCards(changes.flipped)

    if (changes.attackerInstanceId) {
      const attackerSide = sideOf(game.lastEvents.find((event) => event.type === 'ATTACK_DECLARED')?.playerId)
      animations.attack(changes.attackerInstanceId, attackerSide)
    }

    for (const playerId of changes.gigGainers) animations.gigPulse(sideOf(playerId))
    if (changes.defeated.length > 0) {
      const defeatedSide = sideOf(game.lastEvents.find((event) => event.type === 'UNIT_DEFEATED')?.playerId)
      animations.defeat(defeatedSide)
    }
    if (changes.phaseChanged) animations.phaseSweep()
    if (changes.turnChanged) animations.turnBanner(game.isMyTurn ? 'me' : 'opponent')
  },
)

onMounted(() => {
  const id = gameId.value
  if (!id) {
    router.replace({ name: 'lobby' })
    return
  }
  connection.markChannelInUse(true)
  game.attach(id)
  void decks.loadCatalog()
})

onBeforeUnmount(() => {
  connection.markChannelInUse(false)
  game.detach()
})

function backToLobby(): void {
  lobby.leaveLocalRoom()
  void router.push({ name: 'lobby' })
}

function onConcede(): void {
  if (!window.confirm('Abandonner la partie ? Ton adversaire gagne immédiatement.')) return
  game.concede()
  ui.warn('Abandon envoyé au serveur')
}
</script>

<template>
  <div v-if="!me" class="cyber-panel flex flex-col items-center gap-3 p-10 text-center">
    <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-cyan">// synchronisation</p>
    <h1 class="cyber-title text-2xl text-slate-50">Chargement de la partie…</h1>
    <p class="font-mono text-xs text-slate-400">
      En attente de la première <span class="text-cyber-cyan">STATE</span> du serveur (resync en cours).
    </p>
    <button type="button" class="cyber-btn" @click="backToLobby">Retour au lobby</button>
  </div>

  <div v-else ref="board" class="flex flex-col gap-3">
    <PhaseIndicator
      :phase="game.phase"
      :turn-number="game.turnNumber"
      :is-my-turn="game.isMyTurn"
      :active-player-name="game.activePlayerId ?? '—'"
      :game-over="game.isGameOver"
      :waiting="game.waitingForServer"
    />

    <div class="grid gap-3 xl:grid-cols-[minmax(0,1fr)_19rem]">
      <div class="flex min-w-0 flex-col gap-3">
        <PlayerArea
          v-if="opponent"
          :player="opponent"
          :is-me="false"
          :is-active="game.activePlayerId === opponent.playerId"
          :definitions="decks.byId"
          :selected-instance-id="null"
          :targetable-ids="targetingCandidates"
          :actionable-ids="[]"
          :interactive="false"
          :disconnection="disconnection && disconnection.playerId === opponent.playerId ? disconnection : null"
          @card-click="(card) => onCardClick(card, false)"
        />

        <!-- Barre d'action -->
        <section class="cyber-panel flex flex-wrap items-center gap-x-4 gap-y-2 px-3 py-2.5">
          <div data-anim="turn-banner" data-side="me" class="sr-only" aria-live="polite" />

          <div class="min-w-[12rem] flex-1">
            <p class="font-mono text-[0.6rem] uppercase tracking-[0.25em] text-slate-500">Sélection</p>
            <p class="text-sm font-semibold text-slate-100">
              {{ selected ? selected.name : 'Aucune carte' }}
              <span v-if="selected" class="font-mono text-[0.65rem] text-slate-500">
                · {{ selected.type }} · coût {{ selected.cost }}
                <template v-if="selected.power !== undefined"> · PWR {{ selected.power }}</template>
              </span>
            </p>
            <p class="font-mono text-[0.62rem]" :class="primaryHint ? 'text-cyber-yellow' : 'text-slate-500'">
              {{ primaryHint ?? 'Second clic sur la carte = jouer · Échap pour annuler un ciblage' }}
            </p>
          </div>

          <div class="flex flex-wrap items-center gap-2">
            <button
              type="button"
              class="cyber-btn cyber-btn--accent"
              :disabled="primaryDisabled || game.waitingForServer"
              @click="onPrimaryAction"
            >
              {{ primaryLabel }}
            </button>

            <button
              type="button"
              class="cyber-btn"
              :disabled="!selected || selected.zone !== 'HAND' || !game.canSell || game.waitingForServer"
              :title="game.canSell ? 'Vendre rapporte 1 Eddie (1 vente par tour)' : 'Vente impossible'"
              @click="selected && game.sellCard(selected.instanceId)"
            >
              Vendre (+1 ¤)
            </button>

            <button
              type="button"
              class="cyber-btn cyber-btn--green"
              :disabled="!game.canEndTurn"
              @click="game.endTurn()"
            >
              Fin de tour
            </button>

            <button type="button" class="cyber-btn cyber-btn--danger" @click="onConcede">Abandonner</button>
          </div>
        </section>

        <!-- Fenêtre de réaction -->
        <section
          v-if="game.iAmReacting"
          class="cyber-panel border-cyber-magenta/70 px-3 py-2 shadow-[0_0_22px_rgba(255,42,109,0.28)]"
        >
          <p class="font-mono text-[0.62rem] uppercase tracking-[0.25em] text-cyber-magenta">// fenêtre de réaction</p>
          <p class="text-xs text-slate-200">
            Ton adversaire attaque : tu ne peux jouer que des cartes <span class="text-cyber-yellow">QUICK</span>.
            Termine ton intervention en cliquant « Fin de tour » (le serveur ferme la fenêtre).
          </p>
        </section>

        <PlayerArea
          :player="me"
          :is-me="true"
          :is-active="game.isMyTurn"
          :definitions="decks.byId"
          :selected-instance-id="game.selectedInstanceId"
          :targetable-ids="targetingCandidates"
          :actionable-ids="actionableIds"
          :interactive="!game.isGameOver"
          :disconnection="disconnection && disconnection.playerId === me.playerId ? disconnection : null"
          @card-click="(card) => onCardClick(card, true)"
        />
      </div>

      <div class="min-h-[22rem]">
        <GameLogPanel
          :entries="game.log"
          :me-id="game.myPlayerId"
          :player-name="me.name"
          :opponent-name="opponent?.name ?? '—'"
        />
      </div>
    </div>

    <TargetingOverlay
      v-if="game.targeting"
      :kind="game.targeting.kind"
      :source-name="game.targeting.sourceName"
      :candidates="game.targeting.candidates"
      :allow-direct="game.targeting.allowDirect"
      @cancel="game.cancelTargeting()"
      @direct="game.stealGig()"
    />

    <GameOverOverlay
      v-if="game.isGameOver"
      :winner-id="game.winnerId"
      :end-reason="game.endReason"
      :i-won="game.iWon"
      :player-name="me.name"
      :opponent-name="opponent?.name ?? '—'"
      @leave="backToLobby"
    />
  </div>
</template>
