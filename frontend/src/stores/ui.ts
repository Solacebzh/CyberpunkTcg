/**
 * Store UI transverse : pile de notifications (toasts).
 *
 * Les messages d'erreur du serveur (`/user/queue/errors`, doc §9) sont rédigés
 * en français et affichables tels quels : ce store est le seul endroit qui
 * décide *comment* ils apparaissent.
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'

export type ToastKind = 'info' | 'success' | 'warn' | 'error'

export interface Toast {
  id: number
  kind: ToastKind
  message: string
  /** Durée d'affichage en ms (`null` = à fermer manuellement). */
  ttl: number | null
}

const DEFAULT_TTL: Record<ToastKind, number | null> = {
  info: 3_500,
  success: 2_800,
  warn: 4_500,
  error: 6_000,
}

export const useUiStore = defineStore('ui', () => {
  const toasts = ref<Toast[]>([])
  let nextId = 1

  function dismiss(id: number): void {
    toasts.value = toasts.value.filter((toast) => toast.id !== id)
  }

  function push(kind: ToastKind, message: string, ttl: number | null = DEFAULT_TTL[kind]): number {
    const id = nextId++
    toasts.value = [...toasts.value, { id, kind, message, ttl }].slice(-5)
    if (ttl !== null) window.setTimeout(() => dismiss(id), ttl)
    return id
  }

  function info(message: string): number {
    return push('info', message)
  }

  function success(message: string): number {
    return push('success', message)
  }

  function warn(message: string): number {
    return push('warn', message)
  }

  function error(message: string): number {
    return push('error', message)
  }

  function clear(): void {
    toasts.value = []
  }

  return { toasts, push, dismiss, info, success, warn, error, clear }
})
