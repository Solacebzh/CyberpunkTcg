<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ApiError } from '@/services/api'
import { useAuthStore } from '@/stores/authStore'

const auth = useAuthStore()
const router = useRouter()
const username = ref('')
const password = ref('')
const confirmation = ref('')
const loading = ref(false)
const error = ref('')

async function submit(): Promise<void> {
  error.value = ''
  if (password.value !== confirmation.value) {
    error.value = 'Les mots de passe ne correspondent pas.'
    return
  }
  loading.value = true
  try {
    await auth.register(username.value, password.value)
    await router.replace('/lobby')
  } catch (cause) {
    error.value = cause instanceof ApiError && cause.status === 409
      ? 'Ce pseudo est déjà utilisé.' : 'Inscription impossible. Vérifiez les informations saisies.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="mx-auto max-w-md py-8">
    <form class="cyber-panel overflow-hidden p-6 sm:p-8" @submit.prevent="submit">
      <p class="font-mono text-xs uppercase tracking-[0.3em] text-cyber-magenta">// Nouveau profil</p>
      <h1 class="cyber-title mt-2 text-3xl text-cyber-yellow">Inscription</h1>
      <p class="mt-2 text-sm text-slate-400">Créez votre identité de runner avant de rejoindre une partie.</p>

      <label class="mt-7 block font-mono text-xs uppercase tracking-widest text-cyber-cyan" for="register-username">Pseudo</label>
      <input id="register-username" v-model="username" class="cyber-input mt-2" minlength="3" maxlength="50" autocomplete="username" required autofocus />
      <p class="mt-1 font-mono text-[0.65rem] text-slate-500">3 à 50 caractères</p>
      <label class="mt-4 block font-mono text-xs uppercase tracking-widest text-cyber-cyan" for="register-password">Mot de passe</label>
      <input id="register-password" v-model="password" class="cyber-input mt-2" type="password" minlength="6" maxlength="100" autocomplete="new-password" required />
      <label class="mt-4 block font-mono text-xs uppercase tracking-widest text-cyber-cyan" for="register-confirmation">Confirmation</label>
      <input id="register-confirmation" v-model="confirmation" class="cyber-input mt-2" type="password" minlength="6" autocomplete="new-password" required />

      <p v-if="error" role="alert" class="mt-4 border-l-2 border-cyber-magenta pl-3 text-sm text-cyber-magenta">{{ error }}</p>
      <button class="cyber-btn cyber-btn--accent mt-6 w-full justify-center" :disabled="loading" type="submit">
        {{ loading ? 'Création…' : 'Créer le compte' }}
      </button>
      <p class="mt-5 text-center text-sm text-slate-400">Déjà enregistré ? <RouterLink class="text-cyber-cyan hover:underline" to="/login">Se connecter</RouterLink></p>
    </form>
  </section>
</template>
