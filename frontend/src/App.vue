<script setup lang="ts">
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import ConnectionBadge from '@/components/ConnectionBadge.vue'
import CyberToast from '@/components/ui/CyberToast.vue'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const { isAuthenticated, username } = storeToRefs(auth)
const router = useRouter()

function logout(): void {
  auth.logout()
  void router.push('/login')
}

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

        <nav v-if="isAuthenticated" class="flex items-center gap-1 font-mono text-xs uppercase tracking-widest">
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
          <RouterLink
            to="/deck"
            class="rounded px-3 py-1.5 text-slate-300 transition hover:bg-white/5 hover:text-cyber-cyan"
            active-class="text-cyber-cyan"
          >
            Deck
          </RouterLink>
        </nav>

        <div class="ml-auto flex items-center gap-3">
          <ConnectionBadge v-if="isAuthenticated" />
          <template v-if="isAuthenticated">
            <span class="hidden font-mono text-xs text-cyber-green sm:inline">{{ username }}</span>
            <button type="button" class="cyber-btn px-3 py-1.5" @click="logout">Déconnexion</button>
          </template>
          <template v-else>
            <RouterLink class="font-mono text-xs uppercase tracking-widest text-cyber-cyan" to="/login">Connexion</RouterLink>
            <RouterLink class="cyber-btn px-3 py-1.5" to="/register">Inscription</RouterLink>
          </template>
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
      <RouterView />
    </main>

    <CyberToast />

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
