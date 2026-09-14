# Moteur de règles (Rule Engine)

Backend Java uniquement. Serveur autoritaire : toute mutation de l'état d'une partie
passe par une commande validée côté serveur. Aucune règle ne vit dans le frontend,
les contrôleurs ou les services (le service orchestre, le moteur décide).

Documents liés : `game-rules.md` (règles), `architecture.md` (§2-3, couches),
`DATA-MODEL.md` (contrat des cartes), `websocket-protocol.md` (transport, feature 04).

## 1. Carte du code

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
- Ressources : `eddies` (réserve persistante : vente +1, jeu = dépense),
  `costDiscount` (remise `REDUCE_COST`, réinitialisée au début du tour),
  `hasSoldThisTurn` (1 vente/tour).
- `startTurn()` : vente/remise réinitialisées, redressement, fin des mals d'invocation.
- `readyBlockers()` / `controlsReadyBlocker()` : BLOCKERs prêts (non épuisés).

### 3.3 CardInstance

- Identité : `instanceId` (UUID frais), `cardId` (définition), `ownerId`, `zone`.
- Snapshot imprimé : `type`, `color`, `baseCost`, `basePower`,
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
`STEAL_GIG → RIVAL_PLAYER`, `REDUCE_COST → SELF_PLAYER`.

Les capacités au format libre du catalogue actuel sont **ignorées**, sauf
l'heuristique documentée « draw N » → `ON_PLAY:DRAW:N` (compatibilité).
Une capacité invalide ne fait jamais échouer une partie (ignorée silencieusement).

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
| Victoire : 7 Gigs au début du tour | `GameConstants.GIGS_TO_WIN`, `EndTurnCommand` (avant pioche/lancer) |
| Phases Draw → Main → Combat → End | `Phase`, transitions auto (`EndTurnCommand`, `AttackCommand`) |
| Legend : pas de coût, effet FLIP | `PlayCardCommand` (branche Legend, déclencheur `FLIP`) |
| Unit : power = dégâts | comparaison des puissances (`AttackCommand`), `DAMAGE` létal |
| Program : effet Play puis défausse | `PlayCardCommand` (branche Program) |
| Gear : attaché à une Unit | `PlayCardCommand` (hôte obligatoire), `totalPowerFor`, suivi en défausse |
| RAM red/green/blue/yellow | définitions (`CardColor`) ; plafond de deck = feature deck-builder (pas de contrôle en partie) |
| Eddies / Street Cred | réserve dépensée / seuil non consommé (`Player`, `PlayCardCommand`) |
| Vente 1 carte/tour | `SALES_PER_TURN`, `SellCardCommand` + `hasSoldThisTurn` |
| Réactions QUICK uniquement | `ReactionWindow`, `PlayCardCommand` (défenseur) |
| BLOCKER intercepte | `AttackCommand.validate` + `Player.controlsReadyBlocker` |

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
- `service/GameServiceTest` (Mockito, sans Spring) : création, exécution,
  masquage, 404.
- `engine/GameFixtures` : cartes synthétiques au mini-langage + duels frais.
- Les tests REST/JPA existants comparent au contenu réel du catalogue embarqué
  (robustes à l'ajout de cartes).

## 11. Limites V1 assumées (suites)

- Pas de mulligan, pas de choix du premier joueur (p1 commence).
- Legends non jouables comme Units (`GO_SOLO` des Legends), pas de coût activé.
- Pas de déséquipement / destruction ciblée de Gear (seulement suivi en défausse).
- Pas de ciblage fin au-delà de `TARGET_UNIT` (pas de « Unit engagée », etc.).
- Textes naturels du catalogue non interprétés (sauf « draw N ») : les futures
  cartes devront embarquer le mini-langage (ou un format structuré équivalent).
- Fenêtre de réaction simplifiée : synchrone, ouverte à chaque attaque, fermée
  en fin de tour (pas de passe explicite ni de pile proposée/répondue).
- Pas de persistance des parties ni de diffusion STOMP (feature 04) ; pas de
  contrôle de deck (feature deck-builder).
