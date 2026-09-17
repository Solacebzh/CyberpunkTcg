/**
 * Test de flux : Lobby → Partie → Vendre → Incliner (R4) → Jouer → Fin de tour →
 * phase DRAW interactive (Mini-Feature 5 : pioche + choix du dé) → Attaquer
 * (Mini-Feature 6 : cible dépensée ou Gig Area, vol de dés plafonné M) → Fin de tour.
 *
 * Ce qui est réellement exercé : les composants (`LobbyView`, `GameView`,
 * `CardComponent`, `TargetingOverlay`, `PlayerBoard`), les stores Pinia
 * (`lobby`, `game`, `deck`, `ui`) et le composable `useGameSocket` avec le vrai
 * client `@stomp/stompjs`. Seule l'extrémité réseau est simulée
 * (`devtools/mock-protocol.ts`, contrat de docs/WEBSOCKET-PROTOCOL.md).
 *
 * Les assertions portent donc sur le **contrat côté client** : destinations
 * souscrites, payloads envoyés, remplacement d'état, rendu du plateau.
 * Les règles elles-mêmes sont testées côté backend (`mvn test`).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import GameView from '@/views/GameView.vue'
import LobbyView from '@/views/LobbyView.vue'
import { __resetGameSocketForTests, useGameSocket } from '@/composables/useGameSocket'
import { useDeckStore } from '@/stores/deck'
import { useGameStore } from '@/stores/game'
import { useLobbyStore } from '@/stores/lobby'
import { useUiStore } from '@/stores/ui'
import type { CardInstance, PlayerState } from '@/types/game'

import {
  RawClient,
  card,
  commandsTo,
  installMockServer,
  tick,
  uninstallMockServer,
} from './helpers/stompHarness'
import type { MockCard, MockGameServer } from '../../devtools/mock-protocol'

// --- Fixtures ---------------------------------------------------------------

const LEGENDS: MockCard[] = [1, 2, 3].map((index) => ({
  id: `legend-${index}`,
  name: `Legend ${index}`,
  type: 'legend',
  color: 'red',
  cost: null,
  power: 4,
  streetCred: null,
  keywords: ['flip'],
  abilities: [],
}))

/** Deck du joueur A : Units puissantes (coût 1). */
const UNITS_A: MockCard[] = Array.from({ length: 10 }, (_, index) => ({
  id: `unit-a-${index}`,
  name: `Solo A${index}`,
  type: 'unit',
  color: 'green',
  cost: 1,
  power: 9,
  streetCred: null,
  keywords: [],
  abilities: [],
}))

/** Deck du joueur B : Units faibles (coût 1) → A gagne toujours ses combats. */
const UNITS_B: MockCard[] = Array.from({ length: 10 }, (_, index) => ({
  id: `unit-b-${index}`,
  name: `Ganger B${index}`,
  type: 'unit',
  color: 'blue',
  cost: 1,
  power: 2,
  streetCred: null,
  keywords: [],
  abilities: [],
}))

/**
 * Programs « ferraille » (coût 1) — Mini-Feature 10C : les Units et les Legends
 * ne peuvent PAS être vendues, chaque deck embarque donc des cartes vendables.
 */
const PROGRAMS: MockCard[] = Array.from({ length: 7 }, (_, index) => ({
  id: `program-${index}`,
  name: `Script ${index}`,
  type: 'program',
  color: 'yellow',
  cost: 1,
  power: null,
  streetCred: null,
  keywords: [],
  abilities: [],
}))

const CATALOG: MockCard[] = [...LEGENDS, ...PROGRAMS, ...UNITS_A, ...UNITS_B].map(card)
const DECK_A = [...LEGENDS.map((card) => card.id), ...PROGRAMS.map((card) => card.id), ...UNITS_A.map((card) => card.id)]
const DECK_B = [...LEGENDS.map((card) => card.id), ...PROGRAMS.map((card) => card.id), ...UNITS_B.map((card) => card.id)]

/** Première carte vendable de la main (Mini-Feature 10C : ni Unit, ni Legend). */
function firstSellable(hand: CardInstance[]): CardInstance {
  const found = hand.find((instance) => instance.type !== 'unit' && instance.type !== 'legend')
  if (!found) throw new Error('Aucune carte vendable (hors Unit/Legend) en main')
  return found
}

// --- Utilitaires de test ----------------------------------------------------

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/lobby', name: 'lobby', component: LobbyView },
      { path: '/game/:gameId?', name: 'game', component: GameView },
    ],
  })
}

function buttonWith(wrapper: VueWrapper, label: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(label))
  if (!button) throw new Error(`Bouton introuvable : « ${label} »`)
  return button
}

async function waitFor(predicate: () => boolean, label: string, timeoutMs = 3_000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`Délai dépassé en attendant : ${label}`)
    await tick(2)
  }
}

function playerOf(state: Record<string, unknown> | null, playerId: string): PlayerState {
  const players = (state?.players ?? []) as PlayerState[]
  const found = players.find((player) => player.playerId === playerId)
  if (!found) throw new Error(`Joueur ${playerId} absent de l'état`)
  return found
}

// --- Suite ------------------------------------------------------------------

describe('Flux complet Lobby → Partie → Jeu', () => {
  let server: MockGameServer
  let pinia: Pinia
  let router: Router
  let wrapper: VueWrapper

  beforeEach(async () => {
    window.localStorage.clear()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener() {}, removeEventListener() {} }))

    server = installMockServer(CATALOG)
    __resetGameSocketForTests()

    pinia = createPinia()
    setActivePinia(pinia)
    router = buildRouter()
    await router.push('/lobby')
    await router.isReady()

    wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [pinia, router] } })
    await tick()
  })

  afterEach(() => {
    wrapper.unmount()
    uninstallMockServer()
    __resetGameSocketForTests()
    vi.unstubAllGlobals()
  })

  it('ouvre le canal STOMP avec le pseudo en en-tête CONNECT', async () => {
    const lobby = useLobbyStore()

    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')

    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    const connect = server.received.find((frame) => frame.command === 'CONNECT')
    expect(connect?.headers.pseudo).toBe('Alpha')

    const subscribed = server.received
      .filter((frame) => frame.command === 'SUBSCRIBE')
      .map((frame) => frame.headers.destination)
    // Ordre imposé par la doc §1.3 : files privées d'abord, puis topics publics.
    expect(subscribed).toEqual(
      expect.arrayContaining(['/user/queue/errors', '/user/queue/lobby', '/user/queue/rooms', '/topic/rooms']),
    )
  })

  it('crée un salon, démarre la partie au 2e joueur, puis joue un tour complet', async () => {
    const lobby = useLobbyStore()
    const game = useGameStore()
    const decks = useDeckStore()
    const socket = useGameSocket()

    // --- 1. Connexion + deck personnalisé --------------------------------
    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')
    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    decks.setDeck(DECK_A)
    // Mini-Feature 9D : on simule un deck sauvegardé côté mock, puis on le sélectionne.
    const alphaDeckId = server.registerSavedDeck('Alpha', DECK_A)
    lobby.selectDeck(alphaDeckId)
    await tick(2)

    // --- 2. Création du salon --------------------------------------------
    await buttonWith(wrapper, 'Créer le salon').trigger('click')
    await waitFor(() => lobby.room !== null, 'LOBBY_STATE du salon créé')

    const roomCode = lobby.room?.code ?? ''
    expect(roomCode).toMatch(/^[A-Z2-9]{6}$/)
    expect(wrapper.text()).toContain(roomCode)
    expect(wrapper.text()).toContain('en attente du 2e joueur')

    const createCommand = commandsTo(server, '/app/lobby.create')[0]
    expect(createCommand).toMatchObject({ deckId: alphaDeckId })

    // --- 3. Le second joueur rejoint → la partie démarre ------------------
    const bravo = new RawClient(server, 'Bravo')
    bravo.connect()
    bravo.subscribe('/user/queue/lobby')
    bravo.subscribe('/user/queue/errors')
    const bravoDeckId = server.registerSavedDeck('Bravo', DECK_B)
    bravo.send('/app/lobby.join', { roomCode, deckId: bravoDeckId })

    await waitFor(() => lobby.gameId !== null, 'gameId reçu après join')
    await waitFor(() => router.currentRoute.value.name === 'game', 'navigation vers /game')
    await waitFor(() => game.state !== null, 'première STATE de la partie')

    const gameId = lobby.gameId ?? ''
    expect(router.currentRoute.value.params.gameId).toBe(gameId)

    // Le client s'abonne à son topic d'état personnel puis demande un resync.
    const subscribed = server.received
      .filter((frame) => frame.command === 'SUBSCRIBE')
      .map((frame) => frame.headers.destination)
    expect(subscribed).toContain(`/topic/game/${gameId}/Alpha`)
    expect(subscribed).toContain(`/topic/game/${gameId}`)
    // Journal de diagnostic (feature 6.5) : topic public souscrit par le client.
    expect(subscribed).toContain(`/topic/game/${gameId}/log`)
    expect(commandsTo(server, '/app/game/' + gameId + '/resync')).toHaveLength(1)

    // Plateau rendu : 6 cartes en main, main adverse masquée, 3 Legends.
    expect(game.me?.hand).toHaveLength(6)
    expect(game.me?.legendsArea).toHaveLength(3)
    expect(wrapper.findAll('[data-card-side="me"][data-card-zone="HAND"]')).toHaveLength(6)
    expect(wrapper.findAll('[data-card-side="opponent"][data-card-zone="HAND"]')).toHaveLength(6)
    expect(
      wrapper.findAll('[data-card-side="opponent"][data-card-zone="HAND"]')[0]?.attributes('aria-label'),
    ).toBe('Carte masquée')
    expect(game.isMyTurn).toBe(true)
    expect(game.turnNumber).toBe(1)

    // Tapis officiel : les deux demi-plateaux exposent les 6 zones imprimées et le
    // bandeau « Rival Gigs / Friendly Gigs » coiffe le tout (mini-feature playmat).
    for (const side of ['me', 'opponent'] as const) {
      expect(wrapper.findAll(`[data-board="${side}"] .playmat-grid`)).toHaveLength(1)
      for (const zone of ['FIXER', 'FIELD', 'DECK', 'LEGENDS', 'EDDIES', 'TRASH']) {
        expect(wrapper.findAll(`[data-board="${side}"] [data-zone="${zone}"]`)).toHaveLength(1)
      }
    }
    expect(wrapper.findAll('[data-zone="GIGS"]')).toHaveLength(1)
    expect(wrapper.get('[data-zone="GIGS"]').text()).toContain('Rival Gigs')
    expect(wrapper.get('[data-zone="GIGS"]').text()).toContain('Friendly Gigs')
    // 3 slots de Legends remplis par camp (règle : exactement 3 Legends).
    expect(wrapper.findAll('[data-zone="LEGENDS"] [data-slot]')).toHaveLength(6)
    expect(wrapper.findAll('[data-zone="LEGENDS"] [data-slot][data-filled="true"]')).toHaveLength(6)
    // 6 dés Gig par camp dans la colonne Fixer.
    expect(wrapper.findAll('[data-zone="FIXER"] [data-fixer-die]')).toHaveLength(12)

    // --- 3 bis. Mini-Feature 5.1 : le premier joueur (Alpha) ouvre son tour 1 en
    // phase DRAW (sous-étape AWAITING_DRAW) — il doit piocher puis lancer son dé Gig
    // avant d'entrer en phase MAIN, exactement comme tous les tours.
    expect(game.phase).toBe('DRAW')
    expect(game.drawStep).toBe('AWAITING_DRAW')
    await waitFor(() => wrapper.find('[data-draw-guide]').exists(), 'bandeau de guidage (tour 1, premier joueur)')
    // Clic sur la pioche → +1 carte.
    await wrapper.get('[data-board="me"] [data-zone="DECK"] .card-pile').trigger('click')
    await waitFor(() => (game.me?.hand.length ?? 0) === 7, 'ma carte piochée (tour 1)')
    await waitFor(() => game.drawStep === 'AWAITING_DIE_SELECT', 'choix du dé attendu (tour 1)')
    await wrapper.get('[data-draw-guide] [data-die-option="d4"]').trigger('click')
    await waitFor(() => game.phase === 'MAIN', 'phase Main après pioche + lancer (tour 1)')
    expect(game.me?.gigCount).toBe(1)
    expect(game.drawStep).toBeNull()
    expect(game.isMyTurn).toBe(true)

    // --- 4. Vente (Mini-Feature 3 + 10C) ------------------------------------
    // 10C — UI : une Unit sélectionnée en main ⇒ bouton « Vendre » désactivé.
    const unitInHand = (game.me?.hand ?? []).find((instance) => instance.type === 'unit') as CardInstance
    expect(unitInHand).toBeTruthy()
    await wrapper.get(`[data-instance-id="${unitInHand.instanceId}"]`).trigger('click')
    expect(game.selectedInstanceId).toBe(unitInHand.instanceId)
    const sellButton = buttonWith(wrapper, 'Vendre')
    expect((sellButton.element as HTMLButtonElement).disabled).toBe(true)
    expect(sellButton.attributes('title')).toBe('Les Unités et les Légendes ne peuvent pas être vendues')

    // 10C — store : la garde locale refuse l'intention (toast) sans rien envoyer.
    const uiStore = useUiStore()
    const actionUrl = `/app/game/${gameId}/action`
    const sellSendsBefore = commandsTo(server, actionUrl).filter((command) => command.action === 'SELL_CARD').length
    expect(game.sellCard(unitInHand.instanceId)).toBe(false)
    expect(commandsTo(server, actionUrl).filter((command) => command.action === 'SELL_CARD')).toHaveLength(sellSendsBefore)
    expect(uiStore.toasts.some((toast) => toast.message.includes('ne peuvent pas être vendues'))).toBe(true)

    // 10C — serveur : un SELL_CARD forcé sur l'Unit est rejeté (ILLEGAL_ACTION)
    // et ne consomme PAS le quota de vente du tour.
    socket.sendAction(gameId, { action: 'SELL_CARD', instanceId: unitInHand.instanceId })
    await waitFor(
      () => game.debugLog.some((entry) => entry.actionType === 'SELL_CARD' && entry.result === 'ILLEGAL'),
      'refus 10C journalisé (ILLEGAL)',
    )
    const typeRefusal = game.debugLog.find((entry) => entry.actionType === 'SELL_CARD' && entry.result === 'ILLEGAL')
    expect(typeRefusal?.description).toContain('REFUSÉ')
    expect(typeRefusal?.details?.reason).toContain('Les Unités et les Légendes ne peuvent pas être vendues')
    expect(game.me?.hasSoldThisTurn).toBe(false)
    expect(game.me?.hand.some((instance) => instance.instanceId === unitInHand.instanceId)).toBe(true)
    expect(game.me?.eddiesArea ?? []).toHaveLength(0)

    // Une carte VENDABLE (Program) reste acceptée : 1 carte de la main →
    // ressource, 0 ¤ immédiat (Mini-Feature 3).
    const sold = firstSellable(game.me?.hand ?? [])
    await wrapper.get(`[data-instance-id="${sold.instanceId}"]`).trigger('click')
    expect(game.selectedInstanceId).toBe(sold.instanceId)

    await buttonWith(wrapper, 'Vendre').trigger('click')
    await waitFor(() => (game.me?.eddiesArea?.length ?? 0) === 1, 'ressource posée en Eddies Area')
    // La vente ne crédite AUCUN Eddie : elle crée la ressource (face cachée, prête).
    expect(game.me?.eddies).toBe(0)
    const soldInArea = (game.me?.eddiesArea ?? []).find((c) => c.instanceId === sold.instanceId)
    expect(soldInArea?.zone).toBe('EDDIES_AREA')
    expect(soldInArea?.faceDown).toBe(true)
    expect(soldInArea?.exhausted).toBe(false)

    const actions = () => commandsTo(server, `/app/game/${gameId}/action`)
    // Le SELL_CARD refusé de la section 10C (Unit) précède celui-ci : on cible
    // la commande envoyée pour LA carte vendable.
    const sellCommand = actions().find(
      (command) => command.action === 'SELL_CARD' && command.instanceId === sold.instanceId,
    )
    expect(sellCommand).toMatchObject({ action: 'SELL_CARD', instanceId: sold.instanceId })
    expect(typeof sellCommand?.clientRequestId).toBe('string')
    expect(game.me?.hand).toHaveLength(6) // 7 après la pioche du tour 1, moins 1 vente
    expect(game.me?.hasSoldThisTurn).toBe(true)

    // --- 4 bis. R4 (Mini-Feature 4) : incliner la ressource → +1 Eddie -----
    // La carte vendue est rendue individuellement (face cachée) : on la sélectionne
    // puis on l'incline via le bouton « Incliner (+1 ¤) ». Chaque STATE remplace
    // l'état local : on relit donc toujours les exemplaires depuis `game.me`.
    await wrapper.get(`[data-instance-id="${sold.instanceId}"]`).trigger('click')
    expect(game.selectedInstanceId).toBe(sold.instanceId)
    const spentReady = (game.me?.eddiesArea ?? []).find((c) => c.instanceId === sold.instanceId)
    expect(spentReady).toBeTruthy()
    expect(game.canSpendCard(spentReady as CardInstance)).toBeNull()

    await buttonWith(wrapper, 'Incliner').trigger('click')
    await waitFor(() => (game.me?.eddies ?? 0) === 1, 'Eddie reçu après inclinaison (R4)')

    const spendCommand = actions().find((command) => command.action === 'SPEND_RESOURCE')
    expect(spendCommand).toMatchObject({ action: 'SPEND_RESOURCE', instanceId: sold.instanceId })
    const spent = (game.me?.eddiesArea ?? []).find((c) => c.instanceId === sold.instanceId)
    expect(spent?.exhausted).toBe(true)
    expect(spent?.zone).toBe('EDDIES_AREA') // la ressource reste dans sa zone
    // Deuxième inclinaison de la même carte : refusé (1 €$ par tour et par carte).
    expect(game.canSpendCard(spent as CardInstance)).toMatch(/déjà inclinée/)

    // --- 4 bis. Journal de diagnostic : actions et refus tracés -------------
    await waitFor(
      () => game.debugLog.some((entry) => entry.actionType === 'SELL_CARD' && entry.result === 'SUCCESS'),
      'vente journalisée (SUCCESS)',
    )
    expect(game.debugLog.some((entry) => entry.actionType === 'GAME_START')).toBe(true)

    // Une seconde vente est illégale : le refus est journalisé et diffusé (ILLEGAL).
    // (Le garde « 1 vente par tour » précède la restriction de type 10C côté serveur.)
    const secondCard = game.me?.hand[0] as CardInstance
    socket.sendAction(gameId, { action: 'SELL_CARD', instanceId: secondCard.instanceId })
    await waitFor(
      () => game.debugLog.some((entry) => entry.result === 'ILLEGAL' && entry.description.includes('Une seule vente')),
      'refus journalisé (ILLEGAL)',
    )
    const refusal = game.debugLog.find((entry) => entry.result === 'ILLEGAL' && entry.description.includes('Une seule vente'))
    expect(refusal?.description).toContain('REFUSÉ')
    expect(refusal?.phase).toBeTruthy()
    expect(game.me?.hasSoldThisTurn).toBe(true)

    // --- 5. Pose d'une Unit (coût 1) -------------------------------------
    const played = game.me?.hand.find((card) => card.type === 'unit' && card.cost <= 1) as CardInstance
    await wrapper.get(`[data-instance-id="${played.instanceId}"]`).trigger('click')
    await buttonWith(wrapper, 'Jouer la carte').trigger('click')
    await waitFor(() => (game.me?.field.length ?? 0) === 1, 'Unit posée sur le Field')

    const playCommand = actions().find((command) => command.action === 'PLAY_CARD' && command.instanceId === played.instanceId)
    expect(playCommand).toMatchObject({ action: 'PLAY_CARD', instanceId: played.instanceId })
    expect(game.me?.field[0]?.summoningSickness).toBe(true)
    expect(game.me?.eddies).toBe(0)
    expect(wrapper.findAll('[data-card-side="me"][data-card-zone="FIELD"]')).toHaveLength(1)

    // --- 6. Fin de tour ---------------------------------------------------
    await buttonWith(wrapper, 'Fin de tour').trigger('click')
    await waitFor(() => game.turnNumber === 2, 'passage au tour 2')
    expect(game.activePlayerId).toBe('Bravo')
    expect(game.isMyTurn).toBe(false)
    expect(wrapper.text()).toContain('tour de Bravo')

    // Mini-Feature 5 : la phase DRAW de Bravo est interactive — rien n'est pioché
    // ni lancé automatiquement, la partie attend son clic sur la pioche.
    expect(game.phase).toBe('DRAW')
    expect(game.drawStep).toBe('AWAITING_DRAW')
    expect(game.opponent?.hand).toHaveLength(6)
    expect(game.opponent?.gigCount).toBe(0)
    expect(game.awaitingMyDrawAction).toBe(false) // ce n'est pas MA pioche
    expect(wrapper.find('[data-draw-guide]').exists()).toBe(false)
    expect(wrapper.get('[data-draw-waiting]').text()).toContain('Bravo pioche sa carte')

    // --- 7. Tour de Bravo (client brut) : pioche, dé, vente, pose, fin de tour --------
    // Bravo s'abonne à SON topic d'état puis resync (doc §1.3, §5.3).
    bravo.subscribe(`/topic/game/${gameId}/Bravo`)
    bravo.subscribe(`/topic/game/${gameId}`)
    bravo.subscribe('/user/queue/errors')
    bravo.send(`/app/game/${gameId}/resync`, {})
    await waitFor(() => bravo.lastState() !== null, 'STATE reçue par Bravo')
    expect((bravo.lastState()?.turn as { drawStep?: string }).drawStep).toBe('AWAITING_DRAW')

    // Impossible de passer la phase DRAW sans piocher : END_TURN et SELECT_DIE sont refusés.
    bravo.send(`/app/game/${gameId}/action`, { action: 'END_TURN', clientRequestId: 'bravo-early-end' })
    await waitFor(() => bravo.errors().some((e) => e.clientRequestId === 'bravo-early-end'), 'END_TURN refusé en DRAW')
    bravo.send(`/app/game/${gameId}/action`, { action: 'SELECT_DIE', dice: ['d6'], clientRequestId: 'bravo-early-die' })
    await waitFor(() => bravo.errors().some((e) => e.clientRequestId === 'bravo-early-die'), 'SELECT_DIE refusé avant la pioche')
    expect(playerOf(bravo.lastState(), 'Bravo').hand).toHaveLength(6)

    // Clic sur la pioche → +1 carte, puis le serveur attend le choix du dé.
    bravo.send(`/app/game/${gameId}/action`, { action: 'DRAW_CARD' })
    await waitFor(() => playerOf(bravo.lastState(), 'Bravo').hand.length === 7, 'Bravo a pioché sa carte')
    await waitFor(() => game.drawStep === 'AWAITING_DIE_SELECT', 'Alpha voit Bravo choisir son dé')
    expect(wrapper.get('[data-draw-waiting]').text()).toContain('Bravo choisit son dé Gig')

    // Le d20 est refusé tant qu'il reste d'autres dés ; le d6 est accepté et lancé par le serveur.
    bravo.send(`/app/game/${gameId}/action`, { action: 'SELECT_DIE', dice: ['d20'], clientRequestId: 'bravo-d20' })
    await waitFor(() => bravo.errors().some((e) => e.clientRequestId === 'bravo-d20'), 'd20 refusé (toujours en dernier)')
    expect(String(bravo.errors().find((e) => e.clientRequestId === 'bravo-d20')?.message)).toContain('d20')
    bravo.send(`/app/game/${gameId}/action`, { action: 'SELECT_DIE', dice: ['d6'] })
    await waitFor(() => playerOf(bravo.lastState(), 'Bravo').gigCount === 1, 'Gig de Bravo lancé')
    const bravoAfterDraw = playerOf(bravo.lastState(), 'Bravo')
    expect(bravoAfterDraw.fixerDice).toEqual(['d4', 'd8', 'd10', 'd12', 'd20'])
    expect(bravoAfterDraw.gigDice).toEqual(['d6'])
    expect(bravoAfterDraw.gigs[0]).toBeGreaterThanOrEqual(1)
    expect(bravoAfterDraw.gigs[0]).toBeLessThanOrEqual(6)
    await waitFor(() => game.phase === 'MAIN', 'phase Main de Bravo (DRAW_COMPLETE automatique)')
    expect(game.drawStep).toBeNull()
    expect(wrapper.find('[data-draw-waiting]').exists()).toBe(false)

    const bravoHand = playerOf(bravo.lastState(), 'Bravo').hand
    // Mini-Feature 10C : Bravo vend une carte vendable (Program) — jamais une Unit/Legend.
    const bravoSoldId = firstSellable(bravoHand).instanceId
    bravo.send(`/app/game/${gameId}/action`, { action: 'SELL_CARD', instanceId: bravoSoldId })
    await waitFor(
      () => playerOf(bravo.lastState(), 'Bravo').eddiesArea.some((c) => c.instanceId === bravoSoldId),
      'ressource de Bravo posée en Eddies Area',
    )
    expect(playerOf(bravo.lastState(), 'Bravo').eddies).toBe(0) // 0 ¤ immédiat (MF3)
    // R4 : Bravo incline sa ressource pour financer sa Unit.
    bravo.send(`/app/game/${gameId}/action`, { action: 'SPEND_RESOURCE', instanceId: bravoSoldId })
    await waitFor(() => playerOf(bravo.lastState(), 'Bravo').eddies === 1, 'Eddie de Bravo (après inclinaison)')

    const bravoUnit = playerOf(bravo.lastState(), 'Bravo').hand.find((card) => card.type === 'unit') as CardInstance
    bravo.send(`/app/game/${gameId}/action`, { action: 'PLAY_CARD', instanceId: bravoUnit.instanceId })
    await waitFor(() => playerOf(bravo.lastState(), 'Bravo').field.length === 1, 'Unit de Bravo posée')

    bravo.send(`/app/game/${gameId}/action`, { action: 'END_TURN' })
    await waitFor(() => game.turnNumber === 3, 'retour au tour 3 (Alpha)')
    expect(game.isMyTurn).toBe(true)

    // --- 7 bis. Ma phase DRAW interactive, via l'UI (Mini-Feature 5) ------
    // Étape AWAITING_DRAW : bandeau « PIOCHER VOTRE CARTE », pioche cliquable, fin de tour bloquée.
    expect(game.phase).toBe('DRAW')
    expect(game.awaitingMyDraw).toBe(true)
    expect(game.canEndTurn).toBe(false)
    expect(game.me?.hand).toHaveLength(5) // 6 + pioche tour 1 − vente − pose (avant la pioche du tour 3)
    await waitFor(() => wrapper.find('[data-draw-guide]').exists(), 'bandeau de guidage de la phase DRAW')
    expect(wrapper.get('[data-draw-guide]').attributes('data-step')).toBe('AWAITING_DRAW')
    expect(wrapper.get('[data-draw-guide]').text()).toContain('PIOCHER VOTRE CARTE')
    expect((buttonWith(wrapper, 'Fin de tour').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.find('[data-draw-guide] [data-die-option]').exists()).toBe(false)

    // Clic sur la pioche du tapis (le deck pulse et devient un bouton).
    const myDeck = wrapper.get('[data-board="me"] [data-zone="DECK"] .card-pile')
    expect(myDeck.attributes('data-clickable')).toBe('true')
    await myDeck.trigger('click')
    await waitFor(() => (game.me?.hand.length ?? 0) === 6, 'ma carte piochée')
    expect(actions().find((command) => command.action === 'DRAW_CARD')).toMatchObject({ action: 'DRAW_CARD' })

    // Étape AWAITING_DIE_SELECT : grille des dés, d20 grisé, les autres cliquables.
    await waitFor(() => game.drawStep === 'AWAITING_DIE_SELECT', 'choix du dé attendu')
    expect(game.selectableDice).toEqual(['d6', 'd8', 'd10', 'd12'])
    expect(game.canSelectDie('d20')).toMatch(/d20/)
    expect(wrapper.find('[data-board="me"] [data-zone="DECK"] .card-pile').attributes('data-clickable')).toBeUndefined()
    await waitFor(() => wrapper.find('[data-draw-guide] [data-die-option]').exists(), 'grille des dés affichée')
    expect(wrapper.get('[data-draw-guide]').text()).toContain('CHOISIS UN DÉ')
    expect(wrapper.findAll('[data-draw-guide] [data-die-option]')).toHaveLength(5)
    const d20Option = wrapper.get('[data-draw-guide] [data-die-option="d20"]')
    expect(d20Option.attributes('data-selectable')).toBe('false')
    expect((d20Option.element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.findAll('[data-board="me"] [data-fixer-die][data-selectable="true"]')).toHaveLength(4)
    expect(wrapper.get('[data-board="me"] [data-fixer-die="d20"]').attributes('data-locked')).toBe('true')

    // Clic sur le d8 → SELECT_DIE, le serveur lance et enchaîne sur la phase Main.
    await wrapper.get('[data-draw-guide] [data-die-option="d8"]').trigger('click')
    await waitFor(() => (game.me?.gigCount ?? 0) === 2, 'mon Gig lancé')
    // `actions()` capture aussi les commandes brutes de Bravo : on cible la mienne (d8).
    const myDieCommand = actions().find((command) => command.action === 'SELECT_DIE' && (command.dice as string[])?.[0] === 'd8')
    expect(myDieCommand).toMatchObject({ action: 'SELECT_DIE', dice: ['d8'] })
    expect(typeof myDieCommand?.clientRequestId).toBe('string')
    expect(game.me?.gigDice).toEqual(['d4', 'd8'])
    expect(game.me?.fixerDice).toEqual(['d6', 'd10', 'd12', 'd20'])
    await waitFor(() => game.phase === 'MAIN', 'ma phase Main')
    expect(game.drawStep).toBeNull()
    expect(game.canEndTurn).toBe(true)
    expect(wrapper.find('[data-draw-guide]').exists()).toBe(false)
    expect(game.me?.hand).toHaveLength(6) // 6 + pioche tour 1 − vente − pose + pioche tour 3
    expect((game.me?.gigCount ?? 0) + (game.opponent?.gigCount ?? 0)).toBe(3)
    expect(wrapper.get('[data-zone="GIGS"]').text()).toContain('d8')

    // --- 8. Attaque (Mini-Feature 6) : cible dépensée ou vol direct plafonné ---
    const attacker = game.me?.field.find((card) => card.type === 'unit') as CardInstance
    expect(game.canAttackWith(attacker)).toBeNull() // mal d'invocation levé au début du tour

    // La Unit de Bravo a été redressée au début de son tour : « Ready Units can't be
    // attacked » → elle n'est PAS une cible légale (seules les Units dépensées le sont).
    const rivalUnit = game.opponent?.field.find((card) => card.type === 'unit') as CardInstance
    expect(rivalUnit.exhausted).toBe(false)
    expect(game.validAttackTargets(attacker)).not.toContain(rivalUnit.instanceId)
    expect(game.validAttackTargets(attacker)).toEqual([])
    expect(game.canStealGig(attacker)).toBe(true)
    // Quota affiché côté client (le serveur reste seul juge) : Power 9 → N = 1,
    // Bravo n'a qu'un dé Gig actif → plafond strict M = 1.
    expect(game.stealForecast(attacker)).toEqual({ quota: 1, stealable: 1 })

    await wrapper.get(`[data-instance-id="${attacker.instanceId}"]`).trigger('click')
    await buttonWith(wrapper, 'Attaquer').trigger('click')
    await waitFor(() => game.targeting !== null, 'mode ciblage actif')
    expect(wrapper.text()).toContain('Choisis une Unit rivale dépensée à attaquer')
    expect(game.targeting?.candidates).toEqual([])
    expect(game.targeting?.allowDirect).toBe(true)

    // Attaque directe de la Gig Area → modale « Choisissez M dé(s) Gig à voler ».
    await wrapper.get('[data-targeting-direct]').trigger('click')
    await waitFor(() => game.iMustChooseStolenDice, 'modale de vol de dés (M = 1)')
    expect(wrapper.find('[data-steal-modal]').exists()).toBe(true)
    expect(wrapper.get('[data-steal-modal]').attributes('data-steal-quota')).toBe('1')
    expect(wrapper.get('[data-steal-modal]').attributes('data-steal-count')).toBe('1')
    expect(wrapper.get('[data-steal-modal]').text()).toContain('Choisissez 1 dé(s) Gig à voler')
    const rivalDie = game.stealableDice[0]
    expect(rivalDie?.die).toBe('d6')
    expect(rivalDie?.id).toBe(game.opponent?.gigDieIds?.[0])
    expect((buttonWith(wrapper, 'Voler').element as HTMLButtonElement).disabled).toBe(true)
    expect(game.canConfirmSteal()).toMatch(/exactement 1/)

    // Un seul dé actif chez Bravo : c'est lui qui est volé (type ET valeur conservés).
    await wrapper.get(`[data-steal-die="${rivalDie?.id}"]`).trigger('click')
    expect(game.stolenSelection).toEqual([rivalDie?.id])
    expect(game.canConfirmSteal()).toBeNull()
    expect((buttonWith(wrapper, 'Voler').element as HTMLButtonElement).disabled).toBe(false)
    await buttonWith(wrapper, 'Voler').trigger('click')
    await waitFor(() => game.pendingAttack === null, 'combat résolu après STEAL_GIG')

    const attackCommand = actions().find((command) => command.action === 'ATTACK')
    expect(attackCommand).toMatchObject({
      action: 'ATTACK',
      instanceId: attacker.instanceId,
    })
    // Attaque directe « sans targetInstanceId » (doc §5.1) : `compact()` retire
    // les champs nuls du payload filaire, comme le fait le serveur dans ses DTO.
    expect(attackCommand?.targetInstanceId).toBeUndefined()
    const stealCommand = actions().find((command) => command.action === 'STEAL_GIG')
    expect(stealCommand).toMatchObject({ action: 'STEAL_GIG', dice: [rivalDie?.id] })
    expect(typeof stealCommand?.clientRequestId).toBe('string')

    // Plafond strict respecté : Bravo perd son unique Gig, Alpha le récupère tel quel.
    expect(game.opponent?.gigCount).toBe(0)
    expect(game.opponent?.gigs).toEqual([])
    expect(game.me?.gigCount).toBe(3)
    const stolenAt = (game.me?.gigDice ?? []).indexOf('d6')
    expect(stolenAt).toBe(2) // d4 (tour 1), d8 (tour 3), puis le d6 volé
    expect(game.me?.gigs[stolenAt]).toBe(rivalDie?.value)
    expect(game.me?.gigDieIds).toHaveLength(3)
    expect(game.log.some((entry) => entry.type === 'GIG_STOLEN')).toBe(true)
    expect(game.targeting).toBeNull()
    expect(game.iMustChooseStolenDice).toBe(false)
    // Déclarer une attaque incline l'attaquant.
    expect((game.me?.field.find((card) => card.instanceId === attacker.instanceId) as CardInstance).exhausted).toBe(true)
    expect(game.phase).toBe('COMBAT')

    // --- 9. Fin de tour : la main passe à Bravo ---------------------------
    await buttonWith(wrapper, 'Fin de tour').trigger('click')
    await waitFor(() => game.turnNumber === 4, 'passage au tour 4')
    expect(game.activePlayerId).toBe('Bravo')
    expect(wrapper.text()).toContain('tour de Bravo')
    // Bravo repart en phase DRAW interactive : à lui de cliquer sur sa pioche.
    expect(game.phase).toBe('DRAW')
    expect(game.drawStep).toBe('AWAITING_DRAW')

    // Le journal complet est rendu (doc §7.4).
    expect(game.log.length).toBeGreaterThan(8)
    expect(wrapper.findAll('[data-anim="phase"]')).toHaveLength(1)
  })

  it('refuse une action illégale et affiche le message du serveur', async () => {
    const lobby = useLobbyStore()
    const game = useGameStore()
    const decks = useDeckStore()

    await wrapper.get('input[placeholder="Johnny"]').setValue('Alpha')
    await buttonWith(wrapper, 'Ouvrir le canal').trigger('click')
    await waitFor(() => lobby.isConnected, 'connexion STOMP')

    decks.setDeck(DECK_A)
    const alphaDeckIdR2 = server.registerSavedDeck('Alpha', DECK_A)
    lobby.selectDeck(alphaDeckIdR2)
    await tick(2)
    await buttonWith(wrapper, 'Créer le salon').trigger('click')
    await waitFor(() => lobby.room !== null, 'salon créé')

    const bravo = new RawClient(server, 'Bravo')
    bravo.connect()
    bravo.subscribe('/user/queue/errors')
    const bravoDeckId2 = server.registerSavedDeck('Bravo', DECK_B)
    bravo.send('/app/lobby.join', { roomCode: lobby.room?.code, deckId: bravoDeckId2 })

    await waitFor(() => game.state !== null, 'partie démarrée')
    const gameId = lobby.gameId ?? ''

    // Bravo n'est pas le joueur actif : le serveur refuse et lui répond en privé.
    // Les instanceId de la main adverse sont publics (doc §7.3) : on les réutilise.
    const bravoCard = game.opponent?.hand[0]
    bravo.send(`/app/game/${gameId}/action`, {
      action: 'SELL_CARD',
      instanceId: bravoCard?.instanceId,
      clientRequestId: 'req-illegal',
    })

    await waitFor(() => bravo.errors().length > 0, 'erreur privée ILLEGAL_ACTION')
    const error = bravo.errors()[0]
    expect(error).toMatchObject({ type: 'ERROR', code: 'ILLEGAL_ACTION', clientRequestId: 'req-illegal' })
    expect(String(error?.message)).toContain('pas le tour')

    // Aucune STATE n'est diffusée pour l'action fautive : le tour n'a pas bougé.
    expect(game.turnNumber).toBe(1)
    expect(game.activePlayerId).toBe('Alpha')
    expect(game.state?.sequence).toBe(1)
  })
})
