<script setup lang="ts">
/**
 * Panneau de debug (feature 6.5).
 *
 * Objectif : rendre les règles **observables** pendant une vraie partie. Le
 * panneau affiche le journal de diagnostic diffusé en temps réel sur
 * `/topic/game/{gameId}/log` (`type: "LOG"`) — chaque action y figure, y compris
 * les refus — et permet de récupérer l'état complet non masqué via
 * `GET /api/debug/game/{gameId}` (profils Spring `test`/`dev` uniquement).
 *
 * Ouverture : bouton « Debug » ou touche <kbd>F12</kbd>.
 * Couleurs : vert = action acceptée, rouge = action refusée, orange = action sans
 * effet, jaune = information (phase, vérification de victoire).
 *
 * Voir `docs/DEBUG-GUIDE.md`.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { fetchDebugGameState, forceDebugPhase } from '@/services/api'
import type { DebugGameState, DebugPhase } from '@/types/debug'
import type { GameActionLogEntry } from '@/types/game'

const props = defineProps<{
  gameId: string | null
  /** Journal de diagnostic courant (store, alimenté par le topic + les états). */
  entries?: GameActionLogEntry[]
  /** Joueur courant, affiché dans l'en-tête. */
  playerId?: string | null
  /** Noms lisibles des deux joueurs (facultatif, pour l'affichage). */
  playerNames?: Record<string, string>
}>()

const open = ref(false)
const scroller = ref<HTMLElement | null>(null)
const rawState = ref<DebugGameState | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const forcedPhase = ref<DebugPhase | null>(null)

const lines = computed<GameActionLogEntry[]>(() => props.entries ?? [])
const lastIndex = computed(() => (lines.value.length ? lines.value[lines.value.length - 1].index : 0))

/** Classe de couleur par verdict (vert = succès, rouge = refus, jaune = info). */
const TONE: Record<string, string> = {
  SUCCESS: 'text-cyber-green',
  ILLEGAL: 'text-cyber-red',
  FAILED: 'text-cyber-orange',
  INFO: 'text-cyber-yellow',
}

function tone(result: string): string {
  return TONE[result] ?? 'text-slate-300'
}

function badge(result: string): string {
  switch (result) {
    case 'SUCCESS':
      return 'OK'
    case 'ILLEGAL':
      return 'REFUSÉ'
    case 'FAILED':
      return 'SANS EFFET'
    default:
      return 'INFO'
  }
}

function who(playerId?: string | null): string {
  if (!playerId) return 'système'
  return props.playerNames?.[playerId] ?? playerId
}

function toggle(): void {
  open.value = !open.value
  if (open.value) void scrollToBottom()
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'F12') {
    event.preventDefault()
    toggle()
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const node = scroller.value
  if (node) node.scrollTop = node.scrollHeight
}

/** Récupère l'état complet non masqué (bouton « Voir état complet »). */
async function loadFullState(): Promise<void> {
  if (!props.gameId) return
  loading.value = true
  error.value = null
  try {
    rawState.value = await fetchDebugGameState(props.gameId, 50)
  } catch (failure) {
    rawState.value = null
    error.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    loading.value = false
  }
}

/** Force la phase (test d'une règle sans rejouer la partie). */
async function forcePhase(phase: DebugPhase): Promise<void> {
  if (!props.gameId) return
  error.value = null
  try {
    rawState.value = await forceDebugPhase(props.gameId, phase, props.playerId ?? null)
    forcedPhase.value = phase
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  }
}

function download(): void {
  if (!rawState.value) return
  const blob = new Blob([JSON.stringify(rawState.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `cyberpunk-tcg-${rawState.value.gameId}-debug.json`
  anchor.click()
  URL.revokeObjectURL(url)
}

watch(
  () => lastIndex.value,
  () => {
    if (open.value) void scrollToBottom()
  },
)

// Réinitialise l'état complet quand on change de partie.
watch(
  () => props.gameId,
  () => {
    rawState.value = null
    error.value = null
    forcedPhase.value = null
  },
)

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

defineExpose({ toggle, loadFullState, forcePhase })
</script>

<template>
  <section class="cyber-panel text-[11px] leading-snug" data-testid="debug-panel">
    <header class="flex items-center justify-between border-b border-cyber-line/70 px-3 py-2">
      <h2 class="cyber-title text-xs text-cyber-yellow">
        Debug <span class="text-slate-500">(F12)</span>
      </h2>
      <div class="flex items-center gap-2">
        <span class="text-slate-500" data-testid="debug-count">{{ lines.length }} lignes</span>
        <button
          type="button"
          class="cyber-btn px-2 py-0.5"
          data-testid="debug-toggle"
          :aria-expanded="open"
          @click="toggle"
        >
          {{ open ? 'Replier' : 'Déplier' }}
        </button>
      </div>
    </header>

    <div v-if="open" class="flex flex-col gap-2 p-3" data-testid="debug-body">
      <div class="flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="cyber-btn px-2 py-0.5"
          :disabled="!gameId || loading"
          data-testid="debug-full-state"
          @click="loadFullState"
        >
          {{ loading ? 'Chargement…' : 'Voir état complet' }}
        </button>
        <button
          v-for="phase in (['DRAW', 'MAIN', 'COMBAT', 'END'] as DebugPhase[])"
          :key="phase"
          type="button"
          class="cyber-btn px-2 py-0.5"
          :class="forcedPhase === phase ? 'text-cyber-yellow' : ''"
          :data-testid="`debug-force-${phase}`"
          :disabled="!gameId"
          @click="forcePhase(phase)"
        >
          {{ phase }}
        </button>
        <button
          v-if="rawState"
          type="button"
          class="cyber-btn px-2 py-0.5"
          data-testid="debug-download"
          @click="download"
        >
          Télécharger JSON
        </button>
      </div>

      <p v-if="error" class="text-cyber-red" data-testid="debug-error">
        {{ error }} — l'endpoint /api/debug n'est actif que sous les profils test/dev.
      </p>

      <!-- Journal de diagnostic temps réel -->
      <ol
        ref="scroller"
        class="max-h-64 min-h-24 overflow-y-auto rounded border border-cyber-line/50 bg-black/40 p-2 font-mono"
        data-testid="debug-log"
      >
        <li v-if="!lines.length" class="text-slate-500">Aucune action journalisée.</li>
        <li
          v-for="entry in lines"
          :key="entry.index"
          class="whitespace-pre-wrap break-words"
          :data-result="entry.result"
        >
          <span class="text-slate-500">#{{ entry.index }}</span>
          <span class="text-slate-500"> T{{ entry.turnNumber }} {{ entry.phase }}</span>
          <span :class="tone(entry.result)"> [{{ badge(entry.result) }}]</span>
          <span class="text-slate-400"> {{ who(entry.playerId) }}</span>
          <span :class="tone(entry.result)"> {{ entry.description }}</span>
        </li>
      </ol>

      <!-- État complet non masqué -->
      <div v-if="rawState" class="rounded border border-cyber-line/50 bg-black/40 p-2">
        <p class="mb-1 text-slate-400">
          État complet — tour {{ rawState.turnNumber }}, phase {{ rawState.phase
          }}<span v-if="rawState.drawStep" class="text-cyber-cyan"> / {{ rawState.drawStep }}</span
          ><span v-if="rawState.pendingAttack" class="text-cyber-magenta" data-debug-combat>
            / combat {{ rawState.pendingAttack.step }} (quota {{ rawState.pendingAttack.quota }}, M =
            {{ rawState.pendingAttack.stealableCount }})
          </span>, actif
          {{ who(rawState.activePlayerId) }}
          <span v-if="rawState.gameOver" class="text-cyber-yellow">
            — partie terminée (vainqueur {{ rawState.winnerId ?? '—' }})
          </span>
        </p>
        <pre
          class="max-h-80 overflow-auto font-mono text-[10px] whitespace-pre-wrap"
          data-testid="debug-json"
        >{{ JSON.stringify(rawState, null, 2) }}</pre>
      </div>
    </div>
  </section>
</template>
