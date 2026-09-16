<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { ApiError } from '@/services/api'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit(): Promise<void> {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect : '/lobby'
    await router.replace(redirect)
  } catch (cause) {
    error.value = cause instanceof ApiError && cause.status === 401
      ? 'Identifiant ou mot de passe incorrect.' : 'Connexion impossible. Vérifiez le serveur puis réessayez.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="mx-auto max-w-md py-8">
    <form class="cyber-panel overflow-hidden p-6 sm:p-8" @submit.prevent="submit">
      <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-magenta">// Accès sécurisé</p>
      <h1 class="cyber-title mt-2 text-3xl text-cyber-yellow">Connexion</h1>
      <p class="mt-2 text-sm text-slate-400">Identifiez-vous pour entrer dans le réseau de Night City.</p>

      <label class="mt-7 block font-mono text-xs uppercase tracking-widest text-cyber-cyan" for="login-username">Pseudo</label>
      <input id="login-username" v-model="username" class="cyber-input mt-2" autocomplete="username" required autofocus />
      <label class="mt-5 block font-mono text-xs uppercase tracking-widest text-cyber-cyan" for="login-password">Mot de passe</label>
      <input id="login-password" v-model="password" class="cyber-input mt-2" type="password" autocomplete="current-password" required />

      <p v-if="error" role="alert" class="mt-4 border-l-2 border-cyber-magenta pl-3 text-sm text-cyber-magenta">{{ error }}</p>
      <button class="cyber-btn cyber-btn--accent mt-6 w-full justify-center" :disabled="loading" type="submit">
        {{ loading ? 'Authentification…' : 'Se connecter' }}
      </button>
      <p class="mt-5 text-center text-sm text-slate-400">Nouveau mercenaire ? <RouterLink class="text-cyber-cyan hover:underline" to="/register">Créer un compte</RouterLink></p>
    </form>
  </section>
</template>
