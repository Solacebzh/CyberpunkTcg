<script setup lang="ts">
/**
 * Modale « Choisissez M dé(s) Gig à voler à l'adversaire » (Mini-Feature 6).
 *
 * Affichée à l'attaquant après une attaque directe **non bloquée** : le serveur a
 * calculé le quota `N = (power / 10) + 1` (0 si power ≤ 0) puis appliqué le
 * **plafond strict** `M = min(N, dés Gigs actifs du défenseur)`. Les dés non
 * lancés de la Fixer Area n'apparaissent jamais ici : on ne crée pas de dé, on
 * ne vole que des dés déjà actifs, et chaque dé volé conserve son type et sa
 * valeur (un d8 montrant 5 reste un d8 montrant 5).
 *
 * Le choix appartient à l'attaquant : exactement M dés, envoyés via
 * `STEAL_GIG` (identifiants `gigDieIds`). Un abandon de la fenêtre (fin de tour)
 * laisse le serveur résoudre le vol automatiquement sur les M dés de plus haute
 * valeur. L'affichage de N et M reste indicatif côté client : le serveur fait foi.
 */
import { computed } from 'vue'
import type { GigDieView } from '@/types/game'

const props = withDefaults(
  defineProps<{
    /** Quota théorique N (affichage pédagogique). */
    quota: number
    /** Plafond strict M = min(N, dés actifs du défenseur) : nombre exact à choisir. */
    stealableCount: number
    /** Puissance effective de l'attaquant (rappel de la formule). */
    power: number
    /** Dés Gigs actifs du défenseur, avec leur identifiant stable. */
    dice: GigDieView[]
    /** Identifiants déjà cochés (max M). */
    selected: string[]
    defenderName?: string
    /** Une action est en vol vers le serveur. */
    busy?: boolean
  }>(),
  { defenderName: 'l’adversaire', busy: false },
)

const emit = defineEmits<{ toggle: [dieId: string]; confirm: [] }>()

const isSelected = (dieId: string): boolean => props.selected.includes(dieId)
const remaining = computed(() => Math.max(0, props.stealableCount - props.selected.length))
const confirmDisabled = computed(
  () => props.busy || props.selected.length !== props.stealableCount,
)
</script>

<template>
  <div
    class="fixed inset-0 z-40 grid place-items-center bg-black/80 px-4 backdrop-blur-sm"
    role="dialog"
    aria-modal="true"
    aria-label="Vol de dés Gig"
    data-steal-modal
    :data-steal-quota="quota"
    :data-steal-count="stealableCount"
    :data-steal-selected="selected.length"
  >
    <div
      class="cyber-panel w-full max-w-2xl border-2 border-cyber-magenta/70 p-6 shadow-[0_0_40px_rgba(255,42,109,0.3)]"
    >
      <p class="font-mono text-[0.65rem] uppercase tracking-[0.35em] text-cyber-magenta">// steal gig</p>
      <h2 class="cyber-title mt-2 text-2xl text-cyber-magenta">
        Choisissez {{ stealableCount }} dé(s) Gig à voler à l'adversaire
      </h2>

      <p class="mt-3 font-mono text-[0.72rem] leading-relaxed text-slate-300" data-steal-formula>
        Power {{ power }} → quota N = {{ quota }} ; {{ defenderName }} a
        {{ dice.length }} dé(s) Gig actif(s) → plafond strict M = {{ stealableCount }}.
        Chaque dé volé garde son type et sa valeur.
      </p>

      <div class="mt-4 flex flex-wrap gap-3" data-steal-dice>
        <button
          v-for="die in dice"
          :key="die.id"
          type="button"
          class="cyber-btn min-w-[5.5rem] border-2 px-3 py-2 text-center"
          :class="
            isSelected(die.id)
              ? 'border-cyber-green/90 bg-cyber-green/15 text-cyber-green'
              : 'border-cyber-blue/50 text-slate-200 hover:border-cyber-blue'
          "
          :data-steal-die="die.id"
          :data-die="die.die"
          :data-value="die.value"
          :data-selected="isSelected(die.id) ? 'true' : 'false'"
          :aria-pressed="isSelected(die.id)"
          @click="emit('toggle', die.id)"
        >
          <span class="block font-mono text-[0.65rem] uppercase tracking-widest text-slate-400">{{ die.die }}</span>
          <span class="block text-lg font-semibold">{{ die.value }}</span>
        </button>
        <p v-if="dice.length === 0" class="font-mono text-[0.72rem] text-slate-400">
          Aucun dé Gig actif à voler.
        </p>
      </div>

      <div class="mt-5 flex flex-wrap items-center gap-3">
        <button
          type="button"
          class="cyber-btn cyber-btn--accent"
          data-steal-confirm
          :disabled="confirmDisabled"
          @click="emit('confirm')"
        >
          Voler {{ selected.length }}/{{ stealableCount }} dé(s)
        </button>
        <p class="font-mono text-[0.68rem] text-slate-400" data-steal-hint>
          {{ remaining > 0 ? `Encore ${remaining} dé(s) à choisir.` : 'Sélection complète.' }}
          Terminer ton tour sans valider laisse le serveur voler les M dés de plus haute valeur.
        </p>
      </div>
    </div>
  </div>
</template>
