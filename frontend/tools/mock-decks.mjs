/**
 * Comptes + decks simulés pour le développement du frontend **sans JVM**.
 *
 * Miroir de démonstration du backend Spring (Mini-Feature 9C) :
 *   • `POST /api/auth/register` / `POST /api/auth/login` → jeton + identifiant ;
 *   • `GET /api/decks`, `POST /api/decks`, `PUT /api/decks/{id}`,
 *     `DELETE /api/decks/{id}` → protégés par le jeton, limités au compte courant ;
 *   • les règles officielles de deckbuilding (3 Legends uniques, Main Deck 40-50,
 *     max 3 exemplaires, plafonds de RAM par couleur) sont rejouées **avant**
 *     l'écriture : un deck illégal reçoit `400` + la liste des infractions,
 *     exactement comme `DeckService` + `RestExceptionHandler` côté Java.
 *
 * ⚠️ Tout est en mémoire (rien n'est chiffré, aucun mot de passe réel) : c'est un
 * outil d'UI, la référence reste le backend (`docs/RULE-ENGINE.md`).
 */

const REQUIRED_LEGENDS = 3
const MAIN_DECK_MIN = 40
const MAIN_DECK_MAX = 50
const MAX_COPIES_PER_CARD = 3
const MAX_SUBMITTED_CARDS = 100

function sendJson(response, status, payload) {
  const body = payload === undefined ? '' : JSON.stringify(payload)
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Content-Length': Buffer.byteLength(body),
  })
  response.end(body)
}

/** Corps `400` identique à celui de `RestExceptionHandler` (Bean Validation). */
function beanValidationError(fieldMessage) {
  return {
    status: 400,
    code: 'VALIDATION_ERROR',
    message: `Requête invalide : ${fieldMessage}`,
    errors: [fieldMessage],
  }
}

function readJsonBody(request) {
  return new Promise((resolve) => {
    const chunks = []
    request.on('data', (chunk) => chunks.push(chunk))
    request.on('error', () => resolve(null))
    request.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8')
      if (!text.trim()) return resolve(null)
      try {
        resolve(JSON.parse(text))
      } catch {
        resolve(null)
      }
    })
  })
}

/**
 * @param {{ cards: Array<Record<string, any>> }} options catalogue embarqué
 */
export function createAccountDeckApi({ cards }) {
  const cardsById = new Map(cards.map((card) => [card.id, card]))
  /** @type {Map<string, { id: number, username: string, password: string }>} */
  const users = new Map()
  /**
   * Decks en mémoire : même forme que `DeckResponse` côté Spring.
   * @type {Array<{ id: number, name: string, userId: number, cardIds: string[],
   *   createdAt: string, updatedAt: string }>}
   */
  const decks = []
  let nextUserId = 1
  let nextDeckId = 1

  // --- Jeton « JWT » de démonstration : pseudo encodé, aucune signature réelle ---
  function issueToken(username) {
    return `mock-jwt.${Buffer.from(username, 'utf8').toString('base64url')}.demo`
  }

  function usernameOf(request) {
    const header = request.headers.authorization
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null
    const payload = header.slice('Bearer '.length).split('.')[1]
    if (!payload) return null
    try {
      return Buffer.from(payload, 'base64url').toString('utf8')
    } catch {
      return null
    }
  }

  function currentUser(request) {
    const username = usernameOf(request)
    return username ? (users.get(username) ?? null) : null
  }

  // --- Règles officielles (miroir de DeckValidator) ---
  function validateDeck(cardIds) {
    const errors = []
    const unknown = [...new Set(cardIds.filter((id) => !cardsById.has(id)))]
    if (unknown.length > 0) {
      errors.push(`Carte(s) inconnue(s) dans le deck : ${unknown.join(', ')}`)
    }

    const resolved = cardIds.map((id) => cardsById.get(id)).filter((card) => Boolean(card))
    const legends = resolved.filter((card) => card.type === 'legend')
    const mainDeck = resolved.filter((card) => card.type !== 'legend')

    if (legends.length !== REQUIRED_LEGENDS) {
      errors.push(
        `Le deck doit contenir exactement ${REQUIRED_LEGENDS} Legends (actuellement : ${legends.length})`,
      )
    }
    const seenLegendIds = new Set()
    const seenLegendNames = new Set()
    for (const legend of legends) {
      if (seenLegendIds.has(legend.id)) errors.push(`Legend en double interdite (identifiant : ${legend.id})`)
      seenLegendIds.add(legend.id)
      const name = String(legend.name ?? '').trim().toLowerCase()
      if (name && seenLegendNames.has(name)) errors.push(`Legend en double interdite (nom : ${legend.name})`)
      if (name) seenLegendNames.add(name)
    }

    if (mainDeck.length < MAIN_DECK_MIN || mainDeck.length > MAIN_DECK_MAX) {
      errors.push(
        `Le Main Deck doit contenir entre ${MAIN_DECK_MIN} et ${MAIN_DECK_MAX} cartes (actuellement : ${mainDeck.length})`,
      )
    }

    const copies = new Map()
    for (const card of mainDeck) copies.set(card.id, (copies.get(card.id) ?? 0) + 1)
    for (const [id, count] of copies) {
      if (count > MAX_COPIES_PER_CARD) {
        errors.push(
          `Maximum ${MAX_COPIES_PER_CARD} exemplaires de la même carte autorisés : '${cardsById.get(id)?.name ?? id}' apparaît ${count} fois`,
        )
      }
    }

    const ceilings = { red: 0, green: 0, blue: 0, yellow: 0 }
    for (const legend of legends) ceilings[legend.color] = (ceilings[legend.color] ?? 0) + (legend.ram ?? 0)
    for (const card of mainDeck) {
      const ceiling = ceilings[card.color] ?? 0
      if (ceiling <= 0) {
        errors.push(
          `La carte '${card.name}' est de couleur ${card.color} dont le plafond de RAM est 0 (couleur interdite dans le deck)`,
        )
      } else if ((card.ram ?? 0) > ceiling) {
        errors.push(
          `Le coût en RAM de la carte '${card.name}' (${card.ram} RAM) dépasse le plafond ${card.color} de ${ceiling} RAM`,
        )
      }
    }
    return errors
  }

  function normalizeIds(raw) {
    if (!Array.isArray(raw)) return []
    return raw.filter((id) => typeof id === 'string' && id.trim().length > 0).map((id) => id.trim())
  }

  function deckErrors(name, cardIds) {
    if (!name) return ['Le nom du deck est obligatoire']
    if (name.length > 80) return ['Le nom du deck ne peut pas dépasser 80 caractères']
    if (cardIds.length === 0) return ['Le deck doit contenir au moins une carte']
    if (cardIds.length > MAX_SUBMITTED_CARDS) {
      return [`Le deck ne peut pas contenir plus de ${MAX_SUBMITTED_CARDS} cartes (reçu : ${cardIds.length})`]
    }
    return validateDeck(cardIds)
  }

  function toResponse(deck) {
    return { ...deck, userId: deck.userId, totalCards: deck.cardIds.length }
  }

  /**
   * Traite la requête si elle concerne les comptes ou les decks.
   * @returns {Promise<boolean>} `true` si la réponse a été écrite
   */
  async function handle(request, response, url) {
    const path = url.pathname

    if (path === '/api/auth/register' || path === '/api/auth/login') {
      const body = (await readJsonBody(request)) ?? {}
      const username = String(body.username ?? '').trim()
      const password = String(body.password ?? '')
      if (username.length < 3 || username.length > 50) {
        sendJson(response, 400, beanValidationError('username : Username must be between 3 and 50 characters'))
        return true
      }
      if (password.length < 6) {
        sendJson(response, 400, beanValidationError('password : Password must be at least 6 characters'))
        return true
      }

      if (path === '/api/auth/register') {
        if (users.has(username)) {
          sendJson(response, 409, { status: 409, message: `Username already exists: ${username}` })
          return true
        }
        const user = { id: nextUserId++, username, password }
        users.set(username, user)
        sendJson(response, 201, { token: issueToken(username), id: user.id, username })
        return true
      }

      const existing = users.get(username)
      if (!existing || existing.password !== password) {
        sendJson(response, 401, { status: 401, message: 'Invalid username or password' })
        return true
      }
      sendJson(response, 200, { token: issueToken(username), id: existing.id, username })
      return true
    }

    if (path === '/api/decks' || /^\/api\/decks\/\d+$/.test(path)) {
      const user = currentUser(request)
      if (!user) {
        sendJson(response, 403, { status: 403, message: 'Accès refusé : jeton manquant ou invalide' })
        return true
      }

      const method = request.method ?? 'GET'
      const deckIdMatch = /^\/api\/decks\/(\d+)$/.exec(path)
      const deckId = deckIdMatch ? Number(deckIdMatch[1]) : null

      if (method === 'GET' && deckId === null) {
        const mine = decks
          .filter((deck) => deck.userId === user.id)
          .sort((left, right) => left.name.localeCompare(right.name) || left.id - right.id)
          .map(toResponse)
        sendJson(response, 200, mine)
        return true
      }

      if (method === 'GET' && deckId !== null) {
        const deck = decks.find((item) => item.id === deckId && item.userId === user.id)
        if (!deck) {
          sendJson(response, 404, { status: 404, message: `Deck introuvable : ${deckId}` })
          return true
        }
        sendJson(response, 200, toResponse(deck))
        return true
      }

      if (method === 'POST' && deckId === null) {
        const body = (await readJsonBody(request)) ?? {}
        const name = String(body.name ?? '').trim()
        const cardIds = normalizeIds(body.cardIds)
        // Un nom absent est refusé par Bean Validation côté Spring (avant le service).
        if (!name) {
          sendJson(response, 400, beanValidationError('name : Le nom du deck est obligatoire'))
          return true
        }
        const errors = deckErrors(name, cardIds)
        if (errors.length > 0) {
          sendJson(response, 400, {
            status: 400,
            code: 'DECK_INVALID',
            message: `Deck invalide : ${errors.join(' ; ')}`,
            errors,
          })
          return true
        }
        const now = new Date().toISOString()
        const deck = { id: nextDeckId++, name, userId: user.id, cardIds, createdAt: now, updatedAt: now }
        decks.push(deck)
        console.log(`[mock] deck #${deck.id} « ${deck.name} » sauvegardé pour ${user.username} (${cardIds.length} cartes)`)
        sendJson(response, 201, toResponse(deck))
        return true
      }

      if (method === 'PUT' && deckId !== null) {
        const deck = decks.find((item) => item.id === deckId && item.userId === user.id)
        if (!deck) {
          sendJson(response, 404, { status: 404, message: `Deck introuvable : ${deckId}` })
          return true
        }
        const body = (await readJsonBody(request)) ?? {}
        const name = String(body.name ?? '').trim()
        const cardIds = normalizeIds(body.cardIds)
        if (!name) {
          sendJson(response, 400, beanValidationError('name : Le nom du deck est obligatoire'))
          return true
        }
        const errors = deckErrors(name, cardIds)
        if (errors.length > 0) {
          sendJson(response, 400, {
            status: 400,
            code: 'DECK_INVALID',
            message: `Deck invalide : ${errors.join(' ; ')}`,
            errors,
          })
          return true
        }
        deck.name = name
        deck.cardIds = cardIds
        deck.updatedAt = new Date().toISOString()
        sendJson(response, 200, toResponse(deck))
        return true
      }

      if (method === 'DELETE' && deckId !== null) {
        const index = decks.findIndex((item) => item.id === deckId && item.userId === user.id)
        if (index < 0) {
          sendJson(response, 404, { status: 404, message: `Deck introuvable : ${deckId}` })
          return true
        }
        const [removed] = decks.splice(index, 1)
        console.log(`[mock] deck #${removed.id} « ${removed.name} » supprimé`)
        sendJson(response, 204)
        return true
      }

      sendJson(response, 405, { status: 405, message: `Méthode non autorisée : ${method} ${path}` })
      return true
    }

    return false
  }

  return { handle, users, decks }
}
