# Protocole temps réel (WebSocket STOMP)

## 1. Connexion

| Élément | Valeur |
| --- | --- |
| Endpoint | `/ws` (ex. `ws://localhost:8080/ws`, en dev via le proxy Vite : `ws://localhost:5173/ws`) |
| Fréquence des battements de cœur | 10 s entrant / 10 s sortant |
| Reconnexion client | automatique, toutes les 4 s (configurée dans `frontend/src/services/socket.ts`) |
| Broker | simple broker en mémoire Spring (`/topic`, `/queue`) |
| Préfixe applicatif | `/app` (destinations traitées par les `@MessageMapping`) |
| Préfixe utilisateur | `/user` (messages adressés à une seule session) |
| Sérialisation | JSON (UTF-8) |

## 2. Destinations

| Sens | Destination | Usage | Feature |
| --- | --- | --- | --- |
| Client → serveur | `/app/ping` | Test aller-retour du canal | 01 ✅ |
| Serveur → tous | `/topic/pong` | Réponse au test (`{ type, echo, serverTime }`) | 01 ✅ |
| Client → serveur | `/app/lobby.create` | Créer une salle (code d'invitation généré) | 04 |
| Client → serveur | `/app/lobby.join` | Rejoindre une salle depuis son code | 04 |
| Client → serveur | `/app/lobby.ready` | Signaler qu'on a choisi son deck | 04 |
| Serveur → salle | `/topic/lobby.{lobbyId}` | Composition de la salle, prêts, démarrage | 04 |
| Client → serveur | `/app/game.{gameId}.playCard` | Poser une carte (Unit / Program / Gear) | 05 |
| Client → serveur | `/app/game.{gameId}.sellCard` | Vendre une carte pour ses Eddies | 05 |
| Client → serveur | `/app/game.{gameId}.attack` | Attaquer une Unit (combat) | 05 |
| Client → serveur | `/app/game.{gameId}.attackGigs` | Attaquer l'aire de Gigs adverse (vol) | 05 |
| Client → serveur | `/app/game.{gameId}.flipLegend` | Retourner une Legend face visible | 05 |
| Client → serveur | `/app/game.{gameId}.passPhase` | Terminer la phase courante | 05 |
| Client → serveur | `/app/game.{gameId}.concede` | Abandonner la partie | 05 |
| Serveur → partie | `/topic/game.{gameId}` | **État complet** filtré par destinataire + messages du journal | 04-05 |
| Serveur → joueur | `/user/queue/errors` | Action refusée (invisible pour l'adversaire) | 04-05 |
| Serveur → joueur | `/user/queue/state` | État privé après reconnexion (resynchronisation) | 04 |

## 3. Enveloppes

### Action envoyée par le client

```json
{
  "sequence": 12,
  "clientRequestId": "0f2a-…",
  "payload": { "cardInstanceId": "i-17", "targetInstanceId": "i-23" }
}
```

- `sequence` : compteur strictement croissant par partie, incrémenté par le client. Le serveur refuse
  toute action dont la séquence est inférieure ou égale à la dernière acceptée (protection contre le rejeu).
- `clientRequestId` : identifiant d'affichage, permet de corréler une erreur à l'action fautive côté UI.
- Le **identité du joueur n'est jamais dans le payload** : elle est déduite de la session STOMP.

### Diffusion de l'état

```json
{
  "type": "STATE",
  "gameId": "8f2c…",
  "sequence": 43,
  "state": { "turn": { "…": "…" }, "me": { "…": "…" }, "rival": { "…": "…" } },
  "events": [
    { "type": "CARD_PLAYED", "playerId": "a0…", "instanceId": "i-17" },
    { "type": "GIG_STOLEN", "from": "b1…", "to": "a0…", "count": 1 }
  ]
}
```

- `type` ∈ `STATE`, `GAME_OVER`, `LOBBY_UPDATE`, `PONG` (tests).
- `events` sert uniquement aux **animations** (GSAP) : le client peut les ignorer, l'état `state` reste la vérité.
- L'état est **diffusé en entier** à chaque action : la resynchronisation après reconnexion est donc triviale.

### Erreur privée

```json
{
  "type": "ERROR",
  "code": "NOT_ENOUGH_EDDIES",
  "clientRequestId": "0f2a-…",
  "message": "Il faut 4 Eddies, vous en avez 3."
}
```

Codes prévus : `NOT_YOUR_TURN`, `NOT_ENOUGH_EDDIES`, `STREET_CRED_TOO_LOW`, `RAM_VALUES_NOT_ALLOWED`,
`ILLEGAL_TARGET`, `CARD_NOT_IN_HAND`, `UNIT_ALREADY_SPENT`, `SEQUENCE_OUT_OF_ORDER`, `GAME_NOT_FOUND`,
`LOBBY_FULL`, `DECK_INVALID`.

## 4. Exemple complet (état actuel du code, feature 01)

Le seul échange implémenté aujourd'hui est le test aller-retour :

```javascript
// frontend/src/services/socket.ts (usage)
const socket = new GameSocket({ onStateChange: (state) => console.log(state) })
socket.connect()
socket.subscribe('/topic/pong', (pong) => console.log('pong reçu', pong))
socket.publish('/app/ping', { message: 'hello' })

// Message reçu :
// { "type": "pong", "echo": "hello", "serverTime": "2026-09-14T14:50:46Z" }
```

Côté serveur, la même boucle que les futurs contrôleurs de jeu :

```java
@MessageMapping("/ping")
@SendTo("/topic/pong")
public PongResponse ping(@Payload(required = false) PingRequest request) { … }
```

## 5. Règles à respecter pour tout nouveau message

1. **Ajouter la ligne dans le tableau §2** avant d'écrire le code (destination + sens + feature).
2. Actions : `SEND /app/...` et nom au **verbe** (`playCard`, `attackGigs`) ; diffusions : `SUB /topic/...`.
3. Le serveur **ne fait jamais confiance** au client : identité, appartenance à la partie, phase en cours,
   légalité de la cible sont revalidés à chaque message.
4. Toute information **privée** passe par `/user/queue/**`, jamais par `/topic/**`.
5. Un message accepté produit **un** état diffusé (pas de rafale) ; les rafales sont regroupées côté serveur.
6. Changement de contrat = ligne mise à jour ici **et** dans la PR correspondante.
