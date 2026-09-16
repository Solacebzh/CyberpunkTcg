import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { request } from '@/services/api'
import { AUTH_CLEARED_EVENT, clearStoredAuth, getAuthToken, getStoredUsername, storeAuth } from '@/services/authToken'

interface AuthResponse {
  token: string
  id: number
  username: string
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getAuthToken())
  const username = ref<string | null>(getStoredUsername())
  const isAuthenticated = computed(() => Boolean(token.value))

  // Synchronise le store lorsqu'un appel API signale un JWT expiré/invalide.
  window.addEventListener(AUTH_CLEARED_EVENT, () => {
    token.value = null
    username.value = null
  })

  function accept(response: AuthResponse): void {
    token.value = response.token
    username.value = response.username
    storeAuth(response.token, response.username)
  }

  async function login(loginUsername: string, password: string): Promise<void> {
    const response = await request<AuthResponse>('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: loginUsername.trim(), password }),
    })
    accept(response)
  }

  async function register(registerUsername: string, password: string): Promise<void> {
    const response = await request<AuthResponse>('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: registerUsername.trim(), password }),
    })
    accept(response)
  }

  function logout(): void {
    token.value = null
    username.value = null
    clearStoredAuth()
  }

  return { token, username, isAuthenticated, login, register, logout }
})
