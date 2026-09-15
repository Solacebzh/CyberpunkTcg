#!/usr/bin/env node
/**
 * Backend simulé pour le développement du frontend (feature 05).
 *
 * Sert sur un seul port (8080 par défaut, comme Spring Boot) :
 *   • `GET /api/health`, `GET /api/cards[?type=&color=]`, `GET /api/cards/{id}`,
 *     `GET /api/cards/stats` — depuis le vrai catalogue embarqué
 *     (`backend/src/main/resources/data/cards.json`) ;
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
 * ⚠️ Les règles appliquées ici sont un sous-ensemble de démonstration ; le
 * serveur de référence reste le backend Spring (docs/RULE-ENGINE.md).
 */
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { WebSocketServer } from 'ws'

import { MockGameServer } from '../devtools/mock-protocol.ts'

const here = dirname(fileURLToPath(import.meta.url))
const PORT = Number(process.env.MOCK_PORT ?? 8080)
const HOST = process.env.MOCK_HOST ?? '0.0.0.0'
const CATALOG_PATH = join(here, '..', '..', 'backend', 'src', 'main', 'resources', 'data', 'cards.json')

const cards = JSON.parse(readFileSync(CATALOG_PATH, 'utf8'))
const server = new MockGameServer({ cards, seed: Number(process.env.MOCK_SEED ?? 7) })

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

const httpServer = createServer((request, response) => {
  const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`)

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
  console.log('[mock] ⚠️  moteur de règles simplifié — à utiliser pour l’UI uniquement')
})
