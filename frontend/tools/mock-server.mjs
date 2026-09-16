#!/usr/bin/env node
/**
 * Backend simulé pour le développement du frontend (feature 05).
 *
 * Sert sur un seul port (8080 par défaut, comme Spring Boot) :
 *   • `GET /api/health`, `GET /api/cards[?type=&color=]`, `GET /api/cards/{id}`,
 *     `GET /api/cards/stats` — depuis le vrai catalogue embarqué
 *     (`backend/src/main/resources/data/cards.json`) ;
 *   • `POST /api/auth/{register,login}` + `GET|POST|PUT|DELETE /api/decks` —
 *     comptes et decks persistés en mémoire, avec les règles officielles de
 *     deckbuilding rejouées avant écriture (`tools/mock-decks.mjs`,
 *     Mini-Feature 9C) ;
 *   • `WS /ws` — serveur STOMP simulé (`devtools/mock-protocol.ts`).
 *
 * Vite proxifie `/api` et `/ws` vers ce port : `npm run dev` + `npm run mock:ws`
 * donnent une application jouable de bout en bout **sans JVM ni PostgreSQL**.
 *
 * Les zones servies sont celles du protocole (`FIELD`, `HAND`, `TRASH`,
 * `EDDIES_AREA`, `LEGENDS_AREA`, `DECK` + `gigs`/`fixerDice`) : le layout du tapis
 * (« FIXER / GIGS / LEGENDS / EDDIES / TRASH / DECK ») est une traduction
 * d'affichage faite par le frontend (`src/types/playmat.ts`) — rien à renommer ici.
 *
 * Mini-Feature 5 : la phase DRAW est simulée **pas à pas** comme côté Spring —
 * après `END_TURN` la partie s'arrête à `turn.drawStep = AWAITING_DRAW`, puis
 * `DRAW_CARD` (pioche 1 carte) → `AWAITING_DIE_SELECT`, puis `SELECT_DIE`
 * (`dice: ['d6']`, d20 refusé tant qu'il reste d'autres dés) → lancer → `MAIN`.
 *
 * Mini-Feature 6 : le combat est simulé **pas à pas**, comme le moteur Java —
 * `ATTACK` incline l'attaquant et ne cible qu'une Unit rivale **dépensée** (ou la
 * Gig Area adverse). Si le défenseur a un `{Blocker}` prêt, l'état publié porte
 * `pendingAttack.step = AWAITING_BLOCK` : `USE_BLOCKER` (`cardIds`, ordre
 * significatif — le DERNIER Blocker encaisse les dégâts) ou `DECLINE_BLOCK`.
 * Une attaque directe non bloquée passe en `AWAITING_STEAL_CHOICE` avec le quota
 * `N = (power / 10) + 1` (0 si power ≤ 0) et le **plafond strict**
 * `M = min(N, dés Gigs actifs du défenseur)` : `STEAL_GIG` (`dice: [ids]`) exige
 * exactement M identifiants parmi `PlayerState.gigDieIds`, chaque dé conservant
 * son type et sa valeur. Les dés non lancés de la Fixer Area ne sont jamais
 * volés, M = 0 ne vole rien (l'attaque réussit quand même), et `END_TURN` résout
 * un combat en suspens (blocage refusé, puis vol des M dés de plus haute valeur).
 *
 * ⚠️ Les règles appliquées ici sont un sous-ensemble de démonstration ; le
 * serveur de référence reste le backend Spring (docs/RULE-ENGINE.md).
 */
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { WebSocketServer } from 'ws'

import { MockGameServer } from '../devtools/mock-protocol.ts'

import { createAccountDeckApi } from './mock-decks.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const PORT = Number(process.env.MOCK_PORT ?? 8080)
const HOST = process.env.MOCK_HOST ?? '0.0.0.0'
const CATALOG_PATH = join(here, '..', '..', 'backend', 'src', 'main', 'resources', 'data', 'cards.json')

const cards = JSON.parse(readFileSync(CATALOG_PATH, 'utf8'))
const server = new MockGameServer({ cards, seed: Number(process.env.MOCK_SEED ?? 7) })
const accounts = createAccountDeckApi({ cards })

console.log(`[mock] catalogue chargé : ${cards.length} cartes depuis ${CATALOG_PATH}`)

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload)
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Content-Length': Buffer.byteLength(body),
  })
  response.end(body)
}

const httpServer = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`)

  // Comptes + decks sauvegardés (Mini-Feature 9C) : mêmes routes que Spring.
  if (await accounts.handle(request, response, url)) return

  if (url.pathname === '/api/health') {
    return sendJson(response, 200, {
      status: 'UP',
      service: 'cyberpunk-tcg-mock',
      version: '0.0.0-mock',
      database: 'UP',
      timestamp: new Date().toISOString(),
    })
  }

  if (url.pathname === '/api/cards') {
    const type = url.searchParams.get('type')
    const color = url.searchParams.get('color')
    const filtered = cards.filter(
      (card) => (!type || card.type === type) && (!color || card.color === color),
    )
    return sendJson(response, 200, filtered)
  }

  if (url.pathname === '/api/cards/stats') {
    const count = (predicate) => cards.filter(predicate).length
    return sendJson(response, 200, {
      total: cards.length,
      byType: {
        legend: count((card) => card.type === 'legend'),
        unit: count((card) => card.type === 'unit'),
        program: count((card) => card.type === 'program'),
        gear: count((card) => card.type === 'gear'),
      },
    })
  }

  const byId = /^\/api\/cards\/([^/]+)$/.exec(url.pathname)
  if (byId) {
    const card = cards.find((item) => item.id === decodeURIComponent(byId[1]))
    return card ? sendJson(response, 200, card) : sendJson(response, 404, { error: 'Carte introuvable' })
  }

  return sendJson(response, 404, { error: `Route inconnue : ${url.pathname}` })
})

const wss = new WebSocketServer({ server: httpServer, path: '/ws' })

wss.on('connection', (socket) => {
  const session = server.createSession((frame) => {
    if (socket.readyState === socket.OPEN) socket.send(frame)
  })

  socket.on('message', (data) => session.receive(data.toString()))
  socket.on('close', () => session.close())
  socket.on('error', () => session.close())

  console.log('[mock] session WebSocket ouverte sur /ws')
})

httpServer.listen(PORT, HOST, () => {
  console.log(`[mock] API REST + STOMP simulés sur http://${HOST}:${PORT} (ws://${HOST}:${PORT}/ws)`)
  console.log(`[mock] comptes + decks en mémoire : /api/auth/*, /api/decks (règles de deckbuilding appliquées)`)
  console.log('[mock] ⚠️  moteur de règles simplifié — à utiliser pour l’UI uniquement')
})
