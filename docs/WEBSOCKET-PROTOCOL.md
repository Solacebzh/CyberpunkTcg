# Protocole temps réel — WebSocket STOMP (Features 01 & 04)

> **Contrat d'interface backend → frontend.** Ce document est la référence
> exhaustive : toutes les destinations, tous les payloads JSON (envoyés et
> reçus), avec des exemples réels capturés sur l'implémentation.
>
> Version décrite : Feature 04 (lobby + temps réel de partie).
> Backend : Java 21 / Spring Boot 3.5 / STOMP sur WebSocket, broker simple en mémoire.

---

## 0. Principes d'architecture

1. **Serveur faisant autorité.** Le client envoie des **intentions** ; le serveur
   valide (appartenance, phase, coûts, cibles…) puis exécute via le moteur de règles.
2. **L'identité n'est JAMAIS dans les payloads.** Elle est déduite de la session
   STOMP (pseudo fourni au `CONNECT`). Tout `playerId` présent dans un message
   envoyé par un client est ignoré.
3. **État complet après chaque action acceptée.** Le serveur diffuse une
   photographie complète et masquée de la partie ; le client remplace son état
   local. La resynchronisation après reconnexion est triviale.
4. **Le secret de l'adversaire est masqué serveur.** Main, deck et Legends face
   cachée de l'adversaire arrivent sous forme de cartes `hidden` (voir §7.3).
5. **Les erreurs sont privées** : seule la session fautive les reçoit
   (`/user/queue/errors`), jamais l'adversaire.
6. **Sérialisation JSON UTF-8.** Les champs `null` sont **omis**
   (`spring.jackson.default-property-inclusion=non_null`) : un champ absent
   équivaut à `null`. Les dates sont des instants ISO-8601 en UTC, tronqués à la
   milliseconde (ex. `"2026-09-14T20:17:23.036Z"`).

---

## 1. Connexion

| Élément | Valeur |
| --- | --- |
| Endpoint | `ws(s)://<hôte>/ws` — en dev : `ws://localhost:8080/ws` (ou via le proxy Vite : `ws://localhost:5173/ws`) |
| Sous-protocole | `v12.stomp` (négocié par les librairies STOMP) |
| Auth (V1, sans JWT) | en-tête natif **`pseudo`** obligatoire sur le frame `CONNECT` |
| Origines autorisées (CORS) | `http://localhost:5173`, `http://127.0.0.1:5173` (configurable via `CORS_ALLOWED_ORIGINS`) |
| Préfixe applicatif (client → serveur) | `/app` |
| Topics publics (serveur → abonnés) | `/topic` |
| Files privées (serveur → un utilisateur) | `/user/queue/**` |
| Battements de cœur | 10 s dans chaque sens (config serveur) |
| Reconnexion | à la charge du client (recommandé : back-off 2-5 s, puis `resync`) |

### 1.1 Frame CONNECT

```
CONNECT
accept-version:1.2
host:localhost
heart-beat:10000,10000
pseudo:Johnny
```

Avec la librairie `@stomp/stompjs` (future référence frontend) :

```javascript
const client = new Client({
  brokerURL: 'ws://localhost:8080/ws',
  connectHeaders: { pseudo: 'Johnny' },
  heartbeatIncoming: 10000,
  heartbeatOutgoing: 10000,
  reconnectDelay: 4000,
})
```

### 1.2 Règles de pseudo

- Motif : `^[A-Za-z0-9_\-À-ÿ]{2,20}$` (2 à 20 caractères, accents autorisés).
- Le pseudo fait office d'identifiant de joueur : il doit être unique **dans un salon**.
- `CONNECT` sans en-tête `pseudo` ou avec un pseudo invalide → le serveur ferme la
  WebSocket (frame STOMP `ERROR`, puis fermeture du transport). Le client doit
  afficher « pseudo invalide » et ne pas retenter avec le même en-tête.

### 1.3 Ordre des abonnements recommandé

Après connexion, le client s'abonne **d'abord** aux files persistantes de son
expérience, puis agit :

1. `/user/queue/errors` — toujours (erreurs privées) ;
2. selon l'écran : `/topic/rooms`, `/user/queue/rooms`, `/user/queue/lobby` ;
3. après avoir rejoint/créé un salon : `/topic/lobby/{code}` ;
4. au passage en `PLAYING` : `/topic/game/{gameId}` et
   `/topic/game/{gameId}/{pseudo}`, puis envoyer `resync` (voir §5.3).

> Les messages privés `/user/queue/...` sont adressés par pseudo : toutes les
> sessions du même pseudo les reçoivent (onglets multiples).

---

## 2. Tableau exhaustif des destinations

### 2.1 Client → serveur (`SEND`, préfixe `/app`)

| Destination | Payload | Réponse | Section |
| --- | --- | --- | --- |
| `/app/ping` | `{ "message": "hello" }` (optionnel) | broadcast `/topic/pong` | §3.1 |
| `/app/lobby.create` | `CreateRoomRequest` | `LOBBY_STATE` sur `/user/queue/lobby` + topic + liste | §4.1 |
| `/app/lobby.join` | `JoinRoomRequest` | idem ; démarre la partie si 2 joueurs | §4.2 |
| `/app/lobby.leave` | `LeaveRoomRequest` (ou vide) | `LOBBY_STATE` privé + topic + liste | §4.3 |
| `/app/lobby.list` | *(aucun)* | `ROOMS` sur `/user/queue/rooms` | §4.4 |
| `/app/game/{gameId}/action` | `GameCommandDTO` | `STATE` aux deux joueurs ; `ERROR` privé si refus | §5 |
| `/app/game/{gameId}/resync` | *(vide)* | `STATE` personnel immédiat | §5.3 |

### 2.2 Serveur → client (`SUBSCRIBE`)

| Destination | Contenu | Visibilité |
| --- | --- | --- |
| `/topic/pong` | `{ "type": "pong", ... }` | publique (test de canal) |
| `/topic/rooms` | `ROOMS` : liste des salons `WAITING`, rediffusée à chaque changement | publique |
| `/user/queue/rooms` | `ROOMS` : réponse privée à `lobby.list` | privée |
| `/topic/lobby/{code}` | `LOBBY_STATE` : état du salon (attente puis `PLAYING`) | abonnés du salon |
| `/user/queue/lobby` | `LOBBY_STATE` : accusé privé de create/join/leave | privée |
| `/topic/game/{gameId}` | `GameNotice` : `GAME_STARTED`, `GAME_OVER`, présence (dé/reconnexion) | publique aux deux joueurs |
| `/topic/game/{gameId}/log` | `GameLogMessage` (`LOG`) : entrées du **journal de diagnostic** (feature 6.5), incrémentales | publique aux deux joueurs |
| `/topic/game/{gameId}/{pseudo}` | `GameStateMessage` (`STATE`) : état complet masqué pour ce pseudo | chaque joueur ne s'abonne qu'au **sien** |
| `/user/queue/errors` | `ERROR` : rejet d'une action / lobby | privée |

> `/topic/game/{gameId}/log` est distinct du topic d'état *par joueur* : il publie
> les mêmes lignes aux deux joueurs (aucun secret n'y figure — les messages
> utilisent les noms de cartes publiquement révélés et les identifiants de
> joueurs). Le client distingue les deux flux par le champ `type` (`LOG` vs
> `STATE`) puisque les deux arrivent sur des destinations voisines.
>
> Pourquoi un topic par joueur pour l'état ? Le broker simple ne sait pas
> filtrer par destinataire ; on segmente donc par pseudo dans la destination.
> Un client ne doit jamais s'abonner au topic d'état d'un autre pseudo (aucun
> secret utile — le serveur n'y publie rien pour lui).

---

## 3. Canal de test (Feature 01)

### 3.1 Ping / Pong

`SEND /app/ping` :

```json
{ "message": "hello" }
```

`SUBSCRIBE /topic/pong` reçoit :

```json
{
  "type": "pong",
  "echo": "hello",
  "serverTime": "2026-09-14T14:50:46.123Z"
}
```

Corps absent ou `message` nul → `"echo": "ping"`.

---

## 4. Lobby

### 4.1 Créer un salon — `SEND /app/lobby.create`

Requête :

```json
{
  "roomName": "Partie doc",
  "deckCardIds": null
}
```

- `roomName` : optionnel (défaut : `"Salon de {pseudo}"`).
- `deckCardIds` : optionnel. Liste d'**identifiants de cartes du catalogue**
  (`GET /api/cards`). Règles de deck : exactement **3 Legends** et au moins
  **10 autres cartes** (units/gears/programs), sans doublon.
  `null`/absent → **deck par défaut du serveur** (3 premières legends +
  10 premières units du catalogue, triées par nom). Une carte inconnue ou un
  deck invalide renvoie `ERROR` `DECK_INVALID`.

Réponse privée sur `/user/queue/lobby`, puis diffusion sur
`/topic/lobby/{code}` et `/topic/rooms` :

```json
{
  "type": "LOBBY_STATE",
  "code": "6SQX4Z",
  "name": "Partie doc",
  "status": "WAITING",
  "hostPseudo": "DocHost",
  "players": [
    { "pseudo": "DocHost", "seat": 0, "deckCardCount": 13 }
  ],
  "createdAt": "2026-09-14T20:17:22.522Z"
}
```

- `code` : code d'invitation à 6 caractères, alphabet sans ambiguïté
  (`ABCDEFGHJKMNPQRSTUVWXYZ23456789`, pas de 0/O, 1/I), à partager tel quel.
- `status` : `WAITING` | `PLAYING` | `CLOSED`.
- `seat` : `0` = hôte, `1` = invité.
- `gameId` : **absent** tant que la partie n'a pas démarré.

Erreurs possibles (`/user/queue/errors`) : `INVALID_PSEUDO`,
`ALREADY_IN_ROOM` (le pseudo occupe déjà un siège), `DECK_INVALID`.

### 4.2 Rejoindre un salon — `SEND /app/lobby.join`

```json
{ "roomCode": "6SQX4Z", "deckCardIds": null }
```

- Le code est insensible à la casse et aux espaces superflus.
- Dès que le second joueur s'assied, le serveur :
  1. crée la partie via `GameService.createGame(hôte, invité, deckHôte, deckInvité)` ;
  2. fait passer le salon en `PLAYING` (diffusé sur `/topic/lobby/{code}` et en
     accusé privé `/user/queue/lobby`) ;
  3. publie une notice `GAME_STARTED` sur `/topic/game/{gameId}` ;
  4. pousse un premier `STATE` sur les topics d'état (qui peut précéder les
     abonnements — d'où le `resync` systématique côté client, §5.3).

`LOBBY_STATE` au démarrage :

```json
{
  "type": "LOBBY_STATE",
  "code": "6SQX4Z",
  "name": "Partie doc",
  "status": "PLAYING",
  "hostPseudo": "DocHost",
  "players": [
    { "pseudo": "DocHost", "seat": 0, "deckCardCount": 13 },
    { "pseudo": "DocGuest", "seat": 1, "deckCardCount": 13 }
  ],
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "createdAt": "2026-09-14T20:17:22.522Z"
}
```

Notice sur `/topic/game/{gameId}` :

```json
{ "type": "GAME_STARTED", "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720" }
```

Erreurs : `ROOM_NOT_FOUND`, `ROOM_NOT_JOINABLE` (déjà plein / en cours),
`PSEUDO_TAKEN` (déjà assis dans ce salon), `ALREADY_IN_ROOM`, `DECK_INVALID`.

### 4.3 Quitter un salon — `SEND /app/lobby.leave`

```json
{ "roomCode": "6SQX4Z" }
```

Le code est optionnel : sans lui, le serveur utilise le salon courant du pseudo.
- Le départ de l'**hôte** ferme le salon (les sièges sont libérés).
- Une fois `PLAYING`, `leave` est refusé (`GAME_IN_PROGRESS`) : il faut
  abandonner via l'action `CONCEDE` (§5.2), ou se déconnecter (§8).

### 4.4 Lister les salons — `SEND /app/lobby.list`

Aucun corps requis. Réponse privée sur `/user/queue/rooms` ; le même message
est aussi diffusé publiquement sur `/topic/rooms` à chaque création/fermeture :

```json
{
  "type": "ROOMS",
  "rooms": [
    { "code": "6SQX4Z", "name": "Partie doc", "hostPseudo": "DocHost", "playerCount": 1 }
  ]
}
```

Seuls les salons `WAITING` apparaissent.

---

## 5. Parties

### 5.1 Envoi d'une action — `SEND /app/game/{gameId}/action`

Enveloppe unique `GameCommandDTO` (les champs inutiles pour l'action sont
omis ou `null`) :

```jsonc
{
  "action": "END_TURN",          // obligatoire, voir le catalogue ci-dessous
  "instanceId": null,            // UUID : carte concernée (main, terrain, legends area)
  "targetInstanceId": null,      // UUID : cible (Unit rivale, hôte d'un Gear)
  "targetPlayerId": null,        // réservé (attaques joueurs futures)
  "chosen": null,                // option choisie dans une fenêtre de réaction
  "revealedLegendIds": null,     // réservé (effets de reveal avancés)
  "cardIds": null,               // réservé (ventes/pioches groupées futures)
  "dice": null,                  // réservé (choix de dés futures)
  "clientRequestId": "doc-req"   // optionnel, renvoyé tel quel dans le STATE / l'ERROR
}
```

#### Catalogue des actions

| `action` | `instanceId` | `targetInstanceId` | Équivalent moteur |
| --- | --- | --- | --- |
| `PLAY_CARD` | carte à jouer (main, ou Legend de la legends area pour la retourner) | obligatoire pour un **Gear** (l'Unit alliée équipée) ; optionnel pour les autres cartes qui ciblent | `PlayCardCommand` |
| `ATTACK` | l'Unit attaquante | UUID d'une **Unit rivale** pour la combattre ; **absent/null** pour une attaque directe de vol de Gig | `AttackCommand` |
| `SELL_CARD` | carte de sa main à vendre (1 vente/tour, phase Main ; **aucun Eddie immédiat** — la carte est révélée puis posée face cachée et prête en Eddies Area) | — | `SellCardCommand` |
| `SPEND_LEGEND` | Legend **face cachée** de sa Legends Area à incliner (+1 Eddie, définitif) | — | `SpendLegendCommand` |
| `END_TURN` | — | — | `EndTurnCommand` |
| `CONCEDE` | — | — | abandon (victoire immédiate de l'adversaire) |

Règles appliquées par le serveur (rappel) : on joue en phase `MAIN`/`COMBAT` ;
une Unit attaquante doit être prête, sans mal d'invocation (sauf `go_solo`) ;
un `BLOCKER` rival prêt doit être attaqué avant de pouvoir voler un Gig ;
pendant une fenêtre de réaction, le défenseur ne peut jouer que des cartes
`quick` hors de son tour. La vente ne rapporte **aucun** Eddie : elle crée une
ressource (carte révélée, posée `faceDown` et prête en Eddies Area). L'Eddie est
gagné en inclinant une carte — Legend (`SPEND_LEGEND`) ou carte de l'Eddies Area
(`SPEND_EDDIES`) — pour exactement 1 €$ chacune, une fois par tour et par carte
(redressées au début du tour suivant). Le premier joueur commence avec
2 Legends déjà inclinées (malus de mise en place).

Exemples de commandes :

```json
// Poser une Unit
{ "action": "PLAY_CARD", "instanceId": "02bdbfcf-dd69-4a72-ba10-ec21f98f4684" }

// Équiper un Gear sur une Unit alliée
{ "action": "PLAY_CARD",
  "instanceId": "11111111-2222-3333-4444-555555555555",
  "targetInstanceId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" }

// Attaquer une Unit rivale
{ "action": "ATTACK",
  "instanceId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "targetInstanceId": "99999999-8888-7777-6666-555555555555" }

// Vol direct de Gig (pas de cible ; interdit si un BLOCKER rival est prêt)
{ "action": "ATTACK", "instanceId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" }

// Vendre une carte
{ "action": "SELL_CARD", "instanceId": "02bdbfcf-dd69-4a72-ba10-ec21f98f4684" }

// Terminer son tour
{ "action": "END_TURN" }

// Abandonner
{ "action": "CONCEDE" }
```

#### Après une action acceptée

1. Le serveur publie une `GameStateMessage` sur
   `/topic/game/{gameId}/{pseudo}` pour **chacun des deux joueurs** (masquage
   respectif) ; `clientRequestId` est répété pour l'émetteur.
2. Si l'action met fin à la partie, une notice `GAME_OVER` est aussi publiée
   sur `/topic/game/{gameId}`.

### 5.2 La fin de partie

- `state.gameOver === true`, `state.winnerId` renseigné, `state.endReason`
  texte (français) ; la dernière `STATE` contient le journal complet.
- La notice publique complète :

```json
{
  "type": "GAME_OVER",
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "winnerId": "DocHost",
  "endReason": "Abandon de DocGuest"
}
```

La victoire peut survenir par : 7 Gigs au début de son tour, deck vide à la
pioche, effets de cartes, `CONCEDE`, ou forfait déconnexion (§8). Le salon est
ensuite fermé côté serveur (les pseudos redeviennent disponibles).

### 5.3 Ressynchronisation — `SEND /app/game/{gameId}/resync`

À envoyer (corps vide `{}`) :
- juste après s'être abonné aux topics de la partie (le `STATE` initial
  éventuellement manqué) ;
- après toute reconnexion WebSocket ;
- si le client détecte un trou de `sequence` (voir §7.5).

Réponse : une unique `GameStateMessage` personnelle sur
`/topic/game/{gameId}/{pseudo}` (avec `newEvents: []`).

---

## 6. Notifications publiques de partie (`GameNotice`)

Sur `SUBSCRIBE /topic/game/{gameId}`, messages petits et **sans secret** :

| `type` | Champs renseignés | Moment |
| --- | --- | --- |
| `GAME_STARTED` | `gameId` | deuxième joueur assis |
| `GAME_OVER` | `winnerId`, `endReason` | victoire quelle qu'en soit la cause |
| `PLAYER_DISCONNECTED` | `playerId`, `reconnectDeadInSeconds` (120) | fermeture de la dernière session d'un joueur |
| `PLAYER_RECONNECTED` | `playerId` | retour dans le délai de grâce |

```json
{
  "type": "PLAYER_DISCONNECTED",
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "playerId": "DocGuest",
  "reconnectDeadInSeconds": 120
}
```

```json
{
  "type": "PLAYER_RECONNECTED",
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "playerId": "DocGuest"
}
```

---

## 7. `GameStateMessage` : le gros morceau

Enveloppe sur `/topic/game/{gameId}/{pseudo}` :

```json
{
  "type": "STATE",
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "clientRequestId": "doc-req",
  "state": { /* GameStateDTO, §7.1 */ },
  "newEvents": [ /* événements créés par la dernière action, pour les animations */ ]
}
```

- `clientRequestId` : présent uniquement si la poussée fait suite à une action
  du client ; absent sur les poussions d'événements tiers (reconnexions…).
- `newEvents` : sous-ensemble du journal correspondant à la dernière action
  (même format que `state.log`, index globaux inclus). Purement indicatif
  (animations) : en cas de doute, le client se fie à `state`.

### 7.1 `GameStateDTO`

```json
{
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "phase": "MAIN",
  "gameOver": false,
  "yourPlayerId": "DocHost",
  "turn": { "number": 1, "activePlayerId": "DocHost" },
  "players": [ /* PlayerStateDTO hôte puis invité, §7.2 */ ],
  "reactionWindow": null,
  "log": [
    {
      "index": 0,
      "type": "TURN_STARTED",
      "playerId": "DocHost",
      "description": "début de la partie (tour 1, DocHost commence)"
    }
  ],
  "gameLog": [
    {
      "index": 1,
      "timestamp": "2026-09-15T10:00:00.000Z",
      "turnNumber": 1,
      "phase": "MAIN",
      "playerId": "DocHost",
      "actionType": "PLAY_CARD",
      "description": "Joueur DocHost joue 6th Street Recruits (coût: 4 Eddies, 2 RAM rouge)",
      "result": "SUCCESS",
      "details": { "card": "6th Street Recruits", "paid": 4 }
    }
  ],
  "sequence": 1,
  "createdAt": "2026-09-14T20:17:23.036Z"
}
```

- `phase` : `DRAW` | `MAIN` | `COMBAT` | `END`.
- `gameLog` : les dernières entrées du **journal de diagnostic** (feature 6.5,
  50 par défaut) — utile pour amorcer le panneau de debug quand on rejoint une
  partie en cours. Détail des champs en §7.6.
- `gameOver` true → `winnerId` et `endReason` apparaissent.
- `reactionWindow` : `null` hors combat déclaré, sinon :

```json
{ "kind": "ATTACK", "defendingPlayerId": "DocGuest", "attackerInstanceId": "uuid" }
```

### 7.2 `PlayerStateDTO`

```json
{
  "playerId": "DocHost",
  "name": "DocHost",
  "connected": true,
  "deckCount": 4,
  "hand": [ /* CardInstanceDTO, §7.3 */ ],
  "field": [ ],
  "trash": [ ],
  "eddiesArea": [ ],
  "legendsArea": [ /* 3 legends : visibles pour soi, masquées chez l'adversaire */ ],
  "gigs": [ ],
  "fixerDice": [ "d4", "d6", "d8", "d10", "d12", "d20" ],
  "gigCount": 0,
  "streetCred": 0,
  "eddies": 0,
  "availableEddies": 0,
  "costDiscount": 0,
  "hasSoldThisTurn": false
}
```

| Champ | Sens |
| --- | --- |
| `connected` | false tant que le joueur n'a aucune session STOMP active (minuteur §8) |
| `deckCount` | taille de pioche (le contenu n'est jamais exposé) |
| `hand` / `field` / `trash` / `eddiesArea` / `legendsArea` | les 5 zones de cartes (le deck n'a pas de zone exposée, juste `deckCount`) |
| `gigs` | valeurs des dés Gig possédés (ex. `[2, 6]`), `gigCount` = `gigs.length` |
| `fixerDice` | dés pas encore lancés (un dé en moins par tour joué) |
| `eddies` / `availableEddies` | Eddies possédés / immédiatement dépensables |
| `costDiscount` | réduction de coût courante (effets de cartes) |
| `hasSoldThisTurn` | garde-fou UI (le serveur applique de toute façon la limite 1/tour) |

### 7.3 `CardInstanceDTO`

Carte vue par son propriétaire (main) :

```json
{
  "instanceId": "02bdbfcf-dd69-4a72-ba10-ec21f98f4684",
  "cardId": "adam-smasher-metal-over-meat",
  "name": "Adam Smasher",
  "type": "unit",
  "color": "yellow",
  "baseCost": 9,
  "cost": 9,
  "power": 15,
  "powerBonus": 0,
  "damage": 0,
  "streetCredThreshold": null,
  "keywords": [ "play" ],
  "abilities": [ "{Play} Defeat all other Units." ],
  "ownerId": "DocHost",
  "zone": "HAND",
  "faceDown": false,
  "exhausted": false,
  "summoningSickness": false,
  "attachments": [ ]
}
```

Carte **masquée** de l'adversaire (main, deck, legends face cachée) :

```json
{
  "instanceId": "c5eb582e-b752-4936-9f9d-3750a9ca47d5",
  "cardId": "hidden",
  "name": "Carte masquée",
  "type": "unit",
  "color": "yellow",
  "cost": 0,
  "powerBonus": 0,
  "damage": 0,
  "keywords": [ ],
  "abilities": [ ],
  "ownerId": "DocGuest",
  "zone": "HAND",
  "faceDown": false,
  "exhausted": false,
  "summoningSickness": false,
  "attachments": [ ]
}
```

Règles de masquage (traitement serveur, ne pas tenter de deviner) :
- `cardId = "hidden"`, `name = "Carte masquée"`, `baseCost`/`power`/
  `streetCredThreshold` absents, `keywords` et `abilities` vides, `cost: 0`,
  bonus et dégâts à 0 ;
- `instanceId`, `ownerId`, `zone`, `type`, `color` sont conservés (dos de carte
  typé/couleur, suivi des mouvements) ;
- le deck n'est jamais exposé en contenu, même à son propriétaire
  (`deckCount` uniquement).

Détails des champs :

| Champ | Valeurs |
| --- | --- |
| `type` | `unit` \| `gear` \| `program` \| `legend` |
| `color` | `red` \| `green` \| `blue` \| `yellow` |
| `zone` | `DECK` \| `HAND` \| `FIELD` \| `TRASH` \| `EDDIES_AREA` \| `LEGENDS_AREA` \| `REMOVED` |
| `keywords[]` | `go_solo`, `blocker`, `quick`, `flip`, `play`, `attack` (valeurs JSON en snake_case) |
| `baseCost` | coût imprimé (absent sur les Programs/Legends sans coût) ; `cost` = coût effectif actuellement payable (plancher 0) |
| `power` | puissance effective (`base + powerBonus - damage`, plancher 0) ; absent si la carte n'a pas de puissance |
| `attachedTo` | UUID de l'Unit hôte quand ce Gear est équipé (absent sinon) |
| `attachments[]` | UUID des Gears équipés sur cette Unit |
| `exhausted` | carte déjà utilisée ce tour (attaque, capacité) |
| `summoningSickness` | ne peut pas attaquer ce tour (sauf `go_solo`) |
| `faceDown` | carte retournée (legends non révélées, cartes vendues) |

> Les définitions complètes des cartes (textes, art, tags, rareté) viennent de
> l'API REST `GET /api/cards` indexée par `cardId` ; le WS ne transporte que
> l'état de l'exemplaire.

### 7.4 Entrées de journal (`GameLogEntryDTO`) et valeurs de `type`

```json
{ "index": 6, "type": "GIG_ROLLED", "playerId": "DocGuest",
  "description": "lancer d4 → 2 (total 1 Gigs)" }
```

`index` est global et croissant à partir de 0, pratique pour réconcilier
`newEvents` avec le journal déjà connu.

Valeurs possibles de `type` (enum `GameEventType`) :

| Type | Déclencheur |
| --- | --- |
| `TURN_STARTED` / `TURN_ENDED` | changement de tour |
| `PHASE_CHANGED` | transition Draw/Main/Combat/End |
| `CARD_DRAWN` | pioche |
| `CARD_PLAYED` | Unit posée / Program résolue / Gear équipé / Legend retournée |
| `CARD_SOLD` | vente (carte révélée → Eddies Area face cachée, prête ; aucun Eddie immédiat) |
| `LEGEND_FLIPPED` | retournement d'une Legend |
| `ATTACK_DECLARED` | attaque déclarée (cible ou vol direct) |
| `REACTION_WINDOW_OPENED` / `REACTION_WINDOW_CLOSED` | fenêtre QUICK du défenseur |
| `UNIT_DEFEATED` | Unit vaincue au combat |
| `GIG_STOLEN` | vol de Gig réussi |
| `GIG_ROLLED` | dé Gig lancé en début de tour |
| `EFFECT_RESOLVED` | résolution d'un effet de carte |
| `GAME_WON` | fin de partie |

### 7.5 `sequence`

Entier strictement croissant par partie, incrémenté à chaque diffusion d'état.
Si une `STATE` arrive avec un `sequence` ≤ du dernier connu, l'ignorer ; s'il
manque des numéros, envoyer un `resync`. Le `resync` ne consomme pas de numéro
(il renvoie la dernière séquence sans l'incrémenter).

### 7.6 Journal de diagnostic — `GameLogMessage` (feature 6.5)

Diffusé sur `/topic/game/{gameId}/log` à chaque action journalisée :

```json
{
  "type": "LOG",
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "entries": [
    {
      "index": 12,
      "timestamp": "2026-09-15T10:02:11.482Z",
      "turnNumber": 2,
      "phase": "MAIN",
      "playerId": "DocHost",
      "actionType": "SELL_CARD",
      "description": "Joueur DocHost : vendre une carte → REFUSÉ (Une seule vente par tour)",
      "result": "ILLEGAL",
      "details": { "reason": "Une seule vente par tour", "command": "SellCardCommand" }
    }
  ]
}
```

| Champ | Valeurs / rôle |
| --- | --- |
| `type` | toujours `LOG` (permet de distinguer ce flux d'un `STATE`) |
| `entries` | entrées **nouvelles uniquement** (`index` strictement croissant) |
| `result` | `SUCCESS` (vert), `ILLEGAL` (rouge), `FAILED` (orange), `INFO` (jaune) |
| `actionType` | `PLAY_CARD`, `ATTACK`, `SELL_CARD`, `SPEND_LEGEND`, `END_TURN`, `DRAW`, `GIG_ROLL`, `VICTORY_CHECK`, `VICTORY`, `REACTION_WINDOW`, `UNIT_DEFEATED`, `GIG_STOLEN`, `EFFECT`, `SETUP`, `GAME_START`, `DEBUG_FORCE_PHASE`, `CONCEDE`… |

Différence avec `log` (journal public `GameEvent`) : le journal de diagnostic
consigne **aussi les refus** et les vérifications internes, avec leur motif
(`details.reason`) ; il est borné à 200 entrées côté serveur. Après une
reconnexion, un `resync` suffit : l'état embarque `gameLog`, et les entrées déjà
connues sont dédupliquées par leur `index`. Mode d'emploi complet :
`docs/DEBUG-GUIDE.md`.

---

## 8. Présence et déconnexions

1. Le serveur compte les sessions STOMP actives par pseudo (plusieurs onglets
   tolérés : tant qu'au moins une session existe, le joueur est `connected`).
2. À la fermeture de la **dernière** session d'un joueur **en partie** :
   - publication de `PLAYER_DISCONNECTED` (§6) sur le topic public ;
   - nouvelle `STATE` avec `connected: false` pour ce joueur ;
   - armement d'un minuteur de **120 s** (configurable via
     `GAME_DISCONNECT_GRACE`, format ISO-8601, ex. `PT2M`, `PT30S`).
3. Si le joueur se reconnecte (même pseudo) avant l'échéance :
   - le minuteur est annulé ;
   - `PLAYER_RECONNECTED` est publié ;
   - une `STATE` est poussée aux deux joueurs ; le joueur de récupère aussi
     son état dès qu'il envoie `resync`.
4. Sans reconnexion au bout de 120 s : **forfait automatique**, l'adversaire
   gagne (`GAME_OVER`, `endReason` contenant « Forfait déconnexion »).
5. Dans un **salon en attente**, une déconnexion :
   - de l'hôte → fermeture du salon ;
   - (l'invité ne peut être assis qu'au moment exact du démarrage, il n'y a pas
     d'état « invité attendant » persistant).

Comportement attendu du client :
- à la réception de `PLAYER_DISCONNECTED`, afficher un compte à rebours de
  `reconnectDeadInSeconds` et griser le siège adverse ;
- sur coupure réseau, reconnecter la WebSocket avec le même en-tête `pseudo`,
  se réabonner à toutes les destinations, puis envoyer les `resync` des parties
  en cours (le joueur connaît ses `gameId` via les salons/états locaux ; une
  nouvelle connexion ne peut pas « lister mes parties » en V1 — le client
  conserve le dernier état).

---

## 9. Erreurs (`WsError`)

Toujours sur `/user/queue/errors` :

```json
{
  "type": "ERROR",
  "code": "ILLEGAL_ACTION",
  "message": "Ce n'est pas le tour de DocGuest",
  "destination": "/app/game/c764abe0-7ab9-4f74-bfb1-7f4301d48720/action",
  "gameId": "c764abe0-7ab9-4f74-bfb1-7f4301d48720",
  "clientRequestId": "req-42"
}
```

Une erreur **ne mute pas la partie** : aucune `STATE` n'est diffusée pour
l'action fautive. Le client peut afficher `message` (textes en français) et
doit corréler via `clientRequestId`.

### Codes

| Code | Origine | Causes typiques |
| --- | --- | --- |
| `INVALID_PSEUDO` | lobby / CONNECT | pseudo absent ou hors motif 2-20 |
| `ROOM_NOT_FOUND` | lobby | code inconnu / mal orthographié |
| `ROOM_NOT_JOINABLE` | lobby | salon plein ou déjà en partie |
| `PSEUDO_TAKEN` | lobby | ce pseudo est déjà assis dans ce salon |
| `ALREADY_IN_ROOM` | lobby | le pseudo occupe déjà un autre siège |
| `GAME_IN_PROGRESS` | lobby | `leave` alors que la partie est lancée |
| `DECK_INVALID` | deck | carte inconnue, nombre de legends ≠ 3, < 10 cartes hors legends, doublon |
| `ILLEGAL_ACTION` | partie | action inconnue, cible obligatoire manquante, ou règle violée (mauvaise phase/tour, Eddies insuffisants, Street Cred insuffisant, mal d'invocation, BLOCKER non géré, unité déjà épuisée, vente déjà faite, carte introuvable…) |
| `BAD_REQUEST` | partie | payload mal formé |
| `GAME_NOT_FOUND` | partie | `gameId` inconnu |
| `INTERNAL_ERROR` | partie | anomalie inattendue (un identifiant de corrélance côté support) |

> Les messages de règle (`ILLEGAL_ACTION`) sont volontairement explicites et
> rédigés en français (« Eddies insuffisants : 2 pour un coût de 4 »,
> « Une seule vente par tour (déjà effectuée) », …) : le frontend peut les
> afficher tels quels dans un toast.

---

## 10. Scénario complet de référence

```text
1. Val  → CONNECT (pseudo: Val)
2. Johnny → CONNECT (pseudo: Johnny)

3. Val   SUB /user/queue/errors, /user/queue/lobby, /topic/rooms
4. Val   SEND /app/lobby.create        { "roomName": null }
5. S     → Val  /user/queue/lobby      LOBBY_STATE(WAITING, code=6SQX4Z)
6. S     → /topic/rooms                ROOMS [6SQX4Z]

7. Johnny SUB /topic/rooms, /topic/lobby/6SQX4Z
8. Johnny SEND /app/lobby.join         { "roomCode": "6SQX4Z" }
9. S     → /topic/lobby/6SQX4Z         LOBBY_STATE(PLAYING, gameId=…)
10. S    → /topic/game/{gameId}        GAME_STARTED
11. S    → /topic/game/{gameId}/Val    STATE (peut précéder l'abonnement)
12. S    → /topic/game/{gameId}/Johnny STATE

13. Val & Johnny SUB /topic/game/{gameId}
                    /topic/game/{gameId}/{monPseudo}
14. Val   SEND /app/game/{gameId}/resync   {}
15. Johnny SEND /app/game/{gameId}/resync  {}
16. S    → chacun reçoit sa STATE initiale masquée (sequence=1, log=[TURN_STARTED])

17. Val   SEND /app/game/{gameId}/action  { "action": "SELL_CARD",
            "instanceId": "…", "clientRequestId": "a1" }
18. S    → STATE aux deux (sequence=2, newEvents=[CARD_SOLD], clientRequestId=a1 pour Val)

19. Val   SEND /app/game/{gameId}/action  { "action": "END_TURN" }
20. S    → STATE aux deux (tour 2, Johnny actif, Gig d4 lancé, newEvents=6 entrées)

21. Johnny (encore tour 1 logique) SEND action PLAY_CARD
    S    → /user/queue/errors ERROR ILLEGAL_ACTION "Ce n'est pas le tour de …"
    (Val ne reçoit rien)

22. Coupure réseau de Johnny
23. S    → /topic/game/{gameId}   PLAYER_DISCONNECTED(Johnny, 120)
24. S    → STATE : Johnny connected=false

25a. Johnny revient sous 120 s (même pseudo), se réabonne, SEND resync
     S  → PLAYER_RECONNECTED + STATE(connected=true)
25b. OU, au-delà de 120 s
     S  → GAME_OVER(winner=Val, "Forfait déconnexion de Johnny") + STATE finale
```

---

## 11. Points d'attention pour l'implémentation client

1. **Toujours s'abonner avant d'agir**, et faire un `resync` après chaque
   (re)connexion : les messages ne sont pas persistés par le broker simple.
2. **Remplacer l'état, ne pas le patcher :** chaque `STATE` est complet.
3. Ne s'abonner qu'à son topic d'état
   `/topic/game/{gameId}/{monPseudo}`.
4. Conserver `instanceId` comme clé React des cartes (stable sur toute la
   partie ; `cardId` n'est pas unique : un deck contient plusieurs exemplaires).
5. `clientRequestId` : générer un UUID court par action pour pouvoir
   marquer les actions « en vol » et rattacher les toasts d'erreur.
6. Le STOMP `id` de souscription et les `receipt` sont laissés au choix de la
   librairie ; le protocole n'en dépend pas.
7. Les navigateurs n'imposent pas de limite de buffer problématique ; les
   états complets font de l'ordre de 9 à 20 Ko en début de partie.
8. Émulation HTTP : en dev, faire passer le WS par le proxy Vite
   (`ws: true` sur `/ws`) pour éviter toute gestion CORS côté navigateur.
9. **Journal de diagnostic** : s'abonner à `/topic/game/{gameId}/log` (fait par
   `useGameSocket().watchGame`) et dédupliquer par `index` ; le panneau de debug
   (`components/game/DebugPanel.vue`, touche <kbd>F12</kbd>) s'en sert, ainsi que
   de `state.gameLog` après un `resync`. Les routes REST `/api/debug/**`
   n'existent que sous les profils Spring `test`/`dev`.
