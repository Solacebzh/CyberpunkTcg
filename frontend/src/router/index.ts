import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import HomeView from '@/views/HomeView.vue'

/**
 * Routes du client (feature 05) : accueil, deck builder, lobby et plateau.
 *
 * `/game/:gameId?` accepte un identifiant optionnel : en cas de rechargement de
 * page, `GameView` retombe sur le `gameId` conservé par le lobby (doc §8) et
 * redirige vers `/lobby` s'il n'y a rien à reprendre.
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
    // Chargement paresseux : ces écrans ne pèsent pas sur l'accueil
    component: () => import('@/views/LobbyView.vue'),
    meta: { title: 'Lobby' },
  },
  {
    path: '/deck',
    name: 'deck',
    component: () => import('@/views/DeckBuilderView.vue'),
    meta: { title: 'Deck builder' },
  },
  {
    path: '/game/:gameId?',
    name: 'game',
    component: () => import('@/views/GameView.vue'),
    meta: { title: 'Partie' },
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
