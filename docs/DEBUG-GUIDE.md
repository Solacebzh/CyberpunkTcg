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
`RAM rouge insuffisante`, `Tu dois choisir exactement 2 dé(s) Gig à voler…`,
`Une attaque est déjà en cours`…).

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
| `actionType` | `PLAY_CARD` (ou `FLIP`/`CALL` pour Legend), `ATTACK`, `SELL_CARD`, `SPEND_RESOURCE` (Mini-Feature 4, R4 : Legend **ou** carte de l'Eddies Area), `SPEND_LEGEND`, `SPEND_EDDIES`, `END_TURN`, `DRAW_CARD` / `SELECT_DIE` (Mini-Feature 5 : commandes de la phase DRAW interactive, refus compris), `DRAW_STEP` (étape attendue : `AWAITING_DRAW`, `AWAITING_DIE_SELECT`), `DRAW`, `GIG_ROLL`, `VICTORY_CHECK`, `VICTORY`, `TURN_RESET`, `EDDIES_LOST`, `REACTION_WINDOW`, `UNIT_DEFEATED`, `GIG_STOLEN`, `EFFECT` (incl. DISCARD/BUFF/DAMAGE/HEAL/DEFEAT/BOOST_GIG), `SETUP`, `GAME_START`, `DEBUG_FORCE_PHASE`, `CONCEDE`, et depuis la **Mini-Feature 6** `BLOCKER_PROMPT` (fenêtre « Utiliser Blocker ? »), `USE_BLOCKER` / `DECLINE_BLOCK` (décision du défenseur), `GIG_STEAL_CHOICE` (quota N et plafond M proposés à l'attaquant), `GIG_STEAL_AUTO` (vol résolu en fin de tour) et `FIGHT` (comparaison des puissances) |
| `description` | phrase lisible, prête à afficher |
| `result` | `SUCCESS` (vert), `ILLEGAL` (rouge), `FAILED` (orange : légale mais sans effet), `INFO` (jaune) |
| `details` | contexte chiffré (coût, cible, ressources, motif de refus…) |

Le journal est **borné à 200 entrées** par partie (`GameLog.MAX_ENTRIES`) : les
plus anciennes sont évincées, l'index continue de croître.

### 1.2 Exemples de lignes produites

```text
#1  [INFO ] GAME_START      Nouvelle partie : Joueur Val commence (premier joueur tiré au sort, malus 2 Legends spent)
#2  [INFO ] SETUP           Mise en place de Joueur Val : 6 cartes en main, 3 Legends (premier joueur : 2 Legends déjà inclinées → 1 dispo), 6 dés Gig
#3  [OK   ] SPEND_LEGEND    Joueur Val incline Legend face-down → +1 Eddie (total 1, Legends prêtes 0/3)
#3b [OK   ] SPEND_EDDIES    Joueur Val incline Eddies card → +1 Eddie (total 2)
#3c [OK   ] SELL_CARD       Joueur Val vend une carte révélée → EDDIES_AREA face cachée, prête (0 Eddie immédiat : à incliner via SPEND_EDDIES)
#4  [OK   ] PLAY_CARD       Joueur Val flip Legend (Call : coût 1 Eddie, une fois par tour) — 6th Street Recruits reste en Legends Area
#5  [REFUSÉ] SELL_CARD      Joueur Val : vendre une carte → REFUSÉ (Une seule vente par tour)
#5b [REFUSÉ] PLAY_CARD      Joueur Val : flip Legend → REFUSÉ (Call une seule fois par tour)
#5c [REFUSÉ] SPEND_LEGEND   Joueur Val : incliner Legend → REFUSÉ (déjà inclinée)
#6  [INFO ] PHASE           Début du tour 2 — Joueur Johnny (phase DRAW)
#6b [INFO ] VICTORY_CHECK   Vérification victoire : Joueur Johnny a 6/7 Gigs
#6c [INFO ] TURN_RESET      Début de tour : 0 Eddie, cartes redressées ; 3/3 Legend(s) prêtes, 0/0 Eddies prêtes
#6d [INFO ] DRAW_STEP       Phase DRAW : Joueur Johnny doit cliquer sur sa pioche (4 carte(s) dans le deck)
#6e [REFUSÉ] END_TURN       Joueur Johnny : terminer le tour → REFUSÉ (Impossible de terminer le tour pendant la phase Draw : piochez d'abord votre carte)
#6f [OK   ] DRAW            Phase DRAW : Joueur Johnny pioche Maelstrom Ganger
#6g [INFO ] DRAW_STEP       Phase DRAW : Joueur Johnny doit choisir un dé Gig parmi [d4, d6, d8, d10, d12] (le d20 se lance en dernier)
#6h [REFUSÉ] SELECT_DIE     Joueur Johnny : choisir et lancer le dé Gig d20 → REFUSÉ (Le d20 se lance toujours en dernier : choisissez d'abord [d4, d6, d8, d10, d12])
#6i [OK   ] GIG_ROLL        Lancer de Gig : d8 → 5 (total 1 Gigs, Street Cred 5)
#6j [INFO ] PHASE           Phase MAIN : Joueur Johnny peut jouer, incliner ses Legends, vendre 1 carte et attaquer
#7  [OK   ] VICTORY         VICTOIRE : Joueur Johnny atteint 7 Gigs ! (vérifié AU DÉBUT du tour, pas en continu)
#7b [INFO ] EDDIES_LOST     Fin de tour : Eddies perdus s'ils ne sont pas dépensés
#7c [ÉCHEC] DRAW            Phase DRAW : Joueur Johnny doit piocher mais son deck est vide → DÉFAITE
#8  [OK   ] ATTACK          Joueur Johnny attaque directement la Gig Area de Val avec Maelstrom Ganger (power 12)
#8b [INFO ] BLOCKER_PROMPT  Utiliser Blocker ? Joueur Val peut intercepter avec 2 Blocker(s) prêt(s) : [Kiro, Raffen Shiv] (blocage multiple autorisé — seul le dernier Blocker encaisse les dégâts)
#8c [OK   ] USE_BLOCKER     Joueur Val bloque avec Kiro (Unité inclinée, attaque redirigée vers elle)
#8d [OK   ] USE_BLOCKER     Joueur Val bloque avec Raffen Shiv (Unité inclinée, attaque redirigée vers elle)
#8e [OK   ] FIGHT           Combat : Maelstrom Ganger (12) vs Raffen Shiv (3) → Maelstrom Ganger l'emporte
#8f [OK   ] UNIT_DEFEATED   Raffen Shiv est vaincue → Trash
#9  [INFO ] GIG_STEAL_CHOICE Vol de Gigs : quota N = 2 (power 12), plafond strict M = 2 dé(s) à choisir parmi les 3 dés Gigs actifs du défenseur
#9b [OK   ] GIG_STOLEN      Joueur Johnny vole 2 dé(s) Gig : d8 → 6, d6 → 3 (total 4 Gigs, quota 2, plafond 2)
#9c [ÉCHEC] GIG_STOLEN      Attaque directe sans vol de Gig : quota N = 0 (power 0) plafonné à M = 0 — le défenseur n'a 0 dé(s) Gig actif(s) (les dés non lancés de sa Fixer Area ne sont jamais volés)
#9d [REFUSÉ] STEAL_GIG      Joueur Johnny : choisir les dés Gigs à voler → REFUSÉ (Tu dois choisir exactement 2 dé(s) Gig à voler (tu en as désigné 1))
#9e [INFO ] DECLINE_BLOCK   Fin de tour : le défenseur n'a pas utilisé de Blocker, l'attaque suit son cours
#9f [INFO ] GIG_STEAL_AUTO  Fin de tour : vol de 1 dé(s) Gig résolu automatiquement (quota 1, plafond 1)
```

Depuis la **Mini-Feature 5**, la phase DRAW est interactive : `END_TURN` laisse
la partie en `DRAW` / `AWAITING_DRAW`, puis le joueur envoie `DRAW_CARD` et
`SELECT_DIE` (voir `docs/WEBSOCKET-PROTOCOL.md` §5.1). Le champ `drawStep` de
`GET /api/debug/game/{id}` indique l'étape attendue.

Depuis la **Mini-Feature 6**, le combat l'est aussi : une attaque peut rester
suspendue sur `AWAITING_BLOCK` (le **défenseur** doit envoyer `USE_BLOCKER` ou
`DECLINE_BLOCK`) puis sur `AWAITING_STEAL_CHOICE` (l'**attaquant** doit envoyer
`STEAL_GIG` avec exactement `M` dés). Le champ `pendingAttack` de
`GET /api/debug/game/{id}` (et de chaque `STATE`) porte `step`, `quota` (N),
`stealableCount` (M), `targetInstanceId` et `blockerInstanceIds` ; `gigDieIds`
par joueur liste les identifiants des dés Gigs **actifs** (seuls volables).

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
- **Puce « combat » (Mini-Feature 6)** : quand `pendingAttack` est présent, le
  panneau affiche l'étape (`AWAITING_BLOCK` / `AWAITING_STEAL_CHOICE`),
  l'attaquant, le défenseur, la cible (ou `GIG_AREA` pour un vol direct),
  `quota` N et `stealableCount` M — utile pour vérifier que le plafond strict est
  bien celui du serveur avant d'envoyer un `STEAL_GIG` ;
- **« Télécharger JSON »** : exporte l'état complet (dont `pendingAttack` et les
  `gigDieIds`), idéal pour joindre un rapport de bug.

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

# Combat en cours (Mini-Feature 6) : étape attendue, quota N, plafond M
curl -s "localhost:8080/api/debug/game/$GAME_ID" | jq '.pendingAttack'

# Identifiants des dés Gigs actifs (les seuls volables) : gigDieIds / gigs / gigDice
curl -s "localhost:8080/api/debug/game/$GAME_ID/player/Val" \
  | jq '{gigs, gigDice, gigDieIds, fixerDice}'

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

Réponse : `DebugGameStateDTO` — tour, phase, `drawStep` (sous-étape de la phase
DRAW interactive, absente hors DRAW), joueur actif, graine, fenêtre de réaction,
`pendingAttack` (Mini-Feature 6 : attaque suspendue — `step`, `quota`,
`stealableCount`, `targetInstanceId`, `blockerInstanceIds` ; absent si rien
n'attend de décision), état complet des deux joueurs (dont `gigDice`, le type de
dé de chaque Gig, et `gigDieIds`, l'identifiant de chaque dé **actif**),
journal public et journal de diagnostic (paramètre `?logs=` de 1 à 200, défaut 20).

### 3.3 `POST /api/debug/game/{gameId}/force-phase`

Force la phase courante (`DRAW`, `MAIN`, `COMBAT`, `END`) pour tester une règle
sans dérouler les phases précédentes. L'opération est **journalisée**
(`DEBUG_FORCE_PHASE`) et diffusée sur `/topic/game/{gameId}/log`, donc visible
des deux joueurs dans le panneau. Forcer `DRAW` positionne `drawStep =
AWAITING_DRAW` : le joueur actif doit alors cliquer sur sa pioche (`DRAW_CARD`)
puis choisir un dé (`SELECT_DIE`) pour revenir en `MAIN`.

### 3.4 `GET /api/debug/game/{gameId}/player/{playerId}`

Vue détaillée d'un joueur : Eddies, Gigs et Street Cred (avec `gigDice` et
`gigDieIds`, Mini-Feature 6), dés Gig restants en Fixer Area (**jamais
volables**), Legends inclinées/disponibles, **plafonds de RAM par couleur**,
main, pioche, Field, défausse, Eddies Area, Legends Area.

### 3.5 `GET /api/debug/games`

Liste des parties vivantes en mémoire (id, tour, phase, joueur actif, gagnant) :
utile quand on ne connaît pas le `gameId`.

---

## 4. Recettes de diagnostic

| Symptôme | Marche à suivre |
| --- | --- |
| « Mon action ne fait rien » | Panneau Debug → chercher la ligne rouge (`REFUSÉ`). Le champ `details.reason` donne la règle violée ; `details.command` indique la commande concernée. |
| « Je n'ai pas assez d'Eddies » | Ligne `SPEND_LEGEND` / `SPEND_EDDIES` du tour : les Eddies disponibles n'apparaissent qu'après l'inclinaison d'une Legend ou d'une carte de l'Eddies Area (0 au début, perdus à la fin). **Une vente (`SELL_CARD`) ne crédite rien** : elle pose la carte face cachée et prête en Eddies Area — il faut ensuite l'incliner (`SPEND_EDDIES`) pour 1 €$. `GET /api/debug/…/player/{id}` montre `eddies`, `legendsReady`, `eddiesAreaReady`. |
| « Je ne peux pas jouer une carte » | Vérifier `eddies` (coût) — **la RAM n'est PLUS vérifiée en partie** (uniquement deckbuilder). Si une carte à 6 Eddies est refusée, la source est `Eddies insuffisants`. |
| « Mon attaque ne vole pas de Gig » | Ligne `GIG_STOLEN` **orange** (`ÉCHEC`) : `quota N = … plafonné à M = 0`. Cause = power 0, ou **aucun dé Gig actif** chez le défenseur (les dés non lancés de sa Fixer Area ne comptent pas et ne sont jamais volés ni créés). Autre cause : le défenseur a intercepté (`BLOCKER_PROMPT` → `USE_BLOCKER`) — une attaque redirigée ne vole rien. |
| « Le choix des dés volés est refusé » | Ligne `STEAL_GIG` rouge : le nombre d'identifiants doit être **exactement `M`** (`pendingAttack.stealableCount`), sans doublon, et chaque identifiant doit figurer dans les `gigDieIds` du défenseur (dés **actifs** seulement). Relire `GIG_STEAL_CHOICE` (jaune) qui annonce N et M. |
| « Je ne peux plus attaquer » | Ligne `ATTACK` rouge `Une attaque est déjà en cours` : `pendingAttack` n'est pas résolu. Le défenseur doit répondre (`USE_BLOCKER`/`DECLINE_BLOCK`) ou l'attaquant envoyer `STEAL_GIG` ; `END_TURN` résout d'office (voir `DECLINE_BLOCK` + `GIG_STEAL_AUTO` en jaune). Une autre cause : la cible est une Unit **prête** — seules les Units dépensées (ou la Gig Area) sont attaquables. |
| « Le combat n'a pas tué la bonne Unit » | Ligne `FIGHT` (puissances comparées, dont l'issue) + `UNIT_DEFEATED` : à égalité, **les deux** Units sont vaincues. En blocage multiple, c'est le **dernier** `{Blocker}` de `cardIds` qui encaisse (`USE_BLOCKER` répété, un par Blocker). |
| « La partie ne se termine pas » | Ligne `VICTORY_CHECK` : la victoire se vérifie **au début du tour** du joueur (7 Gigs), pas pendant le tour où les Gigs sont acquis. |
| « Je ne peux pas finir mon tour / rien ne se passe en début de tour » | Phase `DRAW` interactive (Mini-Feature 5) : chercher la dernière ligne `DRAW_STEP`. `AWAITING_DRAW` → cliquer sur la pioche (`DRAW_CARD`) ; `AWAITING_DIE_SELECT` → choisir un dé (`SELECT_DIE`, le `d20` est refusé tant qu'il reste d'autres dés). Les refus apparaissent en rouge avec le motif. |
| « Je veux rejouer une situation précise » | `force-phase` pour se placer dans la phase voulue, puis lire `seed` pour connaître la graine des tirages. |
| « Je veux voir ce que voit le serveur » | `GET /api/debug/game/{id}` : aucune information masquée, `gameLog` complet (200 max, incl. TURN_RESET, EDDIES_LOST). |

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

- Backend : `GameIntegrationTest` (51 scénarios R1→R13, journal compris),
  `engine/CombatStealTest` (23 scénarios `testR6_*` — Mini-Feature 6 : quota,
  plafond strict, blocage, choix des dés, journal `GIG_STEAL_CHOICE`/`USE_BLOCKER`),
  `GameServiceTest` (refus consignés, premier joueur tiré au sort),
  `LobbyGameFlowWebSocketIntegrationTest` (partie STOMP de bout en bout).
- Frontend : `src/__tests__/debugPanel.spec.ts` (ouverture par bouton et F12,
  code couleur, appel de `/api/debug`, message d'erreur sur 404),
  `src/__tests__/combatSteal.spec.ts` (quota N, plafond strict M, blocage multiple,
  dés volés type/valeur conservés, victoire à 7 — via le mock de protocole, qui
  sert les mêmes champs `pendingAttack` / `gigDieIds` que le backend).

```bash
cd backend && mvn clean test          # moteur, service, WS, debug
cd frontend && npm run test:unit      # panneau de debug
```
