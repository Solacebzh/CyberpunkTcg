<script setup lang="ts">
/**
 * Fenêtre de réaction « Utiliser Blocker ? » (Mini-Feature 6) — affichée au
 * défenseur tant qu'une attaque est suspendue sur l'étape `AWAITING_BLOCK`.
 *
 * Règle officielle § ATTACKING : un `{Blocker}` prêt peut intercepter l'attaque,
 * ce qui l'incline et la **redirige** vers lui (aucun Gig n'est volé). Le choix
 * appartient au défenseur — jamais à l'attaquant — et le blocage multiple est
 * autorisé : chaque Blocker coché est dépensé et résout ses compétences, mais
 * **seul le dernier Blocker de la liste** encaisse les dégâts du combat.
 *
 * `USE_BLOCKER` porte les identifiants dans l'ordre de la sélection ; renoncer
 * envoie `DECLINE_BLOCK` et l'attaque suit son cours (combat contre la cible
 * déclarée, ou vol de dés plafonné pour une attaque directe).
 */
import { computed } from 'vue'
import { effectivePower, type CardInstance } from '@/types/game'

const props = withDefaults(
  defineProps<{
    attackerName: string
    /** Puissance effective de l'attaquant (comparaison de combat). */
    attackerPower: number
    /** `true` pour une attaque directe de la Gig Area (vol de dés en jeu). */
    direct: boolean
    /** Nom de la cible déclarée (attaque Unité vs Unité). */
    targetName?: string | null
    /** Mes `{Blocker}` prêts — les seuls capables d'intercepter. */
    blockers: CardInstance[]
    /** Identifiants cochés, dans l'ordre de déclaration. */
    selected: string[]
    busy?: boolean
  }>(),
  { targetName: null, busy: false },
)

const emit = defineEmits<{ toggle: [instanceId: string]; confirm: []; decline: [] }>()

const isSelected = (instanceId: string): boolean => props.selected.includes(instanceId)
const lastBlockerId = computed(() => props.selected[props.selected.length - 1] ?? null)
const confirmDisabled = computed(() => props.busy || props.selected.length === 0)
const orderLabel = (instanceId: string): string =>
  props.selected.includes(instanceId) ? `${props.selected.indexOf(instanceId) + 1}.` : '·'
</script>

<template>
  <div
    class="fixed inset-0 z-40 grid place-items-center bg-black/80 px-4 backdrop-blur-sm"
    role="dialog"
    aria-modal="true"
    aria-label="Utiliser Blocker ?"
    data-blocker-prompt
    :data-direct="direct ? 'true' : 'false'"
  >
    <div
      class="cyber-panel w-full max-w-2xl border-2 border-cyber-blue/70 p-6 shadow-[0_0_40px_rgba(0,229,255,0.28)]"
    >
      <p class="font-mono text-[0.65rem] uppercase tracking-[0.35em] text-cyber-blue">// block ?</p>
      <h2 class="cyber-title mt-2 text-2xl text-cyber-blue">Utiliser Blocker ?</h2>

      <p class="mt-3 font-mono text-[0.72rem] leading-relaxed text-slate-300" data-blocker-context>
        {{ attackerName }} (Power {{ attackerPower }}) attaque
        <strong v-if="direct" class="text-cyber-magenta">ta Gig Area</strong>
        <strong v-else class="text-cyber-magenta">{{ targetName ?? 'une de tes Unités' }}</strong>.
        Bloquer redirige l'attaque vers le Blocker — aucun Gig ne sera volé.
      </p>

      <div class="mt-4 flex flex-wrap gap-3" data-blocker-options>
        <button
          v-for="blocker in blockers"
          :key="blocker.instanceId"
          type="button"
          class="cyber-btn min-w-[10rem] border-2 px-3 py-2 text-left"
          :class="
            isSelected(blocker.instanceId)
              ? 'border-cyber-green/90 bg-cyber-green/15 text-cyber-green'
              : 'border-cyber-blue/40 text-slate-200 hover:border-cyber-blue'
          "
          :data-blocker-option="blocker.instanceId"
          :data-selected="isSelected(blocker.instanceId) ? 'true' : 'false'"
          :data-last="lastBlockerId === blocker.instanceId ? 'true' : 'false'"
          :aria-pressed="isSelected(blocker.instanceId)"
          @click="emit('toggle', blocker.instanceId)"
        >
          <span class="block font-mono text-[0.65rem] uppercase tracking-widest text-slate-400">
            {{ orderLabel(blocker.instanceId) }} {Blocker} · Power {{ effectivePower(blocker) }}
          </span>
          <span class="block text-sm font-semibold">{{ blocker.name }}</span>
          <span
            v-if="lastBlockerId === blocker.instanceId"
            class="mt-1 block font-mono text-[0.62rem] text-cyber-magenta"
            data-blocker-last-hint
          >
            dernier Blocker : encaisse les dégâts
          </span>
        </button>
        <p v-if="blockers.length === 0" class="font-mono text-[0.72rem] text-slate-400">
          Aucun Blocker prêt : renonce pour laisser l'attaque se résoudre.
        </p>
      </div>

      <div class="mt-5 flex flex-wrap items-center gap-3">
        <button
          type="button"
          class="cyber-btn cyber-btn--green"
          data-block-confirm
          :disabled="confirmDisabled"
          @click="emit('confirm')"
        >
          Bloquer ({{ selected.length }})
        </button>
        <button type="button" class="cyber-btn cyber-btn--danger" data-block-decline @click="emit('decline')">
          Ne pas bloquer
        </button>
        <p class="font-mono text-[0.68rem] text-slate-400" data-blocker-hint>
          Blocage multiple autorisé : tous les Blockers cochés sont inclinés et résolvent leurs
          compétences, seul le dernier encaisse les dégâts. Fin de tour = renoncement implicite.
        </p>
      </div>
    </div>
  </div>
</template>
