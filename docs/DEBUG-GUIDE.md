# Guide de debug — journal, endpoints et panneau (Feature 6.5)

Ce guide explique comment **voir ce que fait réellement le moteur de règles** :
chaque action (acceptée, refusée ou sans effet) est journalisée côté serveur,
diffusée en temps réel aux clients et consultable via un endpoint réservé au
développement. Il complète `docs/RULE-ENGINE.md` (règles) et
`docs/WEBSOCKET-PROTOCOL.md` (transport).

---

## 1. Deux journaux, deux usages

| Journal | Nature | Destinataire | Contenu |
| --- | --- | --- | --- |
| **Journal public** (`GameEvent` → `GameLogEntryDTO`) | récit de partie | les joueurs | événements visibles : pioche, attaque, vol de Gig, victoire… |
| **Journal de diagnostic** (`GameLog` → `GameActionLogDTO`) | trace technique | développeur | **toutes** les actions, y compris les **refus** et les vérifications internes |

Le journal public ne dit jamais *pourquoi* une action a échoué : une action
illégale ne produit aucun événement. Le journal de diagnostic, lui, consigne le
motif exact (`Eddies insuffisants`, `Une seule vente par tour`,
`RAM rouge insuffisante`, `Un BLOCKER rival doit intercepter cette attaque`…).

### 1.1 Anatomie d'une entrée

```json
{
  "index": 12,
  "timestamp": "2026-09-15T10:02:11.482Z",
  "turnNumber": 2,
  "phase": "MAIN",
  "playerId": "Val",
  "actionType": "PLAY_CARD",
  "description": "Joueur Val joue 6th Street Recruits (coût: 4 Eddies, 2 RAM rouge)",
  "result": "SUCCESS",
  "details": { "card": "6th Street Recruits", "paid": 4, "eddiesLeft": 1, "ram": 2 }
}
```

| Champ | Rôle |
| --- | --- |
| `index` | ordre global dans la partie (1, 2, 3…) — sert à réconcilier temps réel et état |
| `turnNumber`, `phase` | contexte au moment de l'action |
| `playerId` | auteur (`null` pour une ligne système) |
| `actionType` | `PLAY_CARD`, `ATTACK`, `SELL_CARD`, `SPEND_LEGEND`, `END_TURN`, `DRAW`, `GIG_ROLL`, `VICTORY_CHECK`, `VICTORY`, `REACTION_WINDOW`, `UNIT_DEFEATED`, `GIG_STOLEN`, `EFFECT`, `SETUP`, `GAME_START`, `DEBUG_FORCE_PHASE`, `CONCEDE`… |
| `description` | phrase lisible, prête à afficher |
| `result` | `SUCCESS` (vert), `ILLEGAL` (rouge), `FAILED` (orange : légale mais sans effet), `INFO` (jaune) |
| `details` | contexte chiffré (coût, cible, ressources, motif de refus…) |

Le journal est **borné à 200 entrées** par partie (`GameLog.MAX_ENTRIES`) : les
plus anciennes sont évincées, l'index continue de croître.

### 1.2 Exemples de lignes produites

```text
#1  [INFO ] GAME_START      Nouvelle partie : Joueur Val commence (premier joueur tiré au sort)
#2  [INFO ] SETUP           Mise en place de Joueur Val : 6 cartes en main, 3 Legends (premier joueur : 2 Legends déjà inclinées), 6 dés Gig
#3  [OK   ] SPEND_LEGEND    Joueur Val incline Legend 3 → +1 Eddie (total 1 Eddies, Legends prêtes 0/3)
#4  [OK   ] PLAY_CARD       Joueur Val joue 6th Street Recruits (coût: 4 Eddies, 2 RAM rouge)
#5  [REFUSÉ] SELL_CARD      Joueur Val : vendre une carte → REFUSÉ (Une seule vente par tour)
#6  [INFO ] VICTORY_CHECK   Vérification victoire : Joueur Johnny a 6/7 Gigs
#7  [OK   ] VICTORY         VICTOIRE : Joueur Johnny atteint 7 Gigs ! (vérifié au début de son tour)
```

---

## 2. Où lire le journal

### 2.1 En jeu : le panneau de debug (frontend)

- **Ouverture** : bouton `Déplier` du panneau « Debug » (sous le journal de
  partie) ou touche <kbd>F12</kbd> ;
- **Couleurs** : vert = action acceptée, rouge = action refusée (avec son motif),
  orange = action sans effet, jaune = information (phase, vérification de victoire) ;
- **Défilement automatique** vers la dernière ligne ;
- **« Voir état complet »** : `GET /api/debug/game/{gameId}` (état **non masqué**
  des deux joueurs : mains, pioches, Legends, plafonds de RAM, journal) ;
- **`DRAW` / `MAIN` / `COMBAT` / `END`** : force la phase courante (voir §3.3) ;
- **« Télécharger JSON »** : exporte l'état complet, idéal pour joindre un
  rapport de bug.

### 2.2 Temps réel (STOMP)

Le serveur publie les nouvelles entrées sur **`/topic/game/{gameId}/log`** :

```json
{ "type": "LOG", "gameId": "8f1c…", "entries": [ { "index": 12, "result": "ILLEGAL", … } ] }
```

Le client s'y abonne automatiquement (`useGameSocket().watchGame`), l'entrée est
distinguée des états personnels par le champ `type` (`LOG` vs `STATE`).
Le même contenu est embarqué dans chaque état (`state.gameLog`, 50 dernières
lignes) pour amorcer le panneau quand on rejoint une partie en cours.

### 2.3 En tête-à-tête avec le backend

```bash
# Parties en mémoire (quand on ne connaît pas le gameId)
curl -s localhost:8080/api/debug/games | jq

# État complet non masqué + 50 dernières lignes de journal
curl -s "localhost:8080/api/debug/game/$GAME_ID?logs=50" | jq '.phase, .gameLog[-5:]'

# Détail d'un joueur (main et pioche visibles)
curl -s "localhost:8080/api/debug/game/$GAME_ID/player/Val" | jq '{eddies, gigs, ramCeilings, hand: [.hand[].name]}'

# Forcer une phase (tester une règle sans rejouer la partie)
curl -s -X POST "localhost:8080/api/debug/game/$GAME_ID/force-phase" \
     -H 'Content-Type: application/json' \
     -d '{"phase":"COMBAT","playerId":"Val"}' | jq '.phase'
```

---

## 3. Les endpoints de debug

### 3.1 Garde-fou de profil — **à lire avant tout**

`DebugController` porte `@Profile({"test","dev"})` : **les routes n'existent pas
hors de ces profils** (réponse 404, pas de fuite d'information).

```bash
# Développement (profil dev)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
# Tests d'intégration (le profil `test` est déjà actif dans la suite JUnit)
```

En production, ne jamais activer `dev` : `/api/debug` **ne masque rien** (mains,
pioches et Legends adverses sont visibles). C'est un outil de diagnostic, jamais
une API de jeu.

### 3.2 `GET /api/debug/game/{gameId}`

Réponse : `DebugGameStateDTO` — tour, phase, joueur actif, graine, fenêtre de
réaction, état complet des deux joueurs, journal public et journal de diagnostic
(paramètre `?logs=` de 1 à 200, défaut 20).

### 3.3 `POST /api/debug/game/{gameId}/force-phase`

Force la phase courante (`DRAW`, `MAIN`, `COMBAT`, `END`) pour tester une règle
sans dérouler les phases précédentes. L'opération est **journalisée**
(`DEBUG_FORCE_PHASE`) et diffusée sur `/topic/game/{gameId}/log`, donc visible
des deux joueurs dans le panneau.

### 3.4 `GET /api/debug/game/{gameId}/player/{playerId}`

Vue détaillée d'un joueur : Eddies, Gigs et Street Cred, dés Gig restants,
Legends inclinées/disponibles, **plafonds de RAM par couleur**, main, pioche,
Field, défausse, Eddies Area, Legends Area.

### 3.5 `GET /api/debug/games`

Liste des parties vivantes en mémoire (id, tour, phase, joueur actif, gagnant) :
utile quand on ne connaît pas le `gameId`.

---

## 4. Recettes de diagnostic

| Symptôme | Marche à suivre |
| --- | --- |
| « Mon action ne fait rien » | Panneau Debug → chercher la ligne rouge (`REFUSÉ`). Le champ `details.reason` donne la règle violée ; `details.command` indique la commande concernée. |
| « Je n'ai pas assez d'Eddies » | Ligne `SPEND_LEGEND` / `SELL_CARD` du tour : les Eddies disponibles n'apparaissent qu'après l'inclinaison d'une Legend ou une vente. `GET /api/debug/…/player/{id}` montre `eddies`, `legendsReady`, `legendsSpent`. |
| « Je ne peux pas jouer une carte » | Vérifier `ramCeilings` (plafond = somme des RAM des Legends, par couleur) : une carte dont la RAM imprimée dépasse le plafond de sa couleur est refusée (`RAM rouge insuffisante`). |
| « Mon attaque ne vole pas de Gig » | Chercher `REACTION_WINDOW` puis la ligne `ATTACK` : un **BLOCKER** prêt intercepte (`Un BLOCKER rival doit intercepter cette attaque`) et interdit le vol direct. |
| « Le combat n'a pas tué la bonne Unit » | Ligne `UNIT_DEFEATED` + la ligne `ATTACK` de combat (puissances comparées) : à égalité, **les deux** Units sont vaincues. |
| « La partie ne se termine pas » | Ligne `VICTORY_CHECK` : la victoire se vérifie **au début du tour** du joueur (7 Gigs), pas pendant le tour où les Gigs sont acquis. |
| « Je veux rejouer une situation précise » | `force-phase` pour se placer dans la phase voulue, puis lire `seed` pour connaître la graine des tirages. |
| « Je veux voir ce que voit le serveur » | `GET /api/debug/game/{id}` : aucune information masquée, `gameLog` complet. |

---

## 5. Dépannage

| Observation | Cause probable |
| --- | --- |
| `HTTP 404` sur `/api/debug/...` | Profil Spring différent de `test`/`dev` (comportement voulu). |
| Panneau vide malgré une partie en cours | Aucun abonnement au topic `LOG` : vérifier que `watchGame(gameId)` est appelé (le bouton `resync` le fait) et que le backend est à jour. |
| Ligne jaune `DEBUG_FORCE_PHASE` | Quelqu'un (ou un test) a forcé la phase : le journal trace la manipulation. |
| Journal tronqué au début | Borne de 200 entrées : abonnez-vous au topic `LOG` pour ne rien manquer en direct. |

---

## 6. Tests de référence

- Backend : `GameIntegrationTest` (7 scénarios, journal compris),
  `GameServiceTest` (refus consignés, premier joueur tiré au sort),
  `LobbyGameFlowWebSocketIntegrationTest` (partie STOMP de bout en bout).
- Frontend : `src/__tests__/debugPanel.spec.ts` (ouverture par bouton et F12,
  code couleur, appel de `/api/debug`, message d'erreur sur 404).

```bash
cd backend && mvn clean test          # moteur, service, WS, debug
cd frontend && npm run test:unit      # panneau de debug
```
