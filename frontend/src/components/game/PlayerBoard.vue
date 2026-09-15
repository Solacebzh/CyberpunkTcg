<script setup lang="ts">
/**
 * Demi-tapis d'un joueur (soi-même ou le rival).
 *
 * La grille CSS `.playmat-grid` (assets/main.css) reproduit **exactement** la
 * disposition officielle du tapis, de gauche à droite et de bas en haut :
 *
 * ```text
 * ┌────────┬─────────────────────────┬────────┐
 * │        │  FIELD (immense)        │        │   ← centre-haut
 * │ FIXER  │                         │  DECK  │   ← DECK milieu-droite
 * │ (d20  ├────────────┬────────────┼────────┤
 * │  →d4) │  LEGENDS   │  EDDIES    │ TRASH  │   ← ligne du bas
 * └────────┴────────────┴────────────┴────────┘
 * ```
 *
 * Le bandeau **GIGS** (Rival / Friendly) est posé au-dessus des deux demi-tapis par
 * `GameView` via `GigsBar` ; la **main** est rendue sous la grille, hors tapis.
 *
 * Aucune règle ici : le composant affiche `PlayerState` (dernière `STATE` serveur) et
 * remonte les clics de carte ; `actionableIds` ne fait que griser les cartes
 * inexploitables.
 */
import { computed } from 'vue'

import CardComponent from '@/components/CardComponent.vue'
import CardPile from '@/components/game/CardPile.vue'
import FieldArea from '@/components/game/FieldArea.vue'
import FixerArea from '@/components/game/FixerArea.vue'
import HandRow from '@/components/game/HandRow.vue'
import LegendsArea from '@/components/game/LegendsArea.vue'
import PlaymatZone from '@/components/game/PlaymatZone.vue'
import type { GameCard } from '@/types/card'
import type { CardInstance, PlayerState } from '@/types/game'
import { LEGEND_SLOTS } from '@/types/playmat'

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
    /** Mini-Feature 5 : la pioche attend le clic du joueur (phase DRAW, `AWAITING_DRAW`). */
    awaitingDraw?: boolean
    /** Mini-Feature 5 : dés cliquables pendant `AWAITING_DIE_SELECT` (vide sinon). */
    selectableDice?: string[]
  }>(),
  { disconnection: null, awaitingDraw: false, selectableDice: () => [] },
)

const emit = defineEmits<{ cardClick: [card: CardInstance]; drawClick: []; selectDie: [die: string] }>()

const side = computed<'me' | 'opponent'>(() => (props.isMe ? 'me' : 'opponent'))
const units = computed(() => props.player.field.filter((card) => !card.attachedTo))
const legends = computed(() => props.player.legendsArea)

function onCard(card: CardInstance): void {
  emit('cardClick', card)
}

function definitionOf(card: CardInstance): GameCard | null {
  return props.definitions.get(card.cardId) ?? null
}

function isActionable(instanceId: string): boolean {
  return props.interactive && props.actionableIds.includes(instanceId)
}
</script>

<template>
  <section
    :data-side="side"
    :data-board="side"
    class="cyber-panel playmat-board"
    :class="[isActive ? 'playmat-board--active' : '', isMe ? '' : 'playmat-board--rival']"
    :aria-label="`Tapis de ${player.name}`"
  >
    <!-- Plaque du joueur (hors grille : chrome du tapis) -->
    <header class="playmat-board__plate">
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

      <span class="cyber-chip ml-auto border-cyber-yellow/50 text-cyber-yellow" title="Eddies disponibles">
        ¤ {{ player.availableEddies }}<span v-if="player.costDiscount > 0" class="text-cyber-green"> −{{ player.costDiscount }}</span>
      </span>
      <span
        v-if="player.hasSoldThisTurn"
        class="cyber-chip border-cyber-green/50 text-cyber-green"
        title="Vente déjà effectuée ce tour"
      >
        vente faite
      </span>
    </header>

    <!-- TAPIS : la grille officielle (Fixer | Field | Deck / Legends | Eddies | Trash) -->
    <div class="playmat-grid">
      <!-- GAUCHE : Fixer (colonne des dés d20 → d4) -->
      <PlaymatZone zone="FIXER" :side="side" :badge="`${player.fixerDice.length}/6`">
        <FixerArea
          :dice="player.fixerDice"
          :side="side"
          :selecting="isMe && interactive && selectableDice.length > 0"
          :selectable-dice="isMe && interactive ? selectableDice : []"
          @select-die="(die) => emit('selectDie', die)"
        />
      </PlaymatZone>

      <!-- CENTRE-HAUT : Field (immense) -->
      <PlaymatZone zone="FIELD" :side="side" :badge="`${units.length} unit(s)`" :min-height="10">
        <FieldArea
          :field="player.field"
          :definitions="definitions"
          :side="side"
          :selected-instance-id="selectedInstanceId"
          :targetable-ids="targetableIds"
          :actionable-ids="actionableIds"
          :interactive="interactive"
          @card-click="onCard"
        />
      </PlaymatZone>

      <!-- MILIEU-DROITE : Deck -->
      <PlaymatZone zone="DECK" :side="side" :badge="player.deckCount">
        <CardPile
          :count="player.deckCount"
          :side="side"
          empty-label="pioche vide"
          :clickable="isMe && interactive && awaitingDraw"
          click-label="Piocher"
          @click="emit('drawClick')"
        />
      </PlaymatZone>

      <!-- BAS-CENTRE-GAUCHE : Legends (exactement 3 slots) -->
      <PlaymatZone zone="LEGENDS" :side="side" :badge="`${legends.length}/${LEGEND_SLOTS}`">
        <LegendsArea
          :legends="legends"
          :definitions="definitions"
          :side="side"
          :selected-instance-id="selectedInstanceId"
          :targetable-ids="targetableIds"
          :actionable-ids="actionableIds"
          :interactive="interactive"
          @card-click="onCard"
        />
      </PlaymatZone>

      <!-- BAS-CENTRE-DROITE : Eddies (cartes vendues, face cachée, inclinables pour +1 ¤) -->
      <PlaymatZone zone="EDDIES" :side="side" :badge="`¤ ${player.availableEddies}`">
        <div class="eddies-row cyber-scroll" :data-side="side" :data-count="player.eddiesArea.length">
          <p v-if="player.eddiesArea.length === 0" class="font-mono text-[0.6rem] text-slate-600">
            aucune ressource — vends une carte pour en créer
          </p>
          <CardComponent
            v-for="card in player.eddiesArea"
            :key="card.instanceId"
            :card="card"
            :definition="definitionOf(card)"
            :side="side"
            size="xs"
            :selectable="interactive"
            :selected="selectedInstanceId === card.instanceId"
            :dimmed="interactive && !isActionable(card.instanceId)"
            @click="onCard(card)"
          />
        </div>
      </PlaymatZone>

      <!-- BAS-DROITE : Trash -->
      <PlaymatZone zone="TRASH" :side="side" :badge="player.trash.length">
        <CardPile
          :cards="player.trash"
          :count="player.trash.length"
          :definitions="definitions"
          :side="side"
          face-up
          empty-label="trash vide"
        />
      </PlaymatZone>
    </div>

    <!-- Hors tapis : la main (dos de carte pour le rival) -->
    <HandRow
      :player="player"
      :definitions="definitions"
      :is-me="isMe"
      :selected-instance-id="selectedInstanceId"
      :targetable-ids="targetableIds"
      :actionable-ids="actionableIds"
      :interactive="interactive"
      @card-click="onCard"
    />
  </section>
</template>
