/**
 * Test du panneau de debug (feature 6.5).
 *
 * Ce qui est exercé : l'ouverture (bouton et F12), le rendu du journal de
 * diagnostic avec le code couleur par verdict, et le bouton « Voir état complet »
 * qui appelle `GET /api/debug/game/{gameId}` puis affiche le JSON.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

import DebugPanel from '@/components/game/DebugPanel.vue'
import type { DebugGameState } from '@/types/debug'
import type { GameActionLogEntry } from '@/types/game'

const ENTRIES: GameActionLogEntry[] = [
  {
    index: 1,
    timestamp: '2026-09-15T10:00:00Z',
    turnNumber: 1,
    phase: 'MAIN',
    playerId: 'Val',
    actionType: 'PLAY_CARD',
    description: 'Joueur Val joue 6th Street Recruits (coût: 4 Eddies, 2 RAM rouge)',
    result: 'SUCCESS',
    details: { paid: 4 },
  },
  {
    index: 2,
    timestamp: '2026-09-15T10:00:01Z',
    turnNumber: 1,
    phase: 'MAIN',
    playerId: 'Val',
    actionType: 'SELL_CARD',
    description: 'Joueur Val vend une carte → REFUSÉ (Une seule vente par tour)',
    result: 'ILLEGAL',
    details: { reason: 'Une seule vente par tour' },
  },
  {
    index: 3,
    timestamp: '2026-09-15T10:00:02Z',
    turnNumber: 1,
    phase: 'DRAW',
    playerId: 'Johnny',
    actionType: 'PHASE',
    description: 'Phase DRAW : Joueur Johnny pioche Test carte',
    result: 'INFO',
    details: {},
  },
]

const DEBUG_STATE: DebugGameState = {
  gameId: 'game-1',
  turnNumber: 1,
  phase: 'MAIN',
  activePlayerId: 'Val',
  gameOver: false,
  seed: 42,
  createdAt: '2026-09-15T10:00:00Z',
  players: [],
  log: [],
  gameLog: ENTRIES,
}

function mountPanel() {
  return mount(DebugPanel, {
    props: {
      gameId: 'game-1',
      entries: ENTRIES,
      playerId: 'Val',
      playerNames: { Val: 'Val', Johnny: 'Johnny' },
    },
    attachTo: document.body,
  })
}

describe('DebugPanel', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('est replié par défaut et se déplie au clic', async () => {
    const wrapper = mountPanel()
    expect(wrapper.find('[data-testid="debug-body"]').exists()).toBe(false)

    await wrapper.find('[data-testid="debug-toggle"]').trigger('click')
    expect(wrapper.find('[data-testid="debug-body"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="debug-count"]').text()).toContain('3 lignes')
  })

  it('affiche chaque action avec son verdict coloré (vert/rouge/jaune)', async () => {
    const wrapper = mountPanel()
    await wrapper.find('[data-testid="debug-toggle"]').trigger('click')

    const rows = wrapper.findAll('[data-testid="debug-log"] li')
    expect(rows).toHaveLength(3)
    expect(rows[0].attributes('data-result')).toBe('SUCCESS')
    expect(rows[0].html()).toContain('text-cyber-green')
    expect(rows[1].attributes('data-result')).toBe('ILLEGAL')
    expect(rows[1].html()).toContain('text-cyber-red')
    expect(rows[1].text()).toContain('REFUSÉ')
    expect(rows[2].html()).toContain('text-cyber-yellow')
  })

  it('bascule aussi avec F12', async () => {
    const wrapper = mountPanel()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'F12' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="debug-body"]').exists()).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'F12' }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="debug-body"]').exists()).toBe(false)
  })

  it('« Voir état complet » récupère l’état non masqué et l’affiche en JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => DEBUG_STATE,
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mountPanel()
    await wrapper.find('[data-testid="debug-toggle"]').trigger('click')
    await wrapper.find('[data-testid="debug-full-state"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="debug-json"]').exists()).toBe(true))

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/debug/game/game-1?logs=50',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(wrapper.find('[data-testid="debug-json"]').text()).toContain('"seed": 42')
  })

  it('affiche l’erreur HTTP quand /api/debug est désactivé (404 hors test/dev)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }),
    )

    const wrapper = mountPanel()
    await wrapper.find('[data-testid="debug-toggle"]').trigger('click')
    await wrapper.find('[data-testid="debug-full-state"]').trigger('click')

    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="debug-error"]').text()).toContain('HTTP 404'),
    )
  })
})
