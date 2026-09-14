import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import HomeView from '@/views/HomeView.vue'

/**
 * Routes du client. Les écrans de jeu (table, deck builder) arriveront
 * avec les features 03 à 06 — voir docs/roadmap.md.
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'home',
    component: HomeView,
    meta: { title: 'Accueil' },
  },
  {
    path: '/lobby',
    name: 'lobby',
    // Chargement paresseux : le futur écran de partie ne pèse pas sur l'accueil
    component: () => import('@/views/LobbyView.vue'),
    meta: { title: 'Lobby' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    redirect: { name: 'home' },
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} — Cyberpunk TCG Online` : 'Cyberpunk TCG Online'
})

export default router
