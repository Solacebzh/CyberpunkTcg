<script setup lang="ts">
/** Pile de notifications (erreurs de règles du serveur, infos de présence…). */
import { useUiStore, type ToastKind } from '@/stores/ui'

const ui = useUiStore()

const STYLE: Record<ToastKind, string> = {
  info: 'border-cyber-cyan/70 text-cyber-cyan',
  success: 'border-cyber-green/70 text-cyber-green',
  warn: 'border-cyber-yellow/70 text-cyber-yellow',
  error: 'border-cyber-magenta/70 text-cyber-magenta',
}

const ICON: Record<ToastKind, string> = {
  info: 'i',
  success: '✓',
  warn: '!',
  error: '×',
}
</script>

<template>
  <div class="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[min(22rem,90vw)] flex-col gap-2">
    <TransitionGroup name="toast">
      <div
        v-for="toast in ui.toasts"
        :key="toast.id"
        class="pointer-events-auto cyber-panel flex items-start gap-2 border-l-4 px-3 py-2"
        :class="STYLE[toast.kind]"
        role="status"
      >
        <span class="mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-full border font-mono text-[0.6rem]">
          {{ ICON[toast.kind] }}
        </span>
        <p class="flex-1 font-mono text-[0.68rem] leading-snug text-slate-200">{{ toast.message }}</p>
        <button
          type="button"
          class="font-mono text-[0.7rem] text-slate-500 transition hover:text-slate-200"
          :aria-label="'Fermer la notification ' + toast.id"
          @click="ui.dismiss(toast.id)"
        >
          ×
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.22s ease;
}

.toast-enter-from {
  opacity: 0;
  transform: translateX(18px);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(18px);
}
</style>
