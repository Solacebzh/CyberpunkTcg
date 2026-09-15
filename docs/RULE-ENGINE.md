# Moteur de règles (Rule Engine)

Backend Java uniquement. Serveur autoritaire : toute mutation de l'état d'une partie
passe par une commande validée côté serveur. Aucune règle ne vit dans le frontend,
les contrôleurs ou les services (le service orchestre, le moteur décide).

Documents liés : `game-rules.md` (règles), `architecture.md` (§2-3, couches),
`DATA-MODEL.md` (contrat des cartes), `WEBSOCKET-PROTOCOL.md` (transport, feature 04).

## 1. Carte du code

> Feature 6.5.2 (2026-09-15) : corrections TDD R1-R13 — Eddies cycle 0, Legends/Eddies ready, Call 1 Eddie once/turn, RAM désactivée en jeu, SpendEddiesCommand, ADRENALINE, DISCARD/BUFF. Ancienne doc “Legends définitivement inclinées” retirée.
>
> Feature 6.5 : le moteur journalise désormais **chaque** action dans un journal de
> diagnostic (`GameLog`) et expose un panneau de debug
> (`docs/DEBUG-GUIDE.md`). Les règles confirmées sont rappelées au §6.

```text
backend/src/main/java/com/cyberpunktcg/
├── domain/game/          # État de partie (pur Java, zéro dépendance Spring/JPA)
│   ├── GameState.java    # partie 1v1 : joueurs, tour, fenêtre, gagnant, journal, RNG
│   ├── Player.java       # zones, dés, ressources d'un joueur
│   ├── CardInstance.java # exemplaire de carte (UUID + buffs + marqueurs)
│   ├── Zone.java         # DECK, HAND, FIELD, TRASH, EDDIES_AREA, LEGENDS_AREA, REMOVED
│   ├── Phase.java        # DRAW, MAIN, COMBAT, END (+ next())
│   ├── Turn.java         # numéro, joueur actif, phase
│   ├── ReactionWindow.java
│   ├── DieRoll.java      # résultat d'un lancer de dé Gig
│   ├── GameEvent.java / GameEventType.java  # journal (sans secret, base du rejeu R10)
├── engine/               # règles (pur Java, testé sans Spring)
│   ├── GameConstants.java   # 7 Gigs, 1 vente/tour, main de 6 (imposés, non configurables)
│   ├── GameRuleException.java
│   ├── GameEffect.java / EffectType.java / TriggerType.java / EffectTarget.java
│   ├── EffectParser.java    # interprète les abilities JSON (mini-langage + heuristiques)
│   ├── EffectHandler.java   # stratégie d'application d'un type d'effet
│   ├── RuleEngine.java      # resolveEffect(s), defeatUnit, routage Strategy
│   └── command/
│       ├── GameCommand.java      # interface : validate + execute (+ gardes partagés)
│       ├── PlayCardCommand.java
│       ├── AttackCommand.java
│       ├── SellCardCommand.java
│       └── EndTurnCommand.java
└── service/
    └── GameService.java  # parties en mémoire : createGame, executeCommand, getGameState
```

Règle d'import stricte : `domain.game` et `engine` n'importent ni Spring ni JPA
(`domain.game` importe seulement `domain.card` pour les définitions).
`GameService` est le seul pont Spring (chargement des définitions + registre mémoire).

## 2. Flux d'une action

```text
GameService.executeCommand(gameId, command)
        │ 1. charge l'état (404 si inconnu)
        ▼
command.validate(state) ──► GameRuleException si illégal (jamais de mutation)
        │ 2. légal ?
        ▼
command.execute(state)
        │ 3. mute l'état via Player/GameState
        │ 4. déclencheurs via RuleEngine.resolveEffects(...)
        │ 5. journalise des GameEvent (descriptions sans secret)
        ▼
List<GameEvent> retournés (+ état muté en place, journal complété)
```

En feature 04, un contrôleur STOMP construira la commande depuis le message,
appellera `executeCommand`, puis diffusera `getGameState(gameId, joueur)` à chacun.

## 3. Domaine

### 3.1 GameState

- 2 joueurs exactement (ordre des sièges ; le 1er commence), tour 1 en `MAIN`.
- Graine `seed` + `Random` : les lancers de dés Gig sont rejouables (R10).
- `eventLog` append-only (descriptions sans secret → exposé tel quel).
- Victoire : `winnerId` + `endReason` ; `isGameOver()` bloque toute commande.
- Primitives mécaniques (sans journalisation, les appelants journalisent) :
  `drawCards` (deck vide → défaite immédiate), `stealGig` (dé max),
  `rollFixerDie` (`d20` en dernier, garanti par l'ordre de la Fixer Area),
  `totalPowerFor` (unit + gears attachés), `findInstance` / `findInstanceOwner`.
- `maskedCopyFor(viewerId)` : copie détachée aux secrets effacés (§7).

### 3.2 Player

- Zones cartes : `deck` (index 0 = dessus), `hand`, `field`, `trash`,
  `eddiesArea`, `legendsArea` (+ `moveToZone`, `findIn`, `findAnywhere`).
- Dés : `fixerDice` (`d4…d20`, `popFixerDie`), `gigs` (valeurs).
- Dérivés : `getGigCount()`, `getStreetCred()` (somme, jamais stocké).
- Ressources : `eddies` (réserve persistante : vente +1, inclinaison d'une Legend
  +1, jeu = dépense), `costDiscount` (remise `REDUCE_COST`, réinitialisée au début
  du tour), `hasSoldThisTurn` (1 vente/tour).
- **Économie des Eddies (R2/R3/R6)** : chaque carte face-down en Eddies Area vaut 1 Eddie par tour, et chaque Legend (face-up ou face-down) vaut aussi 1 Eddie par tour (Guide § EDDIES & LEGENDS). `spendLegendForEddies` et `spendEddiesCardForEddies` inclinent la carte (exhausted) pour +1 Eddie (compteur remis à 0 au début de chaque tour — `Player.startTurn` fait `eddies=0` et `readyAll` redresse Field+Legends+Eddies). `legendsAvailableForEddies()`, `eddiesAvailableForEddies()` et `countSpentLegends()` exposent la réserve. Le premier joueur démarre avec `FIRST_PLAYER_SPENT_LEGENDS=2` Legends déjà inclinées (ne se redressent qu'à son 2e tour).
- **RAM (R7)** : `ramCeilingFor` existe pour le deckbuilder, mais `GameConstants.RAM_CEILING_ENFORCED=false` — **aucune vérification en jeu** (`PlayCardCommand` ne vérifie plus la RAM). La RAM n'est donc qu'une limite de construction de deck (Guide § DECK BUILDING).
- `startTurn()` : **Eddies remis à 0**, vente (`hasSoldThisTurn`) et Call (`hasCalledLegendThisTurn`) réinitialisés, remise remise à 0, redressement de **toutes** les cartes dépensées (Field+Legends+EddiesArea), fin des mals d'invocation (Lag).
- `readyBlockers()` / `controlsReadyBlocker()` : BLOCKERs prêts (non épuisés).

### 3.3 CardInstance

- Identité : `instanceId` (UUID frais), `cardId` (définition), `ownerId`, `zone`.
- Snapshot imprimé : `type`, `color`, `ram`, `baseCost`, `basePower`,
  `streetCredThreshold`, `keywords`, `abilities` (recopiés à la création).
- Mutable : `powerBonus` / `damage` (buffs), `exhausted`, `faceDown`,
  `summoningSickness`, `attachedTo` / `attachments` (Gears).
- `getEffectivePower()` = `max(0, base + bonus − dégâts)` (`null` si pas de
  puissance imprimée) ; `isLethalDamage()` quand `dégâts ≥ base + bonus`.
- `copy()` / `masked()` (secrets effacés, identité conservée).

### 3.4 Phases et tours

`DRAW → MAIN → COMBAT → END`. En V1, `DRAW`/`END` sont résolues automatiquement
par `EndTurnCommand` (pioche + lancer à l'ouverture, `ON_TURN_END` à la fermeture)
et `MAIN → COMBAT` par la première attaque. Le tour 1 commence en `MAIN`
(main de départ distribuée, sans pioche ni lancer).

Ordre exact du début de tour (`EndTurnCommand`, tours ≥ 2), conforme à
`docs/official-rules.md` §4 :

1. `VICTORY_CHECK` : le joueur actif qui **commence** son tour avec au moins
   `GIGS_TO_WIN` (7) Gigs gagne immédiatement (journal `VICTORY`) ;
2. pioche d'une carte ; **deck vide = défaite** (`GameState.drawCards` désigne le
   vainqueur, le journal consigne `DRAW` en `FAILED` + `VICTORY`) ;
3. lancer d'un dé de la Fixer Area (`d4…d20`, le `d20` en dernier) ;
4. redressement du Field et fin des mals d'invocation (`Player.startTurn()`) ;
5. `Phase.MAIN`.

Le premier joueur est **tiré au sort** à la création (`GameService` +
`Random` injectable pour les tests) ; il subit le malus de mise en place (2
Legends déjà inclinées). Il n'y a pas de mulligan (limite assumée, §11).

## 4. Commandes

| Commande | Ordonnateur | Phase | Effet |
|---|---|---|---|
| `PlayCardCommand` | actif (ou défenseur QUICK en réaction) | `MAIN`/`COMBAT` | Legend : FLIP gratuit ; Unit : paie → Field (+ mal d'invocation sauf `GO_SOLO`) ; Program : paie → `ON_PLAY` → défausse ; Gear : paie → attaché à une Unit alliée |
| `AttackCommand` | actif | `MAIN`/`COMBAT` (auto `MAIN→COMBAT`) | épuise, ouvre la fenêtre, `ON_ATTACK`, puis vol de Gig (sans cible) ou comparaison des puissances (égalité = les deux vaincues) |
| `SellCardCommand` | actif | `MAIN` | 1 carte de la main → Eddies Area face cachée, +1 Eddie |
| `EndTurnCommand` | actif | toute | `ON_TURN_END`, fermeture fenêtre, passage du tour, victoire à 7 Gigs, pioche 1, lancer de Gig, `MAIN` |

Détails :

- **Paiement** : `max(0, coût − remise)`, Eddies insuffisants → refus.
  Les Legends ne paient jamais (FLIP gratuit, quel que soit le coût imprimé).
- **Seuil Street Cred** : exigé à la pose si la carte en imprime un (non consommé).
- **Gear** : hôte obligatoire (Unit alliée du Field) ; sa puissance compte dans
  le total de l'hôte ; il suit l'hôte dans la défausse. Pas de déséquipement en V1.
- **Program** : résolu depuis la main puis défaussé (même sans effet).
- **Combat** : totaux = unit + gears. Si la cible a quitté le Field pendant
  `ON_ATTACK`, l'attaque est sans effet (pas de dégâts « dans le vide »).
- **BLOCKER** : un BLOCKER rival prêt force le ciblage (toute attaque vers une
  autre Unit est refusée) et interdit le vol direct de Gig.
- **Fenêtre de réaction** : ouverte à chaque attaque pour le défenseur ; le
  défenseur n'y joue que des cartes `QUICK` (hors tour, coûts payés normalement) ;
  le joueur actif n'est pas restreint ; fermeture sur `EndTurnCommand`.
- **Fin de tour** : victoire immédiate si l'entrant a ≥ 7 Gigs (**avant** pioche
  et lancer) ; sinon pioche 1 (deck vide → défaite), lancer d'un dé Gig
  (plus de dé → on saute), `MAIN`.

## 5. Effets et déclencheurs

### 5.1 Tables V1

| Effet | Sémantique |
|---|---|
| `DAMAGE n` | +n dégâts à la cible (létal → défausse + `ON_DEATH`) ; sans effet sur carte sans puissance |
| `HEAL n` | −n dégâts (plancher 0, jamais au-delà de base + buffs) |
| `DRAW n` | le bénéficiaire pioche n (deck vide → défaite immédiate) |
| `GRANT_POWER n` | +n bonus de puissance (conservé jusqu'à la défausse) |
| `STEAL_GIG n` | vole n Gigs (dé max à chaque fois) ; **ne fait pas gagner immédiatement** (victoire au début du tour) |
| `REDUCE_COST n` | +n remise Eddies jusqu'au début du prochain tour du bénéficiaire |
| `DEFEAT_UNIT n` | vainc la plus puissante Unit rivale éligible (`n` = plafond de puissance, `0` = aucun) ; cible `TARGET_UNIT` = cible désignée |
| `BOOST_GIG n` | augmente de `n` le Gig de plus faible valeur du bénéficiaire (« Increase a Gig by up to N ») |
| `REDUCE_GIG n` | diminue de `n` le Gig de plus forte valeur du bénéficiaire, sans descendre sous 1 |

| Déclencheur | Moment |
|---|---|
| `ON_PLAY` | carte jouée (toujours résolu, y compris pour les réactions QUICK) |
| `ON_ATTACK` | attaque déclarée (avant la comparaison) |
| `ON_DEATH` | unit vaincue (après mise en défausse) |
| `ON_TURN_END` | fin de tour du contrôleur |
| `FLIP` | Legend retournée |
| `QUICK` | carte `QUICK` jouée **en réaction** (jamais hors fenêtre) |

| Cible | Sens |
|---|---|
| `SELF` | la source (toujours valide, toute zone) |
| `TARGET_UNIT` | la cible de la commande (doit être sur le Field, sinon sans effet) |
| `EACH_RIVAL_UNIT` | toutes les Units rivales du Field |
| `SELF_PLAYER` / `RIVAL_PLAYER` | le contrôleur / son rival (effets joueur) |
| `FRIENDLY_UNIT` | Unit alliée la plus puissante du Field (choix déterministe) |
| `RIVAL_UNIT` | Unit rivale la plus puissante éligible (choix déterministe) |

### 5.2 Mini-langage des capacités

Format : `TRIGGER:EFFET:VALEUR[:CIBLE]`. Exemples :

```text
ON_PLAY:DRAW:2
FLIP:GRANT_POWER:2:SELF
ON_ATTACK:DAMAGE:3:TARGET_UNIT
ON_DEATH:DRAW:1
ON_TURN_END:HEAL:1:SELF
QUICK:DRAW:1
ON_PLAY:STEAL_GIG:1
ON_PLAY:REDUCE_COST:2
ON_PLAY:DAMAGE:2:EACH_RIVAL_UNIT
```

Cibles par défaut (capacité sans 4ᵉ champ) : `DAMAGE → TARGET_UNIT`,
`HEAL → SELF`, `DRAW → SELF_PLAYER`, `GRANT_POWER → SELF`,
`STEAL_GIG → RIVAL_PLAYER`, `REDUCE_COST → SELF_PLAYER`,
`DEFEAT_UNIT → RIVAL_UNIT`, `BOOST_GIG → SELF_PLAYER`, `REDUCE_GIG → RIVAL_PLAYER`.

**Textes du catalogue (feature 6.5).** `EffectParser` analyse aussi les textes
naturels de `data/cards.json`, dans cet ordre :

1. mini-langage structuré (`TRIGGER:EFFET:VALEUR[:CIBLE]`) — prioritaire ;
2. découpage du texte en **segments** par les marqueurs officiels
   (`{Play}`, `{Attack}`, `{Flip}`, `{Quick}`, `{Defeated}`), avec gestion du
   préambule (« At the end of your turn, … » → `ON_TURN_END`,
   « When you play this, … » → `ON_PLAY`) ;
3. reconnaissance, segment par segment, de motifs **non ambigus** par
   expressions régulières ancrées (`(?=\s*(\.|,|$|then|if))`) :
   `draw N`, `defeat a rival Unit [with power N or less]`,
   `give a friendly Unit +N power [this turn]`,
   `increase a Gig by up to N`, `decrease a rival Gig by up to N` ;
4. repli historique « draw N » → `ON_PLAY:DRAW:N` (compatibilité).

Sont **volontairement ignorés** (jamais d'échec de partie) :

- segments dont le marqueur est un rappel de mot-clé (`{Spend}`, `{Call}`,
  `{Go Solo}`, `{Blocker}`) ;
- capacités conditionnelles ou modales : `CONDITIONAL_HINTS` = « you may »,
  « if you do », « if you have », « if a/if it/if your », « whenever »,
  « each time », « the first time », « at the start of », « choose one effect »,
  et le séparateur modal `//` ;
- préambules suspendus à un événement (« When a friendly Unit steals… »,
  « If … ») ;
- segments commençant par une parenthèse (rappel de règle imprimé) ;
- motifs non reconnus, par exemple les effets globaux (« Defeat all other
  Units »), faute de ciblage fin en V1.

Une capacité ignorée est donc une **limite assumée** (§11) : la ligne `EFFECT`
correspondante n'apparaît pas au journal de diagnostic.

### 5.3 Mots-clés (rôle en V1)

| Mot-clé | Rôle |
|---|---|
| `quick` | seul timing de réaction (commande + déclencheur `QUICK`) |
| `blocker` | interception obligatoire (si prêt) |
| `go_solo` | attaque le tour de pose (Units ; Legends-jouées-en-Units = V2) |
| `flip` | informatif (le déclencheur `FLIP` part au retournement d'une Legend) |
| `play` / `attack` | informatifs (le déclencheur vient du mini-langage) |

### 5.4 Cascades

`resolveEffects` borne la profondeur (`MAX_TRIGGER_DEPTH = 32`, garde-fou).
`defeatUnit` est idempotente (une carte hors Field n'est pas re-vaincue) :
pas de double `ON_DEATH`.

## 6. Règles imposées → code (traçabilité)

| Règle imposée | Implémentation |
|---|---|
| Victoire : 7 Gigs au début du tour | `GameConstants.GIGS_TO_WIN`, `EndTurnCommand` (avant pioche/lancer), journal `VICTORY_CHECK` |
| Défaite : pioche impossible (deck vide) | `GameState.drawCards` + journal `DRAW`/`VICTORY` |
| Premier joueur tiré au sort + malus | `GameService.createGame` (`setupRandom`), `FIRST_PLAYER_SPENT_LEGENDS` |
| Phases Draw → Main → Combat → End | `Phase`, transitions auto (`EndTurnCommand`, `AttackCommand`) |
| Legend Call : 1 Eddie, once per turn, effet CALL/FLIP (R4) | `PlayCardCommand` branche Legend (coût 1, `hasCalledLegendThisTurn`, triggers `FLIP`+`CALL`) |
| Unit : power = dégâts | comparaison des puissances (`AttackCommand`), `DAMAGE` létal |
| Unit posée : pas d'attaque sauf `GO_SOLO` | `summoningSickness` (`PlayCardCommand`), levée au tour suivant, exemption `hasGoSolo()` |
| Program : effet Play puis défausse | `PlayCardCommand` (branche Program) |
| Gear : attaché à une Unit | `PlayCardCommand` (hôte obligatoire), `totalPowerFor`, suivi en défausse |
| RAM = deckbuilding uniquement (R7) — aucune vérif en jeu | `GameConstants.RAM_CEILING_ENFORCED=false`, `PlayCardCommand` sans vérif RAM |
| Eddies : cycle 0→tap→pay→lost (R2) ; Legends et Eddies cards +1 par tap, redress au START, reset à 0 | `Player.spendLegendForEddies`, `Player.spendEddiesCardForEddies`, `SpendLegendCommand`, `SpendEddiesCommand`, `Player.startTurn` (eddies=0 & readyAll) |
| Eddies / Street Cred | réserve dépensée / seuil non consommé (`Player`, `PlayCardCommand`) |
| Vente 1 carte/tour | `SALES_PER_TURN`, `SellCardCommand` + `hasSoldThisTurn` |
| Réactions QUICK uniquement | `ReactionWindow`, `PlayCardCommand` (défenseur), fermeture en fin de tour |
| BLOCKER intercepte | `AttackCommand.validate` + `Player.controlsReadyBlocker` |
| Journal de diagnostic (toutes les actions) | `GameLog`, `GameState.log*`, `GameService.executeCommand`, `/topic/game/{id}/log`, `DebugController` |

## 7. Vues masquées

`GameService.getGameState(gameId, viewerId)` retourne `maskedCopyFor(viewerId)` :

| Information | Propriétaire | Rival | Observateur |
|---|---|---|---|
| main | visible | masquée | masquée |
| deck (ordre/contenu) | **masqué** (taille publique) | masqué | masqué |
| Legends face cachée | visibles | masquées | masquées |
| Legends révélées, Field, défausses, Gigs, dés, Eddies | visibles | visibles | visibles |
| cartes vendues | visibles | masquées | masquées |
| journal | complet (sans secret) | complet | complet |

Ne jamais exposer `getGameStateInternal` aux clients (serveur/tests uniquement).

## 8. Service

- `createGame(p1, p2, ids1, ids2)` : charge les définitions (404 si inconnue),
  les Legends vont en Legends Area (face cachée), le reste forme le deck mélangé
  (graine aléatoire stockée), distribution de 6 cartes, p1 commence en `MAIN`.
  Aucun contrôle de légalité de deck en V1 (40-50 cartes, 3 Legends, RAM :
  feature deck-builder).
- `executeCommand(gameId, command)` : 404 si partie inconnue, `validate` puis
  `execute`, retourne les événements. La victoire est détectée pendant l'exécution
  (`EndTurnCommand`, pioche sur deck vide) ; toute commande ultérieure est refusée.
- Registre `ConcurrentHashMap` en mémoire (une instance suffit en V1).

## 9. Ajouter un nouvel effet

Exemple : ajouter `SHIELD` (prévient les n prochains dégâts — fictif, à adapter).

1. **Déclarer le type** dans `engine/EffectType.java` :
   `SHIELD;` (+ javadoc).
2. **Écrire le handler** dans `engine/RuleEngine.java` (classe interne privée
   implémentant `EffectHandler`) et l'enregistrer dans le constructeur :
   `registered.put(EffectType.SHIELD, new ShieldHandler());`.
   Le handler mute `state`, journalise via `state.appendEvent(...)`, et ignore
   proprement les cibles invalides (événement `EFFECT_RESOLVED` explicite).
3. **Cible par défaut** : ajouter le cas dans `EffectParser.defaultTarget(...)`
   (sinon `SELF`).
4. **Documenter** : table §5.1 + exemple §5.2 de ce fichier.
5. **Tester** : cas nominal + cas limite dans `engine/RuleEngineTest.java`
   (pur JUnit, sans Spring). Lancer `cd backend && mvn test`.

Ajouter un **déclencheur** : valeur dans `TriggerType`, appel(s) à
`resolveEffects(...)` au bon moment dans la commande concernée, ligne §5.1, tests.
Ajouter une **commande** : classe implémentant `GameCommand` (`validate` sans
mutation, `execute` qui journalise et retourne ses événements), ligne §4, tests
dans `engine/GameCommandTest.java`.

## 10. Tests

```bash
cd backend && mvn clean test   # profil H2 (aucun Docker requis)
```

- `engine/RuleEngineTest` : déclencheurs, 6 effets, mini-langage, heuristiques.
- `engine/GameCommandTest` : pose (Unit/Program/Gear/Legend), coûts, seuils,
  combat (victoire/égalité), BLOCKER, vol, QUICK, vente unique, victoire à 7,
  pioche/lancer, deck-out, `GO_SOLO`.
- `service/GameServiceTest` (Mockito, sans Spring) : création, premier joueur
  tiré au sort + malus, exécution, refus consignés au journal, masquage, 404.
- `ws/LobbyGameFlowWebSocketIntegrationTest` : partie STOMP de bout en bout
  (états masqués, actions, erreurs privées, abandon) avec premier joueur aléatoire.
- `docs/DEBUG-GUIDE.md` : mode d'emploi du journal et des endpoints de debug.
- `engine/GameIntegrationTest` (feature 6.5.2, TDD R1→R13) : les 51 scénarios `testR{N}_*` —
  R1(4) Setup, R2(3) Eddies cycle 0→+1→lost, R3(3) Legends ressources, R4(3) Legends jouables (Call 1 Eddie), R5(3) vente, R6(3) Eddies cards, R7(2) RAM deck-only,
  R8(3) phases DRAW/MAIN/COMBAT/END, R9(4) combat, R10(3) mal d'invoc., R11(6) keywords, R12(4) Gigs 7-victoire, R13(10) effets (voir `docs/INTEGRATION-TEST.md`).
- `engine/GameFixtures` : cartes synthétiques au mini-langage + duels frais
  (`coloredCard`/`coloredUnit`/`coloredProgram`/`coloredLegend` pour la couleur
  et la RAM).
- Les tests REST/JPA existants comparent au contenu réel du catalogue embarqué
  (robustes à l'ajout de cartes).

## 11. Limites V1 assumées (suites)

- Pas de mulligan (le premier joueur est tiré au sort, le malus de mise en place
  s'applique).
- Capacités activées (`{Spend}`), modales et conditionnelles des textes du
  catalogue non résolues (voir §5.2) ; cohérences de ciblage fin (choix du joueur)
  non implémentées : les effets génériques choisissent la cible de façon
  déterministe.
- L'inclinaison d'une Legend / Eddies card est **redressée** au début du tour suivant (`Player.startTurn` → `readyAll` Field+Legends+Eddies, `eddies=0`, `hasCalled/hasSold/costDiscount` reset, lag cleared) — économie R2/R3/R6.
- RAM en partie désactivée (`GameConstants.RAM_CEILING_ENFORCED=false`) — seuls Eddies comptent ; contrôles RAM réservés au deckbuilder (R7).
- Legends non jouables comme Units (`GO_SOLO` des Legends), pas de coût activé.
- Pas de déséquipement / destruction ciblée de Gear (seulement suivi en défausse).
- Pas de ciblage fin au-delà de `TARGET_UNIT` (pas de « Unit engagée », etc.).
- Les textes naturels sont interprétés pour un sous-ensemble non ambigu de
  motifs ; les nouvelles cartes peuvent embarquer le mini-langage pour un
  comportement exact et testable.
- Fenêtre de réaction simplifiée : synchrone, ouverte à chaque attaque, fermée
  en fin de tour (pas de passe explicite ni de pile proposée/répondue).
- Pas de persistance des parties ni de diffusion STOMP (feature 04) ; pas de
  contrôle de deck (feature deck-builder).
