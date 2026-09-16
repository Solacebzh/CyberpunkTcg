/**
 * Contrat de combat (Mini-Feature 6) : attaque, blocage `{Blocker}` et vol de dés
 * à plafond strict, exercés de bout en bout sur le protocole WebSocket simulé
 * (`devtools/mock-protocol.ts`, miroir de `docs/WEBSOCKET-PROTOCOL.md`).
 *
 * Les deux sièges parlent STOMP « brut » (`RawClient`) : on vérifie le contrat
 * filaire des nouvelles actions — `USE_BLOCKER`, `DECLINE_BLOCK`, `STEAL_GIG` —
 * et l'état publié (`pendingAttack`, `gigDieIds`). Les règles elles-mêmes sont
 * testées côté backend (`CombatStealTest`, `mvn test`) ; ce fichier garantit que
 * le simulateur de dev raconte la même histoire que le moteur Java.
 *
 * Scénario déterministe (decks fabriqués pour la règle R6) :
 * - tour 1 : Alpha pose 2 `{Blocker}` power 3 ;
 * - tour 2 : Bravo (power 25, `{Go Solo}`) attaque → blocage MULTIPLE, seul le
 *   dernier Blocker déclaré encaisse, aucun Gig volé ;
 * - tour 4 : power 0 (quota N = 0 → M = 0) puis power 25 après renoncement au
 *   blocage (N = 3 **plafonné** à M = 2, les dés de la Fixer Area jamais touchés) ;
 * - tour 5 : Alpha (power 3 → N = 1) choisit LE dé volé (refus si ≠ M) ;
 * - tour 6 : plafond strict M = 2, puis attaque sur un défenseur à 0 dé actif ;
 * - tours 8-10 : Bravo atteint 7 Gigs, la victoire n'est déclarée qu'au tout
 *   début de sa phase DRAW suivante.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { RawClient, card, installMockServer, tick, uninstallMockServer } from './helpers/stompHarness'
import type { MockCard, MockGameServer } from '../../devtools/mock-protocol'
import type { GameLogEntry, PendingAttack, PlayerState } from '@/types/game'

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

/** Deck d'Alpha : que des `{Blocker}` power 3 (coût 1) — 14 pour tenir 9 tours. */
const BLOCKERS: MockCard[] = Array.from({ length: 14 }, (_, index) => ({
  id: `blocker-${index}`,
  name: `Mur ${index}`,
  type: 'unit',
  color: 'red',
  cost: 1,
  power: 3,
  streetCred: null,
  keywords: ['blocker'],
  abilities: [],
}))

/**
 * Deck de Bravo : 4 Units power 0 et 6 Units power 25, toutes `{Go Solo}`.
 * Avec 10 cartes (6 en main de départ + 1 pioche par tour), le scénario est sûr
 * de trouver les puissances voulues en main à chaque tour (pigeonhole).
 */
const ZEROES: MockCard[] = Array.from({ length: 4 }, (_, index) => ({
  id: `zero-${index}`,
  name: `Fixeur ${index}`,
  type: 'unit',
  color: 'blue',
  cost: 1,
  power: 0,
  streetCred: null,
  keywords: ['go_solo'],
  abilities: [],
}))

const HEAVIES: MockCard[] = Array.from({ length: 6 }, (_, index) => ({
  id: `heavy-${index}`,
  name: `Solo lourd ${index}`,
  type: 'unit',
  color: 'green',
  cost: 1,
  power: 25,
  streetCred: null,
  keywords: ['go_solo'],
  abilities: [],
}))

const CATALOG: MockCard[] = [...LEGENDS, ...BLOCKERS, ...ZEROES, ...HEAVIES].map(card)
const DECK_ALPHA = [...LEGENDS.map((item) => item.id), ...BLOCKERS.map((item) => item.id)]
const DECK_BRAVO = [...LEGENDS.map((item) => item.id), ...ZEROES.map((item) => item.id), ...HEAVIES.map((item) => item.id)]

// --- Utilitaires ------------------------------------------------------------

function seatOf(client: RawClient, playerId: string): PlayerState {
  const players = (client.lastState()?.players ?? []) as PlayerState[]
  const found = players.find((player) => player.playerId === playerId)
  if (!found) throw new Error(`Joueur ${playerId} absent de l'état`)
  return found
}

function pendingOf(client: RawClient): PendingAttack | null {
  return (client.lastState()?.pendingAttack as PendingAttack | undefined) ?? null
}

function logOf(client: RawClient): GameLogEntry[] {
  return (client.lastState()?.log ?? []) as GameLogEntry[]
}

const fieldOf = (client: RawClient, playerId: string) => seatOf(client, playerId).field
const handOf = (client: RawClient, playerId: string) => seatOf(client, playerId).hand
const gigsOf = (client: RawClient, playerId: string) => seatOf(client, playerId).gigs
const dieIdsOf = (client: RawClient, playerId: string) => seatOf(client, playerId).gigDieIds ?? []

function unitNamed(client: RawClient, playerId: string, instanceId: string) {
  const found = fieldOf(client, playerId).find((instance) => instance.instanceId === instanceId)
  if (!found) throw new Error(`Unit ${instanceId} absente du Field de ${playerId}`)
  return found
}

function readyBlockersOf(client: RawClient, playerId: string) {
  return fieldOf(client, playerId).filter(
    (instance) => instance.type === 'unit' && instance.keywords.includes('blocker') && !instance.exhausted,
  )
}

/** Envoie une action ; le simulateur répond de façon synchrone (tick de sécurité). */
async function act(client: RawClient, gameId: string, payload: Record<string, unknown>): Promise<void> {
  client.send(`/app/game/${gameId}/action`, payload)
  await tick(2)
}

/** Envoie une action attendue refusée et renvoie le message d'erreur privé. */
async function refused(
  client: RawClient,
  gameId: string,
  payload: Record<string, unknown>,
  requestId: string,
): Promise<string> {
  await act(client, gameId, { ...payload, clientRequestId: requestId })
  const error = client.errors().find((entry) => entry.clientRequestId === requestId)
  if (!error) throw new Error(`Action attendue refusée (${requestId}) : aucune erreur reçue`)
  return String(error.message ?? '')
}

/** Phase DRAW interactive : pioche puis lancer du dé choisi. */
async function takeDrawPhase(client: RawClient, gameId: string, die: string): Promise<void> {
  await act(client, gameId, { action: 'DRAW_CARD' })
  await act(client, gameId, { action: 'SELECT_DIE', dice: [die] })
}

/** 1 vente (0 ¤ immédiat) puis inclinaison de la ressource : +1 Eddie (R4). */
async function sellForEddie(client: RawClient, gameId: string, playerId: string, power?: number): Promise<void> {
  const sold = power === undefined
    ? handOf(client, playerId)[0]
    : handOf(client, playerId).find((instance) => instance.type === 'unit' && instance.power === power)
  if (!sold) throw new Error(`Rien à vendre en main de ${playerId} (power ${power ?? '—'})`)
  await act(client, gameId, { action: 'SELL_CARD', instanceId: sold.instanceId })
  await act(client, gameId, { action: 'SPEND_RESOURCE', instanceId: sold.instanceId })
}

/** Incliner une Legend prête : +1 Eddie (R4), pour financer une seconde Unit. */
async function inclineLegend(client: RawClient, gameId: string, playerId: string): Promise<void> {
  const legend = seatOf(client, playerId).legendsArea.find((instance) => !instance.exhausted)
  if (!legend) throw new Error(`Aucune Legend prête à incliner pour ${playerId}`)
  await act(client, gameId, { action: 'SPEND_RESOURCE', instanceId: legend.instanceId })
}

/** Pose une Unit de la puissance voulue (coût 1) et renvoie son instanceId. */
async function playUnit(client: RawClient, gameId: string, playerId: string, power: number): Promise<string> {
  const unit = handOf(client, playerId).find((instance) => instance.type === 'unit' && instance.power === power)
  if (!unit) throw new Error(`Aucune Unit power ${power} en main de ${playerId}`)
  await act(client, gameId, { action: 'PLAY_CARD', instanceId: unit.instanceId })
  if (!fieldOf(client, playerId).some((instance) => instance.instanceId === unit.instanceId)) {
    throw new Error(`La Unit power ${power} n'a pas atteint le Field de ${playerId}`)
  }
  return unit.instanceId
}

/** Total des dés Gigs actifs des deux sièges : le vol ne crée ni ne détruit de dé. */
function activeDiceTotal(client: RawClient): number {
  return gigsOf(client, 'Alpha').length + gigsOf(client, 'Bravo').length
}

// --- Suite ------------------------------------------------------------------

describe('Combat, blocage et vol de dés à plafond strict (contrat WebSocket)', () => {
  let server: MockGameServer
  let alpha: RawClient
  let bravo: RawClient
  let gameId: string

  beforeEach(async () => {
    server = installMockServer(CATALOG)
    alpha = new RawClient(server, 'Alpha')
    alpha.connect()
    alpha.subscribe('/user/queue/lobby')
    alpha.subscribe('/user/queue/errors')
    const alphaDeckId = server.registerSavedDeck('Alpha', DECK_ALPHA);
    alpha.send('/app/lobby.create', { roomName: 'Combat R6', deckId: alphaDeckId })
    const created = alpha.messages.find((message) => message.body.type === 'LOBBY_STATE')
    const roomCode = String(created?.body.code ?? '')
    expect(roomCode).toMatch(/^[A-Z2-9]{6}$/)

    bravo = new RawClient(server, 'Bravo')
    bravo.connect()
    bravo.subscribe('/user/queue/lobby')
    bravo.subscribe('/user/queue/errors')
    const bravoDeckId = server.registerSavedDeck('Bravo', DECK_BRAVO);
    bravo.send('/app/lobby.join', { roomCode, deckId: bravoDeckId })

    const lobby = alpha.messages.filter((message) => message.body.type === 'LOBBY_STATE').pop()
    gameId = String(lobby?.body.gameId ?? '')
    expect(gameId).toMatch(/^game-/)

    for (const client of [alpha, bravo]) {
      client.subscribe(`/topic/game/${gameId}/${client.pseudo}`)
      client.subscribe(`/topic/game/${gameId}`)
      client.subscribe(`/topic/game/${gameId}/log`)
      client.send(`/app/game/${gameId}/resync`, {})
    }
    await tick(2)
    expect(alpha.lastState()?.turn).toMatchObject({ number: 1, activePlayerId: 'Alpha', drawStep: 'AWAITING_DRAW' })
    expect(pendingOf(alpha)).toBeNull()
  })

  afterEach(() => {
    alpha.disconnect()
    bravo.disconnect()
    uninstallMockServer()
  })

  it('R6 — cible dépensée, blocage multiple, quota, plafond strict et victoire à 7 Gigs', async () => {
    // --- Tour 1 (Alpha) : pioche, dé, puis DEUX {Blocker} (2 Eddies) -------
    await takeDrawPhase(alpha, gameId, 'd6')
    expect(gigsOf(alpha, 'Alpha')).toHaveLength(1)
    expect(dieIdsOf(alpha, 'Alpha')).toHaveLength(1)
    await sellForEddie(alpha, gameId, 'Alpha')
    await inclineLegend(alpha, gameId, 'Alpha')
    expect(seatOf(alpha, 'Alpha').eddies).toBe(2)
    const blockerA = await playUnit(alpha, gameId, 'Alpha', 3)
    const blockerB = await playUnit(alpha, gameId, 'Alpha', 3)
    expect(blockerA).not.toBe(blockerB)
    expect(fieldOf(alpha, 'Alpha')).toHaveLength(2)
    await act(alpha, gameId, { action: 'END_TURN' })
    expect(alpha.lastState()?.turn).toMatchObject({ number: 2, activePlayerId: 'Bravo' })

    // --- Tour 2 (Bravo) : power 25 {Go Solo} attaque la Gig Area -----------
    await takeDrawPhase(bravo, gameId, 'd6')
    await sellForEddie(bravo, gameId, 'Bravo', 25)
    const heavy2 = await playUnit(bravo, gameId, 'Bravo', 25)

    // « Ready Units can't be attacked » : le Blocker prêt d'Alpha n'est pas ciblable.
    const readyBlocker = readyBlockersOf(bravo, 'Alpha')[0]
    const readyRefusal = await refused(
      bravo,
      gameId,
      { action: 'ATTACK', instanceId: heavy2, targetInstanceId: readyBlocker.instanceId },
      'r6-ready-target',
    )
    expect(readyRefusal).toMatch(/dépensée|prête/)
    expect(fieldOf(bravo, 'Alpha')).toHaveLength(2)
    expect(unitNamed(bravo, 'Bravo', heavy2).exhausted).toBe(false)

    // Attaque directe : c'est au DÉFENSEUR de décider — étape AWAITING_BLOCK publiée.
    await act(bravo, gameId, { action: 'ATTACK', instanceId: heavy2 })
    expect(pendingOf(bravo)).toMatchObject({
      attackerPlayerId: 'Bravo',
      defendingPlayerId: 'Alpha',
      attackerInstanceId: heavy2,
      step: 'AWAITING_BLOCK',
      quota: 0,
      stealableCount: 0,
      blockerInstanceIds: [],
    })
    expect(pendingOf(bravo)?.targetInstanceId ?? null).toBeNull()
    expect(unitNamed(bravo, 'Bravo', heavy2).exhausted).toBe(true) // déclarer incline
    expect(bravo.lastState()?.phase).toBe('COMBAT')
    // Une attaque est en cours : aucune autre ne peut être déclarée.
    expect(await refused(bravo, gameId, { action: 'ATTACK', instanceId: heavy2 }, 'r6-attack-twice'))
      .toMatch(/déjà en cours|épuisée/)

    // Seul le défenseur bloque ; l'attaquant ne choisit pas encore de dés.
    expect(await refused(bravo, gameId, { action: 'USE_BLOCKER', cardIds: [readyBlocker.instanceId] }, 'r6-block-attacker'))
      .toMatch(/défenseur/)
    expect(await refused(bravo, gameId, { action: 'STEAL_GIG', dice: ['gig-0001'] }, 'r6-steal-too-early'))
      .toMatch(/AWAITING_STEAL_CHOICE|attaque/i)

    // Blocage MULTIPLE : les deux Blockers sont dépensés, le DERNIER encaisse.
    const ready = readyBlockersOf(alpha, 'Alpha')
    expect(ready).toHaveLength(2)
    const [first, second] = ready
    await act(alpha, gameId, { action: 'USE_BLOCKER', cardIds: [first.instanceId, second.instanceId] })
    expect(pendingOf(alpha)).toBeNull()
    expect(logOf(alpha).filter((entry) => entry.type === 'ATTACK_BLOCKED')).toHaveLength(2)
    // Power 25 > 3 : seul le dernier Blocker déclaré tombe, le premier survit incliné.
    expect(fieldOf(alpha, 'Alpha')).toHaveLength(1)
    expect(fieldOf(alpha, 'Alpha')[0]?.instanceId).toBe(first.instanceId)
    expect(fieldOf(alpha, 'Alpha')[0]?.exhausted).toBe(true)
    expect(seatOf(alpha, 'Alpha').trash.map((instance) => instance.instanceId)).toEqual([second.instanceId])
    // Une attaque redirigée ne vole JAMAIS de Gig.
    expect(gigsOf(alpha, 'Alpha')).toHaveLength(1)
    expect(gigsOf(bravo, 'Bravo')).toHaveLength(1)
    expect(logOf(bravo).some((entry) => entry.type === 'GIG_STOLEN')).toBe(false)
    await act(bravo, gameId, { action: 'END_TURN' })

    // --- Tour 3 (Alpha) : second duo de Blockers ---------------------------
    await takeDrawPhase(alpha, gameId, 'd8')
    expect(gigsOf(alpha, 'Alpha')).toHaveLength(2)
    await sellForEddie(alpha, gameId, 'Alpha')
    await inclineLegend(alpha, gameId, 'Alpha')
    await playUnit(alpha, gameId, 'Alpha', 3)
    await playUnit(alpha, gameId, 'Alpha', 3)
    // Le survivant du tour 2 est redressé au début du tour : 3 Blockers prêts.
    expect(readyBlockersOf(alpha, 'Alpha')).toHaveLength(3)
    await act(alpha, gameId, { action: 'END_TURN' })

    // --- Tour 4 (Bravo) : power 0 (N = 0) puis power 25 (N = 3, M = 2) -----
    await takeDrawPhase(bravo, gameId, 'd8')
    await sellForEddie(bravo, gameId, 'Bravo', 0)
    await inclineLegend(bravo, gameId, 'Bravo')
    const zero4 = await playUnit(bravo, gameId, 'Bravo', 0)
    const heavy4 = await playUnit(bravo, gameId, 'Bravo', 25)
    expect(seatOf(bravo, 'Alpha').fixerDice).toEqual(['d4', 'd10', 'd12', 'd20'])

    // Power 0 → quota N = 0 → M = 0 : l'attaque réussit mais ne vole rien.
    await act(bravo, gameId, { action: 'ATTACK', instanceId: zero4 })
    expect(pendingOf(bravo)?.step).toBe('AWAITING_BLOCK')
    await act(alpha, gameId, { action: 'DECLINE_BLOCK' })
    expect(pendingOf(alpha)).toBeNull()
    expect(gigsOf(alpha, 'Alpha')).toHaveLength(2)
    expect(gigsOf(bravo, 'Bravo')).toHaveLength(2)
    expect(unitNamed(bravo, 'Bravo', zero4).exhausted).toBe(true)
    expect(logOf(alpha).some((entry) => entry.type === 'ATTACK_DECLARED' && /sans vol/.test(entry.description))).toBe(true)

    // Power 25 → N = 3, mais Alpha n'a que 2 dés actifs → plafond strict M = 2.
    await act(bravo, gameId, { action: 'ATTACK', instanceId: heavy4 })
    expect(pendingOf(bravo)?.step).toBe('AWAITING_BLOCK')
    // Une carte adverse (ou sans {Blocker}) ne peut pas bloquer.
    expect(await refused(alpha, gameId, { action: 'USE_BLOCKER', cardIds: [zero4] }, 'r6-block-not-blocker'))
      .toMatch(/BLOCKER|introuvable/)
    // Même Blocker désigné deux fois : refusé (pas de double dépense).
    const firstReady = readyBlockersOf(alpha, 'Alpha')[0]
    expect(
      await refused(
        alpha,
        gameId,
        { action: 'USE_BLOCKER', cardIds: [firstReady.instanceId, firstReady.instanceId] },
        'r6-block-double',
      ),
    ).toMatch(/double/)
    expect(readyBlockersOf(alpha, 'Alpha')).toHaveLength(3) // rien n'a été dépensé
    await act(alpha, gameId, { action: 'DECLINE_BLOCK' })
    expect(pendingOf(alpha)).toMatchObject({
      step: 'AWAITING_STEAL_CHOICE',
      attackerPlayerId: 'Bravo',
      defendingPlayerId: 'Alpha',
      quota: 3,
      stealableCount: 2,
    })

    // Le défenseur ne choisit pas les dés ; l'attaquant doit en prendre exactement M.
    expect(await refused(alpha, gameId, { action: 'STEAL_GIG', dice: [] }, 'r6-steal-not-attacker')).toMatch(/attaquant/)
    const alphaDice = dieIdsOf(bravo, 'Alpha')
    expect(alphaDice).toHaveLength(2)
    expect(await refused(bravo, gameId, { action: 'STEAL_GIG', dice: [alphaDice[0]] }, 'r6-steal-one'))
      .toMatch(/exactement 2/)
    expect(await refused(bravo, gameId, { action: 'STEAL_GIG', dice: [...alphaDice, 'gig-inconnu'] }, 'r6-steal-three'))
      .toMatch(/exactement 2/)
    expect(await refused(bravo, gameId, { action: 'STEAL_GIG', dice: [alphaDice[0], alphaDice[0]] }, 'r6-steal-double'))
      .toMatch(/exactement 2|double/)

    // Vol de 2 dés : type, valeur et identifiant conservés, dans l'ordre demandé.
    const before = {
      dice: [...(seatOf(bravo, 'Alpha').gigDice ?? [])],
      values: [...gigsOf(bravo, 'Alpha')],
      ids: [...alphaDice],
      total: activeDiceTotal(bravo),
      fixer: [...seatOf(bravo, 'Alpha').fixerDice],
    }
    await act(bravo, gameId, { action: 'STEAL_GIG', dice: [alphaDice[0], alphaDice[1]] })
    expect(pendingOf(bravo)).toBeNull()
    expect(gigsOf(bravo, 'Alpha')).toHaveLength(0)
    expect(gigsOf(bravo, 'Bravo')).toHaveLength(4)
    expect(seatOf(bravo, 'Bravo').gigDice?.slice(2)).toEqual(before.dice)
    expect(gigsOf(bravo, 'Bravo').slice(2)).toEqual(before.values)
    expect(dieIdsOf(bravo, 'Bravo').slice(2)).toEqual(before.ids)
    // Aucun dé créé, aucun dé détruit, réserve de la Fixer Area intacte.
    expect(activeDiceTotal(bravo)).toBe(before.total)
    expect(seatOf(bravo, 'Alpha').fixerDice).toEqual(before.fixer)
    expect(logOf(bravo).filter((entry) => entry.type === 'GIG_STOLEN')).toHaveLength(2)
    await act(bravo, gameId, { action: 'END_TURN' })

    // --- Tour 5 (Alpha) : power 3 → N = 1, l'attaquant choisit LE dé -------
    await takeDrawPhase(alpha, gameId, 'd10')
    expect(gigsOf(alpha, 'Alpha')).toHaveLength(1)
    await sellForEddie(alpha, gameId, 'Alpha')
    const attacker5 = readyBlockersOf(alpha, 'Alpha')[0]
    await act(alpha, gameId, { action: 'ATTACK', instanceId: attacker5.instanceId })
    // Bravo n'a aucun {Blocker} : le choix des dés s'ouvre immédiatement.
    expect(pendingOf(alpha)).toMatchObject({
      step: 'AWAITING_STEAL_CHOICE',
      attackerPlayerId: 'Alpha',
      defendingPlayerId: 'Bravo',
      quota: 1,
      stealableCount: 1,
    })
    const bravoDice = dieIdsOf(alpha, 'Bravo')
    expect(bravoDice).toHaveLength(4)
    const chosen = bravoDice[2] as string // un dé volé au tour 4 : il repart tel quel
    const chosenValue = gigsOf(alpha, 'Bravo')[2] as number
    expect(await refused(alpha, gameId, { action: 'STEAL_GIG', dice: [chosen, bravoDice[3]] }, 'r6-steal-two'))
      .toMatch(/exactement 1/)
    expect(await refused(alpha, gameId, { action: 'STEAL_GIG', dice: ['gig-inexistant'] }, 'r6-steal-foreign'))
      .toMatch(/actif/)
    await act(alpha, gameId, { action: 'STEAL_GIG', dice: [chosen] })
    expect(pendingOf(alpha)).toBeNull()
    expect(gigsOf(alpha, 'Alpha')).toHaveLength(2)
    expect(gigsOf(alpha, 'Bravo')).toHaveLength(3)
    expect(dieIdsOf(alpha, 'Bravo')).not.toContain(chosen)
    expect(dieIdsOf(alpha, 'Alpha')).toContain(chosen)
    expect(gigsOf(alpha, 'Alpha')[1]).toBe(chosenValue)
    await act(alpha, gameId, { action: 'END_TURN' })

    // --- Tour 6 (Bravo) : M = 2 (plafond) puis défenseur à 0 dé actif -----
    await takeDrawPhase(bravo, gameId, 'd10')
    await sellForEddie(bravo, gameId, 'Bravo', 25)
    const heavy6 = await playUnit(bravo, gameId, 'Bravo', 25)
    await act(bravo, gameId, { action: 'ATTACK', instanceId: heavy6 })
    expect(pendingOf(bravo)?.step).toBe('AWAITING_BLOCK')
    await act(alpha, gameId, { action: 'DECLINE_BLOCK' })
    expect(pendingOf(bravo)).toMatchObject({ step: 'AWAITING_STEAL_CHOICE', quota: 3, stealableCount: 2 })
    const remaining = dieIdsOf(bravo, 'Alpha')
    expect(remaining).toHaveLength(2)
    await act(bravo, gameId, { action: 'STEAL_GIG', dice: remaining })
    expect(gigsOf(bravo, 'Alpha')).toHaveLength(0)
    expect(gigsOf(bravo, 'Bravo')).toHaveLength(6)

    // Défenseur à 0 dé actif : l'attaque RÉUSSIT, aucun dé fictif n'est créé et
    // la Fixer Area n'est jamais pillée.
    const fixerBefore = [...seatOf(bravo, 'Alpha').fixerDice]
    expect(unitNamed(bravo, 'Bravo', heavy2).exhausted).toBe(false) // redressée au début du tour
    await act(bravo, gameId, { action: 'ATTACK', instanceId: heavy2 })
    expect(pendingOf(bravo)?.step).toBe('AWAITING_BLOCK')
    await act(alpha, gameId, { action: 'DECLINE_BLOCK' })
    expect(pendingOf(bravo)).toBeNull()
    expect(gigsOf(bravo, 'Alpha')).toHaveLength(0)
    expect(seatOf(bravo, 'Alpha').fixerDice).toEqual(fixerBefore)
    expect(gigsOf(bravo, 'Bravo')).toHaveLength(6)
    expect(unitNamed(bravo, 'Bravo', heavy2).exhausted).toBe(true)
    expect(logOf(bravo).some((entry) => entry.type === 'ATTACK_DECLARED' && /sans vol/.test(entry.description))).toBe(true)
    await act(bravo, gameId, { action: 'END_TURN' })

    // --- Tour 7 (Alpha) : la victoire se joue au début de la DRAW suivante --
    await takeDrawPhase(alpha, gameId, 'd12')
    expect(gigsOf(alpha, 'Bravo')).toHaveLength(6)
    await act(alpha, gameId, { action: 'END_TURN' })

    // --- Tour 8 (Bravo) : 7 Gigs, mais la partie continue (vérification R6 au
    // tout début de la phase DRAW, jamais en cours de tour) -----------------
    expect(alpha.lastState()?.turn).toMatchObject({ number: 8, activePlayerId: 'Bravo' })
    await takeDrawPhase(bravo, gameId, 'd12')
    expect(gigsOf(bravo, 'Bravo')).toHaveLength(7)
    expect(bravo.lastState()?.gameOver).toBe(false)
    await act(bravo, gameId, { action: 'END_TURN' })

    // --- Tour 9 (Alpha) : 1 dé, pas de victoire adverse rétroactive ---------
    expect(alpha.lastState()?.turn).toMatchObject({ number: 9, activePlayerId: 'Alpha' })
    expect(alpha.lastState()?.gameOver).toBe(false)
    await takeDrawPhase(alpha, gameId, 'd4')
    await act(alpha, gameId, { action: 'END_TURN' })

    // --- Tour 10 : Bravo commence sa phase DRAW avec 7 Gigs → victoire ------
    expect(bravo.lastState()?.gameOver).toBe(true)
    expect(bravo.lastState()?.winnerId).toBe('Bravo')
    expect(String(bravo.lastState()?.endReason ?? '')).toContain('7 Gigs')
    expect(gigsOf(bravo, 'Bravo')).toHaveLength(7)
    expect(logOf(bravo).some((entry) => entry.type === 'GAME_WON')).toBe(true)
    expect(await refused(bravo, gameId, { action: 'DRAW_CARD' }, 'r6-after-win')).toMatch(/terminée/)
  })
})
