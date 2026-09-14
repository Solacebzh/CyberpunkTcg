<script setup lang="ts">
/**
 * Demi-plateau d'un joueur : Legends, Field (+ Gears attachés), main, pioche,
 * défausse, Eddies et Gigs.
 *
 * Le même composant sert pour moi et pour l'adversaire : `isMe` pilote
 * l'affichage de la main (cartes visibles vs dos de carte) et l'interactivité.
 * Aucune règle ici : toutes les cartes de mon plateau sont cliquables (sélection,
 * inspection, vente) et `actionableIds` — fourni par l'écran de jeu d'après le
 * store — pilote uniquement le grisage des cartes inexploitables.
 */
import { computed } from 'vue'

import CardComponent from '@/components/CardComponent.vue'
import GigTracker from '@/components/game/GigTracker.vue'
import type { GameCard } from '@/types/card'
import type { CardInstance, PlayerState } from '@/types/game'

const props = withDefaults(
  defineProps<{
    player: PlayerState
    isMe: boolean
    /** Le tour actif est celui de ce joueur. */
    isActive: boolean
    definitions: Map<string, GameCard>
    selectedInstanceId: string | null
    targetableIds: string[]
    /** Cartes sur lesquelles un clic a un sens (jouer / attaquer / retourner). */
    actionableIds: string[]
    interactive: boolean
    disconnection?: { secondsLeft: number } | null
  }>(),
  { disconnection: null },
)

const emit = defineEmits<{ cardClick: [card: CardInstance] }>()

const side = computed<'me' | 'opponent'>(() => (props.isMe ? 'me' : 'opponent'))

const units = computed(() => props.player.field.filter((card) => !card.attachedTo))
const legends = computed(() => props.player.legendsArea)
const handCards = computed(() => props.player.hand)

function gearsOf(instanceId: string): CardInstance[] {
  return props.player.field.filter((card) => card.attachedTo === instanceId)
}

function definitionOf(card: CardInstance): GameCard | null {
  return props.definitions.get(card.cardId) ?? null
}

function isTargetable(instanceId: string): boolean {
  return props.targetableIds.includes(instanceId)
}

function isActionable(instanceId: string): boolean {
  return props.interactive && props.actionableIds.includes(instanceId)
}

function onCard(card: CardInstance): void {
  emit('cardClick', card)
}
</script>

<template>
  <section
    :data-side="side"
    class="cyber-panel flex flex-col gap-2 p-2.5"
    :class="[
      isActive ? 'border-cyber-cyan/60 shadow-[0_0_24px_rgba(5,217,232,0.18)]' : 'border-cyber-line',
      isMe ? '' : 'bg-black/20',
    ]"
    :aria-label="`Plateau de ${player.name}`"
  >
    <!-- Bandeau joueur -->
    <header class="flex flex-wrap items-center gap-x-3 gap-y-1">
      <h2 class="cyber-title text-sm" :class="isMe ? 'text-cyber-cyan' : 'text-cyber-magenta'">
        {{ player.name }}<span v-if="isMe" class="text-slate-500"> (toi)</span>
      </h2>

      <span
        class="cyber-chip"
        :class="player.connected ? 'border-cyber-green/60 text-cyber-green' : 'border-cyber-magenta/60 text-cyber-magenta'"
      >
        {{ player.connected ? 'connecté' : 'déconnecté' }}
      </span>
      <span
        v-if="isActive"
        class="cyber-chip border-cyber-yellow/70 text-cyber-yellow shadow-[0_0_12px_rgba(252,238,10,0.35)]"
      >
        tour actif
      </span>
      <span v-if="disconnection" class="cyber-chip border-cyber-magenta/70 text-cyber-magenta animate-pulse">
        forfait dans {{ disconnection.secondsLeft }} s
      </span>

      <div class="ml-auto flex flex-wrap items-center gap-1.5 font-mono text-[0.62rem]">
        <span data-anim="deck" :data-side="side" class="cyber-chip" title="Cartes restantes dans la pioche">
          pioche {{ player.deckCount }}
        </span>
        <span data-anim="trash" :data-side="side" class="cyber-chip" title="Défausse">
          défausse {{ player.trash.length }}
        </span>
        <span class="cyber-chip border-cyber-yellow/50 text-cyber-yellow" title="Eddies disponibles">
          ¤ {{ player.availableEddies }}<span v-if="player.costDiscount > 0" class="text-cyber-green"> −{{ player.costDiscount }}</span>
        </span>
        <span
          v-if="player.hasSoldThisTurn"
          class="cyber-chip border-cyber-green/50 text-cyber-green"
          title="Vente déjà effectuée ce tour"
        >
          vente faite
        </span>
        <span class="cyber-chip" title="Cartes vendues (Eddies Area)">
          vendues {{ player.eddiesArea.length }}
        </span>
      </div>
    </header>

    <!-- Gigs -->
    <GigTracker
      :side="side"
      :player-name="player.name"
      :gig-count="player.gigCount"
      :gigs="player.gigs"
      :fixer-dice="player.fixerDice"
      :street-cred="player.streetCred"
    />

    <div class="grid gap-2 lg:grid-cols-[minmax(0,1fr)_auto]">
      <!-- Field -->
      <div
        data-anim="field"
        :data-side="side"
        class="field-zone flex min-h-[9.5rem] flex-wrap items-start gap-2 rounded border border-dashed border-cyber-line/80 p-2"
      >
        <p v-if="units.length === 0" class="self-center font-mono text-[0.62rem] uppercase tracking-widest text-slate-600">
          Field vide
        </p>

        <div v-for="unit in units" :key="unit.instanceId" class="flex flex-col items-center gap-1">
          <CardComponent
            :card="unit"
            :definition="definitionOf(unit)"
            :side="side"
            size="sm"
            :selectable="interactive"
            :selected="selectedInstanceId === unit.instanceId"
            :targetable="isTargetable(unit.instanceId)"
            :dimmed="interactive && !isActionable(unit.instanceId) && !isTargetable(unit.instanceId)"
            @click="onCard"
          />
          <div v-if="gearsOf(unit.instanceId).length" class="flex flex-wrap justify-center gap-1">
            <CardComponent
              v-for="gear in gearsOf(unit.instanceId)"
              :key="gear.instanceId"
              :card="gear"
              :definition="definitionOf(gear)"
              :side="side"
              size="xs"
              :targetable="isTargetable(gear.instanceId)"
              @click="onCard"
            />
          </div>
        </div>
      </div>

      <!-- Legends -->
      <div class="flex flex-col gap-1">
        <p class="font-mono text-[0.55rem] uppercase tracking-[0.25em] text-slate-500">Legends</p>
        <div data-anim="legends" :data-side="side" class="flex flex-col gap-1">
          <CardComponent
            v-for="card in legends"
            :key="card.instanceId"
            :card="card"
            :definition="definitionOf(card)"
            :side="side"
            size="xs"
            :selectable="interactive"
            :selected="selectedInstanceId === card.instanceId"
            :targetable="isTargetable(card.instanceId)"
            :dimmed="interactive && !isActionable(card.instanceId)"
            @click="onCard"
          />
          <p v-if="legends.length === 0" class="font-mono text-[0.55rem] text-slate-600">—</p>
        </div>
      </div>
    </div>

    <!-- Main -->
    <div>
      <p class="mb-1 font-mono text-[0.55rem] uppercase tracking-[0.25em] text-slate-500">
        Main · {{ handCards.length }} carte(s)
      </p>
      <div data-anim="hand" :data-side="side" class="cyber-scroll flex gap-2 overflow-x-auto pb-1">
        <p v-if="handCards.length === 0" class="font-mono text-[0.6rem] text-slate-600">main vide</p>
        <CardComponent
          v-for="card in handCards"
          :key="card.instanceId"
          :card="card"
          :definition="definitionOf(card)"
          :side="side"
          :size="isMe ? 'sm' : 'xs'"
          :selectable="interactive"
          :selected="selectedInstanceId === card.instanceId"
          :targetable="isTargetable(card.instanceId)"
          :dimmed="interactive && !isActionable(card.instanceId)"
          :show-abilities="isMe"
          @click="onCard"
        />
      </div>
    </div>
  </section>
</template>
