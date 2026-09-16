# Architecture du frontend (Feature 05 — Frontend Jeu)

> Référence pour toute modification du client Vue. Documents liés :
> [`WEBSOCKET-PROTOCOL.md`](WEBSOCKET-PROTOCOL.md) (contrat de transport, **source de vérité**),
> [`RULE-ENGINE.md`](RULE-ENGINE.md) (règles côté serveur), [`architecture.md`](architecture.md) (vue globale).

---

## 1. Principe directeur

**Le frontend est 100 % passif.** Il ne contient aucune règle de jeu :

1. il **affiche** la dernière `GameStateDTO` reçue (chaque `STATE` est complète et
   **remplace** l'état local, jamais de patch — doc §11.2) ;
2. il **envoie des intentions** (`SEND /app/game/{gameId}/action`) et attend la
   réponse du serveur ;
3. les quelques gardes reproduites dans `gameStore` (`canPlayCard`,
   `canAttackWith`, `validAttackTargets`…) servent uniquement à **griser l'UI** :
   le serveur reste seul juge et renvoie `ILLEGAL_ACTION` avec un message en
   français affiché tel quel dans un toast.

Conséquence pratique : pour changer une règle, on modifie le backend. Pour
changer un *affordance* (ce qui est cliquable/grisé), on modifie `gameStore`.

---

## 2. Arborescence

```
frontend/
├── devtools/
│   └── mock-protocol.ts          # Serveur STOMP simulé (contrat de transport uniquement)
├── tools/
│   ├── mock-server.mjs           # Backend de dev : /api/* + ws://…/ws (npm run mock:ws)
│   └── mock-decks.mjs            # /api/auth/* + /api/decks en mémoire (règles de deckbuilding rejouées)
├── src/
│   ├── assets/main.css           # Thème cyberpunk (Tailwind v4 @theme + composants maison)
│   ├── components/
│   │   ├── CardComponent.vue     # Carte en jeu : image, stats, keywords, états
│   │   ├── CardPreview.vue       # Carte du catalogue (deck builder) — feature 02
│   │   ├── ConnectionBadge.vue   # Badge API/WebSocket de l'en-tête
│   │   ├── TargetingOverlay.vue  # Sélection de cible (attaques, Gear)
│   │   ├── game/
│   │   │   ├── PlayerBoard.vue   # Demi-tapis officiel : la grille `.playmat-grid`
│   │   │   ├── PlaymatZone.vue   # Cadre d'une zone imprimée (grid-area + libellé)
│   │   │   ├── GigsBar.vue       # Bandeau du haut : Rival Gigs | Friendly Gigs
│   │   │   ├── GigTracker.vue    # Compteur de Gigs / 7 + dés + Street Cred
│   │   │   ├── FixerArea.vue     # Colonne FIXER (dés d20 → d4)
│   │   │   ├── FieldArea.vue     # FIELD : Units + Gears attachés
│   │   │   ├── LegendsArea.vue   # LEGENDS : exactement 3 slots
│   │   │   ├── CardPile.vue      # Piles DECK / EDDIES / TRASH
│   │   │   ├── HandRow.vue       # Main (hors tapis)
│   │   │   ├── GameLogPanel.vue  # Journal de partie (doc §7.4)
│   │   │   ├── GameOverOverlay.vue
│   │   │   └── PhaseIndicator.vue# Draw → Main → Combat → End + tour
│   │   └── ui/CyberToast.vue     # Pile de notifications
│   ├── composables/
│   │   ├── useGameAnimations.ts  # Animations GSAP (pioche, pose, attaque, Gig, phase)
│   │   └── useGameSocket.ts      # Canal STOMP unique : connexion, abonnements, intentions
│   ├── router/index.ts           # /, /lobby, /deck, /game/:gameId?
│   ├── services/
│   │   ├── api.ts                # REST (GET /api/health, /api/cards, /api/decks — JWT)
│   │   └── socket.ts             # Enveloppe @stomp/stompjs (reconnexion, abonnements)
│   ├── stores/
│   │   ├── connection.ts         # Santé API + état du canal partagé
│   │   ├── deck.ts               # Catalogue + deck (localStorage) + « Mes Decks » persistés (/api/decks)
│   │   ├── game.ts               # État de partie, sélection, ciblage, intentions
│   │   ├── lobby.ts              # Pseudo, salons, gameId
│   │   └── ui.ts                 # Toasts
│   ├── types/
│   │   ├── card.ts               # Miroir de card-schema.json (feature 02)
│   │   ├── game.ts               # Miroir des DTO WS + destinations + libellés
│   │   └── playmat.ts            # Zones **d'affichage** du tapis officiel (Fixer, Eddies…)
│   └── views/
│       ├── HomeView.vue          # Diagnostic (API + ping) — feature 01
│       ├── LobbyView.vue         # Pseudo, créer/rejoindre, attente 2e joueur
│       ├── DeckBuilderView.vue   # Catalogue + glisser-déposer + Mes Decks / sauvegarde
│       └── GameView.vue          # Plateau 2 joueurs
├── vitest.config.ts
└── src/__tests__/                # Tests de flux (client réel ↔ serveur simulé)
```

---

## 3. Flux de données

### 3.1 Chaîne de responsabilité

```
serveur STOMP
   │  MESSAGE (JSON)
   ▼
services/socket.ts        GameSocket : transport, reconnexion, abonnements
   ▼
composables/useGameSocket.ts   singleton : fan-out par type de message, intentions
   ▼
stores (Pinia)            lobby.ts / game.ts : état applicatif + affordances
   ▼
views / components        rendu (props ↓, emits ↑)
```

Aucun composant n'ouvre de socket ni ne construit de destination STOMP : tout
passe par `useGameSocket`, qui centralise les destinations (constantes `Ws` de
`types/game.ts`, alignées sur `WsDestinations.java`).

### 3.2 Connexion

```
LobbyView → lobbyStore.connect()
          → useGameSocket.connect()
          → GameSocket.connect()  (CONNECT avec en-tête natif pseudo)
          → abonnements mémorisés rejoués : /user/queue/errors, /user/queue/lobby,
            /user/queue/rooms, /topic/rooms
```

L'identité vit **uniquement** dans l'en-tête `pseudo` du frame CONNECT ; elle est
persistée dans `localStorage['cyberpunk-tcg.pseudo']`. Changer de pseudo provoque
une reconnexion (le frame CONNECT est figé à la création du client).

### 3.3 Lobby → démarrage de partie

```
createRoom()  → SEND /app/lobby.create { roomName, deckCardIds }
              ← LOBBY_STATE (privé) → lobbyStore.room, puis SUB /topic/lobby/{code}
joinRoom()    → SEND /app/lobby.join   { roomCode, deckCardIds }
              ← LOBBY_STATE status=PLAYING + gameId
              → LobbyView.watch(gameId) → router.push('/game/{gameId}')
              → GameView.onMounted → gameStore.attach(gameId)
                  → SUB /topic/game/{gameId} + /topic/game/{gameId}/{pseudo}
                  → SEND /app/game/{gameId}/resync   (le STATE initial peut précéder l'abonnement)
              ← STATE → gameStore.state
```

`deckCardIds` provient de `deckStore.deck` quand `lobbyStore.useCustomDeck` est
coché, sinon `null` = deck par défaut du serveur (3 Legends + 10 Units).

### 3.4 Action de jeu

```
clic → gameStore.playCard() / attack() / sellCard() / endTurn()
     → garde d'ergonomie (sinon toast d'explication, rien n'est envoyé)
     → useGameSocket.sendAction(gameId, { action, instanceId, targetInstanceId })
        · clientRequestId généré (8 caractères) et mémorisé dans pendingRequestIds
     → SEND /app/game/{gameId}/action
     ← STATE (aux deux joueurs, clientRequestId répété pour l'émetteur)
        · sequence ≤ dernière connue → ignorée (état périmé)
        · trou de séquence → resync (doc §7.5)
        · state remplace l'état local ; `changes` = diff pour les animations
     ← ou ERROR (privée) → toast + retrait du clientRequestId
```

`pendingRequestIds` alimente `waitingForServer`, qui désactive les boutons le
temps de l'aller-retour (pas d'optimistic UI : le serveur fait autorité).

### 3.5 Sélection et ciblage

```
clic sur une carte de mon plateau → gameStore.selectCard(instanceId)
  · main / Legends : « Jouer la carte » (ou « Équiper un Gear »)
  · Unit du Field  : « Attaquer » → gameStore.beginAttack()
                     → targeting = { kind, sourceInstanceId, candidates, allowDirect }
                     → TargetingOverlay s'affiche ; les cibles légales sont surlignées
                     → clic sur une cible → chooseTarget() → attack(source, cible)
                     → « Vol direct de Gig » → stealGig() → attack(source, null)
  · second clic sur une carte déjà sélectionnée et jouable → playCard()
  · Échap / « Annuler » → cancelTargeting()
```

`candidates` est recalculé depuis les règles d'ergonomie (interception BLOCKER
comprise) et revalidé à chaque nouvel état reçu.

### 3.6 Reconnexion et présence

* Coupure transport → `@stomp/stompjs` retente seul ; `GameSocket` allonge le
  délai (2 s → 5 s, facteur 1,5) et expose l'état `connecting` + le détail.
* À chaque (re)connexion : abonnements rejoués, actions différées exécutées
  (`resync`), handlers `onReconnected` notifiés → `gameStore` redemande son état.
* `PLAYER_DISCONNECTED` → compte à rebours (`disconnection.secondsLeft`) affiché
  sur le siège concerné ; `PLAYER_RECONNECTED` l'annule.
* Rechargement de page : `localStorage['cyberpunk-tcg.session.v1']` conserve
  `{ pseudo, roomCode, gameId }` ; `LobbyView` propose la reprise
  (`resumeStoredGame()`), un `GAME_NOT_FOUND` nettoie la session.

---

## 4. Stores Pinia

### 4.1 `stores/game.ts` — la partie

| Catégorie | Éléments |
| --- | --- |
| État brut | `gameId`, `state` (GameStateDTO), `lastSequence`, `notice`, `disconnection`, `loading` |
| Interaction | `selectedInstanceId`, `targeting`, `pendingRequestIds` |
| Animations | `changes` (diff entre deux états), `lastEvents` (`newEvents` de l'enveloppe) |
| Dérivés | `me`, `opponent`, `isMyTurn`, `phase`, `turnNumber`, `iAmReacting`, `canEndTurn`, `canSell`, `myFieldUnits`, `opponentFieldUnits`, `selectedCard`, `waitingForServer`, `gigsToWin` |
| Ergonomie | `canPlayCard(card)`, `canAttackWith(card)`, `canStealGig(card)`, `validAttackTargets(card)`, `validGearHosts()`, `canEquipGear(card)` — renvoient `null` si l'action est possible, sinon la raison (chaîne affichable) |
| Intentions | `playCard`, `attack`, `sellCard`, `endTurn`, `concede` |
| Ciblage | `selectCard`, `clearSelection`, `beginAttack`, `beginEquip`, `chooseTarget`, `stealGig`, `cancelTargeting` |
| Cycle de vie | `attach(gameId)`, `detach()` |

`computeChanges(previous, next, events)` produit les `StateChanges` utilisés par
les animations : cartes apparues dans ma main (`drawn`), posées (`played`),
vaincues (`defeated`), Legends retournées (`flipped`), attaquant
(`attackerInstanceId`, repris depuis `reactionWindow`), gagnants de Gig
(`gigGainers`), `phaseChanged`, `turnChanged`, `opponentDrew`.

### 4.2 `stores/lobby.ts`

`status` (`idle|connecting|ready|waiting|starting|in-game`), `room`, `rooms`,
`gameId`, `error`, `busy`, brouillons (`roomNameDraft`, `roomCodeDraft`),
`useCustomDeck`. Actions : `setPseudo`, `connect`, `createRoom`, `joinRoom`,
`leaveRoom`, `leaveLocalRoom`, `refreshRooms`, `resumeStoredGame`, `forfeit`.

### 4.3 `stores/deck.ts`

Catalogue (`cards`, `catalogState`, `loadCatalog`), filtres (`search`,
`typeFilter`, `colorFilter`), deck (`deck`, `groupedDeck`, `legendCount`,
`mainCount`, `problems`, `isValid`) et `add/remove/clear/setDeck/buildSampleDeck`.
Persistance : `localStorage['cyberpunk-tcg.deck.v1']`. Les règles affichées sont
celles du serveur (`DefaultDeckService`) : 3 Legends, ≥ 10 non-Legends, sans doublon.

### 4.4 `stores/ui.ts` et `stores/connection.ts`

`ui` : pile de toasts (`info/success/warn/error`, TTL par défaut, 5 max).
`connection` : santé REST (`checkApi`) + **lecture** de l'état du canal partagé
(`wsState`, `isWsConnected`, `ping`) ; `markChannelInUse(true)` empêche le bouton
« Quitter » de couper le canal pendant une partie.

> Un seul client STOMP par page : `connection` n'ouvre plus sa propre connexion
> depuis la feature 05, il lit `useGameSocket()`.

---

## 5. Composables

### 5.1 `useGameSocket()` (singleton de module)

| Rôle | API |
| --- | --- |
| Identité / connexion | `pseudo`, `setPseudo()`, `connect()`, `disconnect()`, `status`, `statusDetail`, `isConnected` |
| Intentions | `sendAction(gameId, command)`, `requestResync(gameId)`, `createRoom`, `joinRoom`, `leaveRoom`, `requestRooms`, `ping` |
| Abonnements | `watchGame(gameId)`, `watchRoom(code)`, `forgetRoom(code)` |
| Écoute | `onGameState`, `onGameNotice`, `onWsError`, `onLobbyState`, `onRoomList`, `onPong`, `onReconnected` |

Points d'implémentation à connaître avant d'y toucher :

* les abonnements sont conservés dans une `Map` module (`registrations`) et
  **rejoués à chaque reconnexion** ;
* les files privées (`/user/queue/errors|lobby|rooms`) et `/topic/rooms` sont
  abonnées dès la création du client (ordre recommandé doc §1.3) ;
* `whenConnected()` met en file les actions à jouer à l'ouverture du canal
  (`resync` notamment) ;
* `sendAction()` supprime les champs `null` du payload (le serveur les omet aussi)
  et renvoie le `clientRequestId` généré ;
* `__resetGameSocketForTests()` remet le singleton à zéro (tests uniquement).

### 5.2 `useGameAnimations(root)`

GSAP scopé au plateau (`gsap.context`, nettoyé via `onScopeDispose`),
`prefers-reduced-motion` respecté (animations ignorées). Points d'accroche DOM :

| Sélecteur | Usage |
| --- | --- |
| `[data-instance-id="…"]` | chaque carte (`CardComponent`) |
| `[data-zone="FIXER|FIELD|DECK|LEGENDS|EDDIES|TRASH"][data-side="me|opponent"]` | zones imprimées (`PlaymatZone`) — `data-anim` est dérivé du nom (`FIELD` → `field`) |
| `[data-anim="hand"][data-side="me|opponent"]` | main (`HandRow`) |
| `[data-anim="gigs"][data-side="me|opponent"]` | compteurs de Gigs (`GigTracker`, bandeau `GigsBar`) |
| `[data-anim="phase"]` | indicateur de phase |
| `[data-anim="turn-banner"]` | bannière « À ton tour » |
| `[data-anim="gig-pip"]` | pips du compteur de Gigs |

Méthodes : `drawCards`, `playCards`, `flipCards`, `attack`, `defeat`, `gigPulse`,
`phaseSweep`, `turnBanner`, `opponentDraw`. **Ne pas renommer ces attributs** sans
mettre à jour `GameView` et ce tableau.

---

## 6. Composants

| Composant | Props principales | Emits |
| --- | --- | --- |
| `CardComponent` | `card` (CardInstance), `definition` (GameCard du catalogue), `side`, `size` (`xs/sm/md/lg`), `selectable`, `selected`, `targetable`, `dimmed`, `showKeywords`, `showAbilities` | `click(card)` |
| `PlayerBoard` | `player`, `isMe`, `isActive`, `definitions` (Map), `selectedInstanceId`, `targetableIds`, `actionableIds`, `interactive`, `disconnection` | `cardClick(card)` |
| `PlaymatZone` | `zone` (`FIXER|FIELD|DECK|LEGENDS|EDDIES|TRASH`), `side`, `label`, `badge`, `hint`, `minHeight` | — (slot `header` pour des chips) |
| `LegendsArea` | `legends`, `definitions`, `side`, `selectedInstanceId`, `targetableIds`, `actionableIds`, `interactive` | `cardClick(card)` |
| `FieldArea` | `field`, `definitions`, `side`, `selectedInstanceId`, `targetableIds`, `actionableIds`, `interactive` | `cardClick(card)` |
| `FixerArea` | `dice` (dés restants), `side` | — |
| `CardPile` | `cards`, `count`, `faceUp`, `definitions`, `side`, `emptyLabel`, `size` | — |
| `HandRow` | `player`, `definitions`, `isMe`, `selectedInstanceId`, `targetableIds`, `actionableIds`, `interactive` | `cardClick(card)` |
| `TargetingOverlay` | `kind` (`attack|gear`), `sourceName`, `candidates`, `allowDirect` | `cancel`, `direct` |
| `PhaseIndicator` | `phase`, `turnNumber`, `isMyTurn`, `activePlayerName`, `gameOver`, `waiting` | — |
| `GigsBar` | `me`, `opponent`, `activePlayerId` | — |
| `GigTracker` | `label` (« Rival Gigs » / « Friendly Gigs »), `side`, `playerName`, `gigCount`, `gigs`, `fixerDice`, `streetCred`, `fixerTotal`, `target` | — |
| `GameLogPanel` | `entries`, `meId`, `playerName`, `opponentName` | — |
| `GameOverOverlay` | `winnerId`, `endReason`, `iWon`, `playerName`, `opponentName` | `leave` |
| `CyberToast` | — (lit `uiStore`) | — |

États visuels d'une carte (`CardComponent`) : **sélectionnable** (survol +
cursor), **sélectionnée** (halo de la couleur RAM), **tapped/épuisée** (rotation
−6° + désaturation + ruban « épuisée »), **grisée** (`dimmed` : opacité +
grayscale), **ciblable** (anneau magenta pulsant + réticule), **dos de carte**
(`cardId === 'hidden'` ou `faceDown`).

`TargetingOverlay` est en `pointer-events-none` : seules sa bannière et ses
boutons captent les clics, les cartes restent cliquables en dessous.

---

## 6 bis. Tapis officiel (mini-feature « Layout exact du Playmat »)

La disposition du plateau n'est pas improvisée : elle reproduit le tapis officiel
(`docs/OFFICIAL-RULES.md` § PLAYMAT AREAS). Elle tient dans **une seule grille CSS**,
`.playmat-grid` de `src/assets/main.css` — les zones sont placées par
`grid-template-areas`, jamais par l'ordre du DOM.

```
                 RIVAL GIGS            FRIENDLY GIGS        ← GigsBar (tout en haut)
        ┌────────┬──────────────────────────────┬────────┐
        │        │                              │        │
        │ FIXER  │  FIELD (immense)             │  DECK  │   ← centre-haut / milieu-droite
        │ d20→d4 ├───────────────┬──────────────┼────────┤
        │        │  LEGENDS (×3) │  EDDIES      │ TRASH  │   ← ligne du bas
        └────────┴───────────────┴──────────────┴────────┘
              · bas-centre-gauche = Legends   · bas-centre-droite = Eddies
```

| Zone affichée | `data-zone` | `grid-area` | Source serveur (inchangée) |
| --- | --- | --- | --- |
| Fixer Area | `FIXER` | `fixer` | `PlayerState.fixerDice` (dés pas encore lancés) |
| Gig Area (haut) | `GIGS` | bandeau `GigsBar` | `gigs`, `gigCount`, `streetCred` |
| Field | `FIELD` | `field` | `field` (+ `attachedTo` pour les Gears) |
| Deck | `DECK` | `deck` | `deckCount` (contenu jamais envoyé) |
| Legends | `LEGENDS` | `legends` | `legendsArea` → **3 slots** (`LEGEND_SLOTS`) |
| Eddies | `EDDIES` | `eddies` | `eddiesArea` (cartes vendues `faceDown`) |
| Trash | `TRASH` | `trash` | `trash` (face visible) |
| Main | `HAND` | hors grille | `hand` (masquée pour l'adversaire) |

Points à respecter avant de toucher au plateau :

* **aucun renommage côté transport** : le serveur (Spring **et** mock) émet toujours
  `FIELD | HAND | TRASH | EDDIES_AREA | LEGENDS_AREA | DECK`. Les identifiants du tapis
  (`FIXER`, `GIGS`, `LEGENDS`, `EDDIES`…) sont purement **présentationnels** et vivent
  dans `src/types/playmat.ts` ;
* la zone LEGENDS rend **toujours 3 slots** (`LegendsArea`), remplis par index : une
  Legend retournée ou dépensée garde son emplacement, un slot vide reste visible ;
* `PlayerBoard` sert aux **deux** sièges (le rival affiche les mêmes zones, cartes masquées) ;
* les hooks d'animation restent adossés aux zones : `PlaymatZone` dérive `data-anim` du
  nom de la zone (`data-zone="FIELD"` → `data-anim="field"`), `HandRow` porte
  `data-anim="hand"` et `GigTracker` `data-anim="gigs"` ;
* le contrat est verrouillé par `src/__tests__/playmatLayout.spec.ts` (zones, 3 slots,
  6 dés, `grid-template-areas` et `grid-area` lus dans `main.css`).

---

## 7. Thème cyberpunk

Tokens dans `src/assets/main.css` (`@theme`, Tailwind v4 CSS-first) :

| Token | Valeur | Usage |
| --- | --- | --- |
| `--color-cyber-bg` | `#0a0a12` | fond (grille « Night City » en `body`) |
| `--color-cyber-panel` / `-light` / `--color-cyber-line` | `#14141f` / `#1c1c2b` / `#2a2a3d` | panneaux, bordures |
| `--color-cyber-yellow` | `#fcee0a` | accents, Eddies, Gigs, codes de salon |
| `--color-cyber-cyan` | `#05d9e8` | joueur local, phases, RAM bleue |
| `--color-cyber-magenta` | `#ff2a6d` | adversaire, ciblage, erreurs |
| `--color-cyber-green` | `#39ff88` | validation, victoire, RAM verte |
| `--color-cyber-red` | `#ff4d4d` | dégâts, défaite |

Classes maison : `.cyber-panel`, `.cyber-title`, `.cyber-chip`, `.cyber-btn`
(+ `--accent`, `--green`, `--danger`), `.cyber-scroll`, `.cyber-cardback`,
`.field-zone`, `.zone-label`, animations `--animate-scan` / `--animate-pulse-slow`.

Couleur de cadre d'une carte = sa RAM (`red/green/blue/yellow`) via les tables
`FRAME`/`ACCENT`/`HALO` de `CardComponent` — classes écrites **en toutes lettres**
(le scanner Tailwind ne résout pas les chaînes construites).

---

## 8. Développement sans backend

Le backend Spring nécessite JVM + PostgreSQL. Pour travailler l'UI seule :

```bash
cd frontend
npm install
npm run mock:ws   # API REST + STOMP simulés sur :8080 (catalogue réel du backend)
npm run dev       # Vite sur :5173, proxifie /api et /ws vers :8080
```

`devtools/mock-protocol.ts` reproduit le **contrat de transport** (destinations,
enveloppes, masquage, séquences, erreurs) et un sous-ensemble de règles
suffisant pour jouer : pose, vente, attaque (comparaison de puissances,
interception BLOCKER, vol de Gig), fin de tour (pioche + dé Gig + victoire à 7).
**Ce n'est pas le moteur de règles** — toute validation sérieuse passe par
`cd backend && mvn test`.

La **présence** est simulée comme le backend (`ws/GamePresenceService`) : la
fermeture du socket (rafraîchissement de page, coupure réseau) libère le siège
d'un salon en attente et, pendant une partie, marque le joueur déconnecté
(`connected: false`) puis diffuse `PLAYER_DISCONNECTED` avec
`reconnectDeadInSeconds: 120`. Une reconnexion avec le même pseudo diffuse
`PLAYER_RECONNECTED` et repousse les états. Le minuteur de forfait n'est pas
chronométré côté simulé : `server.forfeitOfflinePlayer(gameId, pseudo)` rejoue
l'issue (abandon, `GAME_OVER`, salon fermé) sans attendre 120 s.

Pour pointer le client vers un vrai backend : `npm run dev` suffit (proxy),
ou `VITE_WS_URL` / `VITE_API_BASE_URL` pour une origine distante.

---

## 9. Tests

```bash
cd frontend && npm run test:unit
```

Les tests montent les **vrais** composants et stores avec le vrai client
`@stomp/stompjs` ; seule l'extrémité réseau est une socket en mémoire branchée
sur le serveur simulé (`src/__tests__/helpers/stompHarness.ts`).

* `gameFlow.spec.ts` : en-tête `pseudo` du CONNECT + ordre des abonnements ;
  flux complet **Lobby → Partie → Vendre → Jouer → Attaquer → Fin de tour**
  (destinations souscrites, payloads envoyés, remplacement d'état, rendu du
  plateau, journal) ; erreur `ILLEGAL_ACTION` privée au seul joueur fautif.
* `frontendFlow.spec.ts` : deck builder (catalogue `/api/cards`, ajout, doublons,
  validation, glisser-déposer, persistance) et bascule en reconnexion
  automatique quand le transport tombe.
* `playmatLayout.spec.ts` : disposition du tapis officiel — zones des deux demi-tapis,
  3 slots de Legends, colonne Fixer (d20 → d4), piles Deck/Eddies/Trash, bandeau
  `Rival Gigs` / `Friendly Gigs` et grille CSS (`grid-template-areas`, `grid-area`) ;
* `presence.spec.ts` : fermeture d'un socket — siège d'un salon en attente
  libéré (le même pseudo peut recréer un salon), `PLAYER_DISCONNECTED` puis
  `PLAYER_RECONNECTED` reçus par le joueur resté en ligne (minuteur de 120 s
  démarré puis annulé, `connected` à jour), et fin de partie sur forfait.

`npm run build` exécute `vue-tsc` sur `src/**` **et** `devtools/**` : les tests
sont donc aussi vérifiés typiquement.

---

## 10. Limites V1 et pistes pour le polish

* Pas de mode spectateur, pas d'historique/rejeu (le journal est déjà complet et
  sans secret : `state.log`).
* Animations : la cible d'une attaque n'est pas transportée par le protocole —
  l'assaillant « fonce » vers le camp adverse ; pour viser précisément la carte
  touchée, il faudrait un champ `targetInstanceId` dans `GameLogEntryDTO`.
* Fenêtre de réaction : bannière + restriction QUICK affichées, mais pas de
  « passe » explicite (le serveur ferme la fenêtre en fin de tour).
* Deck builder : pas de persistance serveur ni de plafonds de RAM par couleur
  (feature comptes/decks) ; le deck vit dans `localStorage`.
* Le responsive du plateau est fonctionnel mais perfectible : sous `lg` (1024 px),
  `.playmat-grid` empile les zones en une colonne (Field → Legends → Eddies →
  Trash → Deck → Fixer) au lieu de reproduire le tapis ; accessibilité : `aria-*`
  posés, navigation clavier partielle (Échap pour le ciblage).
* Aucun son, aucun tutoriel intégré, pas d'affichage des textes de cartes au
  survol (`showAbilities` existe, le tooltip riche reste à faire).
