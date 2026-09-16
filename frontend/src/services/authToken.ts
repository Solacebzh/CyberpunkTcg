const TOKEN_KEY = 'cyberpunk_tcg.jwt'
const USERNAME_KEY = 'cyberpunk_tcg.username'

export function getAuthToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getStoredUsername(): string | null {
  return localStorage.getItem(USERNAME_KEY)
}

export function storeAuth(token: string, username: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USERNAME_KEY, username)
}

export const AUTH_CLEARED_EVENT = 'cyberpunk-tcg:auth-cleared'

export function clearStoredAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USERNAME_KEY)
  window.dispatchEvent(new Event(AUTH_CLEARED_EVENT))
}
