<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import ConnectionBadge from '@/components/ConnectionBadge.vue'

const year = new Date().getFullYear()
</script>

<template>
  <div class="flex min-h-dvh flex-col">
    <header class="sticky top-0 z-30 border-b border-cyber-line/70 bg-cyber-bg/85 backdrop-blur">
      <div class="mx-auto flex w-full max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
        <RouterLink to="/" class="group flex items-center gap-3">
          <span
            class="grid h-9 w-9 place-items-center rounded border border-cyber-yellow/80 bg-cyber-yellow/10 font-mono text-sm font-bold text-cyber-yellow transition group-hover:bg-cyber-yellow/20"
          >
            CP
          </span>
          <span class="leading-none">
            <span class="cyber-title block text-sm text-cyber-yellow">Cyberpunk TCG</span>
            <span class="block font-mono text-[0.65rem] tracking-widest text-slate-400">ONLINE // v0.1.0</span>
          </span>
        </RouterLink>

        <nav class="flex items-center gap-1 font-mono text-xs uppercase tracking-widest">
          <RouterLink
            to="/"
            class="rounded px-3 py-1.5 text-slate-300 transition hover:bg-white/5 hover:text-cyber-cyan"
            active-class="text-cyber-cyan"
          >
            Accueil
          </RouterLink>
          <RouterLink
            to="/lobby"
            class="rounded px-3 py-1.5 text-slate-300 transition hover:bg-white/5 hover:text-cyber-cyan"
            active-class="text-cyber-cyan"
          >
            Lobby
          </RouterLink>
        </nav>

        <div class="ml-auto">
          <ConnectionBadge />
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
      <RouterView />
    </main>

    <footer class="border-t border-cyber-line/70 py-5">
      <div class="mx-auto flex w-full max-w-6xl flex-col gap-1 px-4 font-mono text-[0.7rem] text-slate-500">
        <p>© {{ year }} Cyberpunk TCG Online — projet privé, non commercial.</p>
        <p>
          Cyberpunk® est une marque de CD PROJEKT RED. Cyberpunk TCG est édité par WeirdCo. Projet de fan non affilié.
        </p>
      </div>
    </footer>
  </div>
</template>
