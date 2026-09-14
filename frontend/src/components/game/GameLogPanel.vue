<script setup lang="ts">
/**
 * Journal de partie (doc §7.4). Le journal est public et sans secret : il est
 * diffusé tel quel par le serveur, ce composant ne fait que le mettre en forme.
 */
import { nextTick, ref, watch } from 'vue'

import { EVENT_LABELS, type GameLogEntry } from '@/types/game'

const props = defineProps<{
  entries: GameLogEntry[]
  meId: string | null
  playerName: string
  opponentName: string
}>()

const scroller = ref<HTMLElement | null>(null)

const TONE: Record<string, string> = {
  ATTACK_DECLARED: 'text-cyber-magenta',
  UNIT_DEFEATED: 'text-cyber-red',
  GIG_STOLEN: 'text-cyber-yellow',
  GIG_ROLLED: 'text-cyber-yellow',
  CARD_PLAYED: 'text-cyber-cyan',
  LEGEND_FLIPPED: 'text-cyber-cyan',
  CARD_DRAWN: 'text-slate-300',
  CARD_SOLD: 'text-cyber-green',
  TURN_STARTED: 'text-cyber-green',
  TURN_ENDED: 'text-slate-400',
  PHASE_CHANGED: 'text-slate-500',
  GAME_WON: 'text-cyber-yellow',
  EFFECT_RESOLVED: 'text-cyber-cyan',
  REACTION_WINDOW_OPENED: 'text-cyber-magenta',
  REACTION_WINDOW_CLOSED: 'text-slate-500',
}

function tone(type: string): string {
  return TONE[type] ?? 'text-slate-300'
}

function who(playerId?: string): string {
  if (!playerId) return ''
  if (playerId === props.meId) return props.playerName
  return playerId === props.opponentName ? props.opponentName : playerId
}

watch(
  () => props.entries.length,
  async () => {
    await nextTick()
    const node = scroller.value
    if (node) node.scrollTop = node.scrollHeight
  },
)
</script>

<template>
  <section class="cyber-panel flex h-full min-h-0 flex-col">
    <header class="flex items-center justify-between border-b border-cyber-line/70 px-3 py-2">
      <h2 class="cyber-title text-xs text-cyber-cyan">Journal</h2>
      <span class="font-mono text-[0.6rem] text-slate-500">{{ entries.length }} entrée(s)</span>
    </header>

    <div ref="scroller" class="cyber-scroll min-h-0 flex-1 overflow-y-auto px-3 py-2">
      <p v-if="entries.length === 0" class="font-mono text-[0.65rem] text-slate-600">
        En attente du premier événement…
      </p>
      <ol v-else class="flex flex-col gap-1.5">
        <li
          v-for="entry in entries"
          :key="entry.index"
          class="grid grid-cols-[auto_auto_1fr] items-baseline gap-x-2 font-mono text-[0.62rem] leading-snug"
        >
          <span class="text-slate-600">{{ String(entry.index).padStart(3, '0') }}</span>
          <span class="uppercase tracking-wider" :class="tone(entry.type)">
            {{ EVENT_LABELS[entry.type] ?? entry.type }}
          </span>
          <span class="text-slate-300">
            <span v-if="entry.playerId" class="text-slate-500">{{ who(entry.playerId) }} — </span>
            {{ entry.description }}
          </span>
        </li>
      </ol>
    </div>
  </section>
</template>
