# Moteur de règles (Rule Engine)

Backend Java uniquement. Serveur autoritaire : toute mutation de l'état d'une partie
passe par une commande validée côté serveur. Aucune règle ne vit dans le frontend,
les contrôleurs ou les services (le service orchestre, le moteur décide).

Documents liés : `game-rules.md` (règles), `architecture.md` (§2-3, couches),
`DATA-MODEL.md` (contrat des cartes), `WEBSOCKET-PROTOCOL.md` (transport, feature 04).

## 1. Carte du code

> Mini-Feature 6 (2026-09-16) : **Combat, Vol de Dés & Plafond des Dés Actifs** —
> l'attaque devient une machine à états interactive (`CombatStep`, `PendingAttack`,
> `CombatResolver`) : déclaration (l'attaquant s'incline) → fenêtre « Utiliser
> Blocker ? » **au choix du défenseur** (`BlockCommand` / `DeclineBlockCommand`,
> blocage multiple : seul le **dernier** Blocker encaisse) → combat
> (`RuleEngine.fight`) ou vol de dés (`StealGigCommand`) avec quota
> `N = (power / 10) + 1` (0 si power ≤ 0) **plafonné strictement** aux dés Gigs
> actifs du défenseur (`M = min(N, dés actifs)`). Chaque dé actif porte un
> identifiant stable (`GigDie`, `Player.gigDieIds`) : l'attaquant choisit
> **quels** dés il vole, type et valeur conservés. Détails §3.5 et §4.
>
> Mini-Feature 4 (2026-09-15) : **Générer des Eddies (R4)** — commande unifiée
> `SpendResourceCommand` (phase `MAIN`) : incline une Legend non inclinée
> (Legends Area) **ou** une carte vendue non inclinée (Eddies Area) du joueur
> actif → `exhausted=true`, `availableEddies += 1`. `SpendLegendCommand` /
> `SpendEddiesCommand` deviennent des alias légers (mêmes règles, `actionType`
> de journal conservé) ; action filaire `SPEND_RESOURCE` ajoutée au protocole.
>
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
│   ├── DrawStep.java     # sous-étapes de la phase DRAW interactive (Mini-Feature 5)
│   ├── CombatStep.java   # sous-étapes d'une attaque (Mini-Feature 6)
│   ├── PendingAttack.java # attaque en cours : attaquants, cible, quota N, plafond M
│   ├── Turn.java         # numéro, joueur actif, phase, drawStep
│   ├── ReactionWindow.java
│   ├── DieRoll.java      # résultat d'un lancer de dé Gig
│   ├── GigDie.java       # dé Gig actif : id stable + type + valeur (vol choisi)
│   ├── GameEvent.java / GameEventType.java  # journal (sans secret, base du rejeu R10)
├── engine/               # règles (pur Java, testé sans Spring)
│   ├── GameConstants.java   # 7 Gigs, 1 vente/tour, main de 6 (imposés, non configurables)
│   ├── GameRuleException.java
│   ├── GameEffect.java / EffectType.java / TriggerType.java / EffectTarget.java
│   ├── EffectParser.java    # interprète les abilities JSON (mini-langage + heuristiques)
│   ├── EffectHandler.java   # stratégie d'application d'un type d'effet
│   ├── RuleEngine.java      # resolveEffect(s), defeatUnit, fight, quota/plafond de vol
│   ├── DrawPhaseHandler.java # machine à états de la phase DRAW interactive (Mini-Feature 5)
│   ├── CombatResolver.java  # machine à états du combat (Mini-Feature 6)
│   └── command/
│       ├── GameCommand.java      # interface : validate + execute (+ gardes partagés)
│       ├── PlayCardCommand.java
│       ├── AttackCommand.java    # déclare l'attaque, ouvre la résolution
│       ├── BlockCommand.java     # USE_BLOCKER : interception au choix du défenseur
│       ├── DeclineBlockCommand.java # DECLINE_BLOCK : renoncement explicite
│       ├── StealGigCommand.java  # STEAL_GIG : les M dés choisis par l'attaquant
│       ├── SellCardCommand.java
│       └── EndTurnCommand.java   # résout automatiquement un combat en suspens
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
  `drawCards` (deck vide → défaite immédiate), `stealGig(from, to)` (dé de plus
  forte valeur — effets de cartes), `stealGig(from, to, dieId)` (**dé choisi** par
  l'attaquant, Mini-Feature 6 : identifiant, type et valeur transférés tels quels),
  `rollFixerDie(playerId, die)` (dé **choisi** par le joueur,
  Mini-Feature 5 ; la variante sans dé lance le plus petit dé sélectionnable),
  `totalPowerFor` (unit + gears attachés), `findInstance` / `findInstanceOwner`.
- `getDrawStep()` / `setDrawStep()` : sous-étape de la phase `DRAW` (`null` hors
  `DRAW`, effacée automatiquement par `setPhase`).
- `getPendingAttack()` / `setPendingAttack()` / `clearPendingAttack()` : attaque en
  cours de résolution (Mini-Feature 6, §3.5) ; gardes `isCombatPending()`,
  `isAwaitingBlock()`, `isAwaitingStealChoice()`. Recopiée par `maskedCopyFor`
  (aucun secret : identifiants, étape, quota `N` et plafond `M`).
- `maskedCopyFor(viewerId)` : copie détachée aux secrets effacés (§7).

### 3.2 Player

- Zones cartes : `deck` (index 0 = dessus), `hand`, `field`, `trash`,
  `eddiesArea`, `legendsArea` (+ `moveToZone`, `findIn`, `findAnywhere`).
- Dés : `fixerDice` (`d4…d20`, dés pas encore lancés), `gigs` (valeurs) et
  `gigDice` (type du dé de chaque Gig, aligné sur `gigs` ; `"?"` pour un Gig
  injecté hors lancer). Choix du dé (Mini-Feature 5) : `selectableFixerDice()`
  = tous les dés restants **sauf le `d20`**, ou `[d20]` quand il est le dernier ;
  `canSelectFixerDie(die)`, `removeFixerDie(die)`, `addRolledGig(die, value)`,
  `removeGig(index)`, `normalizeDie("D8") → "d8"`.
- **Dés Gigs actifs (Mini-Feature 6)** : `gigDieIds` (identifiant stable de chaque
  dé, aligné sur `gigs`/`gigDice`, généré au lancer et **conservé** en cas de vol),
  `activeGigs()` → `List<GigDie>` (`id`, `die`, `value`), `getActiveGigCount()`,
  `findActiveGig(dieId)`, `removeGigById(dieId)`, `addGigDie(GigDie)`. Seuls ces dés
  *déjà lancés* sont volables : la Fixer Area (`fixerDice`) ne l'est **jamais**
  (plafond strict). Un Gig injecté hors lancer reçoit `UNKNOWN_DIE` (`"?"`) et un
  identifiant frais (`syncGigDice()` réaligne les trois listes).
- Dérivés : `getGigCount()`, `getStreetCred()` (somme, jamais stocké).
- Ressources : `eddies` (réserve : inclinaison d'une Legend ou d'une carte de
  l'Eddies Area = +1, jeu = dépense ; **la vente ne crédite rien**, elle crée la
  ressource), `costDiscount` (remise `REDUCE_COST`, réinitialisée au début
  du tour), `hasSoldThisTurn` (1 vente/tour).
- **Économie des Eddies (R2/R3/R6)** : chaque carte face-down en Eddies Area vaut 1 Eddie par tour, et chaque Legend (face-up ou face-down) vaut aussi 1 Eddie par tour (Guide § EDDIES & LEGENDS). `spendLegendForEddies` et `spendEddiesCardForEddies` inclinent la carte (exhausted) pour +1 Eddie (compteur remis à 0 au début de chaque tour — `Player.startTurn` fait `eddies=0` et `readyAll` redresse Field+Legends+Eddies). `legendsAvailableForEddies()`, `eddiesAvailableForEddies()` et `countSpentLegends()` exposent la réserve. Le premier joueur démarre avec `FIRST_PLAYER_SPENT_LEGENDS=2` Legends déjà inclinées (ne se redressent qu'à son 2e tour).
- **Vente = création de ressource (Mini-Feature 3)** : `SellCardCommand` **ne crédite aucun Eddie**. La carte est révélée au rival (`CARD_REVEALED`), retirée de la main, puis posée en Eddies Area `faceDown = true` et `exhausted = false` (prête). Elle vaut dès lors 1 €$ **par tour** via `SpendEddiesCommand` — y compris le tour même de la vente. Seule limite : `SALES_PER_TURN = 1` vente par tour, en phase `MAIN`.
- **Restriction de vente par Type (Mini-Feature 10C)** : les cartes de type `UNIT` et `LEGEND` ne peuvent **pas** être vendues (`SellCardCommand.isSellableType`) — refus « Les Unités et les Légendes ne peuvent pas être vendues » (`GameRuleException` → `ILLEGAL_ACTION`), sans consommer le quota du tour. Tous les autres types (`PROGRAM`, `GEAR`…) restent vendables, dans la limite d'1 vente par tour.
- **RAM (R7)** : `ramCeilingFor` existe pour le deckbuilder, mais `GameConstants.RAM_CEILING_ENFORCED=false` — **aucune vérification en jeu** (`PlayCardCommand` ne vérifie plus la RAM). La RAM n'est donc qu'une limite de construction de deck (Guide § DECK BUILDING).
- `startTurn()` : **Eddies remis à 0**, vente (`hasSoldThisTurn`) et Call (`hasCalledLegendThisTurn`) réinitialisés, remise remise à 0, redressement de **toutes** les cartes dépensées (Field+Legends+EddiesArea), fin des mals d'invocation (Lag).
- `readyBlockers()` / `controlsReadyBlocker()` : BLOCKERs prêts (non épuisés) —
  ils ouvrent la fenêtre « Utiliser Blocker ? » (§3.5), sans jamais forcer le
  ciblage de l'attaquant.

### 3.3 CardInstance

- Identité : `instanceId` (UUID frais), `cardId` (définition), `ownerId`, `zone`.
- Snapshot imprimé : `type`, `color`, `ram`, `baseCost`, `basePower`,
  `streetCredThreshold`, `keywords`, `abilities` (recopiés à la création).
- Mutable : `powerBonus` / `damage` (buffs), `exhausted`, `faceDown`,
  `summoningSickness`, `attachedTo` / `attachments` (Gears).
- `getEffectivePower()` = `max(0, base + bonus − dégâts)` (`null` si pas de
  puissance imprimée) ; `getEffectivePowerOrZero()` (variante `int`) ;
  `isLethalDamage()` quand `dégâts ≥ base + bonus`.
- `isBlocker()`, `hasGoSolo()`, `hasAdrenaline()`, `hasHaste()` et
  `canIgnoreSummoningSickness()` (= `GO_SOLO` **ou** `ADRENALINE` **ou** `HASTE`,
  alias « jeu rapide » ajouté par la Mini-Feature 6).
- `copy()` / `masked()` (secrets effacés, identité conservée).

### 3.4 Phases et tours

`DRAW → MAIN → COMBAT → END`. `END` est résolue automatiquement par
`EndTurnCommand` (`ON_TURN_END` à la fermeture), `MAIN → COMBAT` par la première
attaque. Le tour 1 commence en `MAIN` (main de départ distribuée, sans pioche ni
lancer).

Depuis la **Mini-Feature 5**, la phase `DRAW` est **interactive** : le serveur
pilote une machine à états (`DrawStep`, `DrawPhaseHandler`) et **attend une
commande du joueur actif** à deux reprises. Il est impossible de passer la phase
`DRAW` sans cliquer sur sa pioche **et** choisir un dé (`EndTurnCommand` est
refusée pendant toute la phase `DRAW`).

```text
EndTurnCommand ──▶ DRAW_START ──▶ AWAITING_DRAW ──(DRAW_CARD)──▶ AWAITING_DIE_SELECT
                                                                        │
                           MAIN ◀── DRAW_COMPLETE ◀── ROLLING_DIE ◀──(SELECT_DIE)
```

Ordre exact du début de tour (tours ≥ 2), conforme à `docs/OFFICIAL-RULES.md`
§ START PHASE (*ready spent cards → draw 1 → gain a Gig*) :

1. `DRAW_START` (`EndTurnCommand` → `DrawPhaseHandler.beginDrawPhase`) : passage
   du tour, `Phase.DRAW`, journal `PHASE` ;
2. `VICTORY_CHECK` : le joueur actif qui **commence** son tour avec au moins
   `GIGS_TO_WIN` (7) Gigs gagne immédiatement (journal `VICTORY`), avant toute
   pioche ;
3. **READY** (`DrawPhaseHandler.readyAndAwaitDraw`) : redressement de toutes les
   cartes (Field, Legends, Eddies), fin des mals d'invocation, `availableEddies = 0`
   (`Player.startTurn()`, journal `TURN_RESET`) ; puis **`AWAITING_DRAW`**
   (journal `DRAW_STEP`) — la partie attend le clic sur la pioche ;
4. **`DRAW_CARD`** (`DrawCardCommand`, refusée hors `AWAITING_DRAW`) : pioche
   d'une carte ; **deck vide = défaite immédiate** (`GameState.drawCards` désigne
   le vainqueur, le journal consigne `DRAW` en `FAILED` + `VICTORY`) ; sinon
   **`AWAITING_DIE_SELECT`** (Fixer Area vide → directement `MAIN`) ;
5. **`SELECT_DIE`** (`SelectDieCommand`, refusée hors `AWAITING_DIE_SELECT`) :
   le dé demandé (`dice[0]`, normalisé) doit être **dans la Fixer Area** et
   respecter la règle officielle « *any die except the d20, which is always rolled
   last* » (`Player.canSelectFixerDie`) ; `ROLLING_DIE` : tirage serveur
   `1..faces`, résultat rangé dans `gigs` + `gigDice`, journal `GIG_ROLL` ;
6. `DRAW_COMPLETE` → `Phase.MAIN` (automatique, `drawStep = null`).

Les étapes `DRAW_START`, `ROLLING_DIE` et `DRAW_COMPLETE` sont transitoires (résolues
dans la même commande) ; seules `AWAITING_DRAW` et `AWAITING_DIE_SELECT` sont des
états d'attente exposés au client (`turn.drawStep`).

Le premier joueur est **tiré au sort** à la création (`GameService` +
`Random` injectable pour les tests) ; il subit le malus de mise en place (2
Legends déjà inclinées). Il n'y a pas de mulligan (limite assumée, §11).

### 3.5 Combat interactif (Mini-Feature 6)

Règle officielle § ATTACKING : « *Each Unit attacks individually, and completes all
the attacking steps before another Unit can attack.* » Une attaque n'est donc plus
résolue d'un seul bloc : elle traverse des étapes portées par `PendingAttack`
(`GameState.pendingAttack`, publié dans `GameStateDTO.pendingAttack`).

```text
AttackCommand (déclare, incline l'attaquant, MAIN→COMBAT, ON_ATTACK)
      │
      ├─ défenseur avec {Blocker} prêt ──▶ AWAITING_BLOCK ──┬─ USE_BLOCKER  ──▶ FIGHT! (dernier Blocker)
      │                                                     └─ DECLINE_BLOCK ─┐
      └─ aucun Blocker prêt ─────────────────────────────────────────────────┤
                                                                              ▼
                              cible déclarée (Unit rivale dépensée) ──▶ FIGHT! (RuleEngine.fight)
                              attaque directe (Gig Area) ──▶ quota N = (power / 10) + 1  (0 si power ≤ 0)
                                                              plafond M = min(N, dés Gigs ACTIFS du défenseur)
                                                                   ├─ M = 0 ──▶ attaque réussie, 0 dé volé
                                                                   └─ M ≥ 1 ──▶ AWAITING_STEAL_CHOICE ──(STEAL_GIG : M identifiants)──▶ dés transférés
```

- **Cibles légales** (`AttackCommand.validate`) : une Unit rivale **dépensée**
  (« *Ready Units can't be attacked* ») ou la Gig Area adverse (attaque directe).
  Un `{Blocker}` prêt n'est donc jamais une cible : il intercepte via la fenêtre.
  Une attaque déjà en cours bloque toute nouvelle déclaration (`isCombatPending()`).
- **Blocage au choix du défenseur** (`BlockCommand`, action `USE_BLOCKER`) : le
  défenseur peut dépenser **un ou plusieurs** `{Blocker}` prêts (`cardIds`, ordre
  significatif). Chaque Blocker est incliné, l'événement `ATTACK_BLOCKED` est
  publié, ses compétences sont résolues (`ON_BLOCK` puis `ON_ATTACK`, cible =
  l'attaquant) et **seul le dernier Blocker déclaré** encaisse les dégâts du
  combat. Une attaque redirigée ne vole **jamais** de Gig. `DeclineBlockCommand`
  (`DECLINE_BLOCK`) laisse l'attaque suivre son cours.
- **Quota et plafond strict** (`RuleEngine.calculateQuota` /
  `calculateActualStealable`, `GameConstants.POWER_PER_EXTRA_GIG = 10`) :
  `N = power ≤ 0 ? 0 : (power / 10) + 1` (1-9 → 1, 10-19 → 2, 20-29 → 3, …) puis
  `M = min(N, défenseur.getActiveGigCount())`. On ne crée jamais de dé et on ne
  ponctionne jamais la Fixer Area. `M = 0` n'ouvre **aucun** choix : l'attaque est
  réussie (Unit inclinée, phase `COMBAT`) et le journal consigne `GIG_STOLEN` en
  `FAILED` avec le détail du plafond.
- **Choix de l'attaquant** (`StealGigCommand`, action `STEAL_GIG`) : exactement `M`
  identifiants (`dice`, repli `cardIds` / `chosen`), tous distincts et tous actifs
  chez le défenseur — sinon refus sans mutation. `M` est **recalculé** à la
  validation (une réaction `QUICK` peut avoir changé la puissance ou les dés).
  Chaque dé transféré conserve son identifiant, son type et sa valeur
  (« un d8 affichant 5 reste un d8 affichant 5 »), événement `GIG_STOLEN` par dé.
- **Fin de tour** (`EndTurnCommand` → `CombatResolver.autoResolve`) : un combat en
  suspens est résolu automatiquement pour qu'un joueur silencieux ne gèle pas la
  partie — blocage refusé implicitement, puis vol des `M` dés de plus haute valeur
  (tri stable, journal `GIG_STEAL_AUTO`).
- **Victoire** : un vol ne fait jamais gagner immédiatement ; les ≥ 7 dés sont
  vérifiés au tout début de la phase `DRAW` du joueur entrant (§3.4, étape 2).

Journal de diagnostic (`actionType`) : `ATTACK`, `BLOCKER_PROMPT`, `USE_BLOCKER`,
`DECLINE_BLOCK`, `GIG_STEAL_CHOICE`, `GIG_STOLEN`, `GIG_STEAL_AUTO`, `FIGHT`.

## 4. Commandes

| Commande | Ordonnateur | Phase | Effet |
|---|---|---|---|
| `PlayCardCommand` | actif (ou défenseur QUICK en réaction) | `MAIN`/`COMBAT` | Legend : FLIP gratuit ; Unit : paie → Field (+ mal d'invocation sauf `GO_SOLO`) ; Program : paie → `ON_PLAY` → défausse ; Gear : paie → attaché à une Unit alliée |
| `AttackCommand` | actif | `MAIN`/`COMBAT` (auto `MAIN→COMBAT`) | épuise l'attaquant, ouvre la fenêtre de réaction, `ON_ATTACK`, puis **ouvre la résolution** (`CombatResolver.openAttack`, §3.5) : `AWAITING_BLOCK` si le défenseur a un `{Blocker}` prêt, sinon combat (cible déclarée **dépensée**) ou vol plafonné (attaque directe). Refusée tant qu'une attaque est en cours |
| `BlockCommand` (Mini-Feature 6) | **défenseur** (hors tour) | `MAIN`/`COMBAT` / `AWAITING_BLOCK` | `USE_BLOCKER` : incline chaque `{Blocker}` désigné (`cardIds`, ordre significatif ; `instanceId` pour un blocage simple), `ATTACK_BLOCKED`, compétences `ON_BLOCK` puis `ON_ATTACK`, combat contre le **dernier** Blocker, aucun Gig volé |
| `DeclineBlockCommand` (Mini-Feature 6) | **défenseur** (hors tour) | `MAIN`/`COMBAT` / `AWAITING_BLOCK` | `DECLINE_BLOCK` : renoncement explicite → `CombatResolver.resolveAttack` (combat ou choix des dés à voler) |
| `StealGigCommand` (Mini-Feature 6) | actif (attaquant) | `MAIN`/`COMBAT` / `AWAITING_STEAL_CHOICE` | `STEAL_GIG` : transfère **exactement M** dés Gigs actifs choisis (`dice` = identifiants `gigDieIds`), type + valeur + identifiant conservés, `GIG_STOLEN` par dé |
| `SellCardCommand` | actif | `MAIN` | 1 carte de la main → révélée au rival puis posée en Eddies Area `faceDown=true`, `exhausted=false` (prête) ; **aucun Eddie immédiat** : la carte devient une ressource à incliner (`SpendResourceCommand`, 1 €$/tour). **Mini-Feature 10C** : types `UNIT`/`LEGEND` refusés (« Les Unités et les Légendes ne peuvent pas être vendues ») — seuls `PROGRAM`, `GEAR`… sont vendables |
| `SpendResourceCommand` (R4, Mini-Feature 4) | actif | `MAIN` | **Générer des Eddies** : incliner une ressource — Legend non inclinée de la `LEGENDS_AREA` **ou** carte vendue non inclinée de l'`EDDIES_AREA` (ID unique, propriété du joueur ordonnateur vérifiée) → `exhausted=true`, `availableEddies += 1` ; 1 €$ par tour et par carte, redressée au START PHASE. Actions filaires : `SPEND_RESOURCE` (+ aliases historiques `SPEND_LEGEND`/`SPEND_EDDIES` via `SpendLegendCommand`/`SpendEddiesCommand`, sous-classes de cette commande) |
| `EndTurnCommand` | actif | toute sauf `DRAW` | **résolution automatique d'un combat en suspens** (`CombatResolver.autoResolve`), `ON_TURN_END`, fermeture fenêtre, passage du tour, `DRAW_START`, victoire à 7 Gigs, redressement, puis **attente** `AWAITING_DRAW` |
| `DrawCardCommand` (Mini-Feature 5) | actif | `DRAW` / `AWAITING_DRAW` | pioche 1 (deck vide → défaite), puis `AWAITING_DIE_SELECT` (ou `MAIN` si plus aucun dé). Action filaire `DRAW_CARD` |
| `SelectDieCommand` (Mini-Feature 5) | actif | `DRAW` / `AWAITING_DIE_SELECT` | valide le dé (`dice[0]` ∈ Fixer Area, `d20` seulement en dernier), lance côté serveur, `gigs`/`gigDice` += résultat, puis `MAIN`. Action filaire `SELECT_DIE` |

Détails :

- **Paiement** : `max(0, coût − remise)`, Eddies insuffisants → refus.
  Les Legends ne paient jamais (FLIP gratuit, quel que soit le coût imprimé).
- **Seuil Street Cred** : exigé à la pose si la carte en imprime un (non consommé).
- **Gear** : hôte obligatoire (Unit alliée du Field) ; sa puissance compte dans
  le total de l'hôte ; il suit l'hôte dans la défausse. Pas de déséquipement en V1.
- **Program** : résolu depuis la main puis défaussé (même sans effet).
- **Combat** (`RuleEngine.fight`) : totaux = unit + gears ; la puissance la plus
  haute l'emporte, **égalité = les deux Units vaincues** (règle officielle
  « *Higher defeats other. Tie both defeated.* »), journal `FIGHT`. Si un
  participant a quitté le Field (réaction `ON_ATTACK`, effet tiers), l'attaque est
  **sans effet** (`CombatResolver.fizzle`, journal `ATTACK` en `FAILED`) — pas de
  dégâts « dans le vide ».
- **Cibles d'attaque** : une Unit rivale **dépensée** uniquement (« *Ready Units
  can't be attacked* ») ou la Gig Area adverse. Attaquer sa propre Unit, une Unit
  prête ou une carte hors Field est refusé.
- **BLOCKER** : un `{Blocker}` rival prêt n'interdit plus rien à l'attaquant — il
  ouvre la fenêtre « Utiliser Blocker ? » et c'est le **défenseur** qui décide
  (`USE_BLOCKER` / `DECLINE_BLOCK`, §3.5). Blocage multiple autorisé : tous les
  Blockers désignés sont dépensés et résolvent leurs compétences, **seul le
  dernier** encaisse les dégâts. Une attaque redirigée ne vole aucun Gig.
- **Vol de Gigs** : quota `N = (power / 10) + 1` (0 si power ≤ 0), **plafond
  strict** `M = min(N, dés Gigs actifs du défenseur)` — jamais un dé de la Fixer
  Area, jamais de dé créé. L'attaquant choisit les `M` dés (`STEAL_GIG`) ; `M = 0`
  = attaque réussie sans vol.
- **Fenêtre de réaction** : ouverte à chaque attaque pour le défenseur ; le
  défenseur n'y joue que des cartes `QUICK` (hors tour, coûts payés normalement) ;
  le joueur actif n'est pas restreint ; fermeture sur `EndTurnCommand`.
- **Fin de tour** : un combat encore en attente est résolu d'office (blocage
  refusé implicitement, puis vol automatique des `M` dés de plus haute valeur) ;
  victoire immédiate si l'entrant a ≥ 7 Gigs (**avant** pioche
  et lancer) ; sinon redressement puis **attente** de la pioche (`AWAITING_DRAW`).
  La pioche (`DRAW_CARD`, deck vide → défaite) et le lancer (`SELECT_DIE`, plus de
  dé → on saute) sont ordonnés par le joueur ; `MAIN` s'ouvre ensuite (§3.4).
- **Choix du dé** : refus si le dé est inconnu, déjà lancé (absent de la Fixer
  Area) ou si c'est le `d20` alors qu'il reste d'autres dés (« *always rolled
  last* »). L'état ne bouge pas sur un refus : le joueur rechoisit.

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
| `blocker` | interception **au choix du défenseur** (fenêtre `AWAITING_BLOCK`, déclencheur `ON_BLOCK`, mini-langage `ON_BLOCK:…` ou marqueur `{Block}`) ; le rappel `{Blocker}` dans un texte reste ignoré |
| `go_solo` | attaque le tour de pose (Units ; Legends-jouées-en-Units = V2) |
| `adrenaline` / `haste` | alias « jeu rapide » : mal d'invocation ignoré (`haste` accepté par le moteur et le schéma, aucune carte du catalogue officiel ne le porte encore) |
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
| Défaite : pioche impossible (deck vide) | `DrawCardCommand` → `GameState.drawCards` + journal `DRAW`/`VICTORY` |
| Premier joueur tiré au sort + malus | `GameService.createGame` (`setupRandom`), `FIRST_PLAYER_SPENT_LEGENDS` |
| Phases Draw → Main → Combat → End | `Phase`, transitions auto (`EndTurnCommand`, `AttackCommand`) ; `DRAW` interactive (`DrawStep`, `DrawPhaseHandler`) |
| START PHASE : ready → draw 1 → gain a Gig, `d20` en dernier | `DrawPhaseHandler` (`readyAndAwaitDraw`, `drawCard`, `rollSelectedDie`), `Player.selectableFixerDice()` |
| Legend Call : 1 Eddie, once per turn, effet CALL/FLIP (R4) | `PlayCardCommand` branche Legend (coût 1, `hasCalledLegendThisTurn`, triggers `FLIP`+`CALL`) |
| Unit : power = dégâts | `RuleEngine.fight` (comparaison des puissances, égalité = les deux vaincues), `DAMAGE` létal |
| Cibles d'attaque : Unit rivale **dépensée** ou Gig Area | `AttackCommand.validate` (« Ready Units can't be attacked »), `validAttackTargets` côté front |
| Une attaque à la fois (toutes ses étapes avant la suivante) | `GameState.isCombatPending()`, refus `AttackCommand.validate` |
| Unit posée : pas d'attaque sauf `GO_SOLO`/`ADRENALINE`/`HASTE` | `summoningSickness` (`PlayCardCommand`), levée au tour suivant, exemption `CardInstance.canIgnoreSummoningSickness()` |
| Program : effet Play puis défausse | `PlayCardCommand` (branche Program) |
| Gear : attaché à une Unit | `PlayCardCommand` (hôte obligatoire), `totalPowerFor`, suivi en défausse |
| RAM = deckbuilding uniquement (R7) — aucune vérif en jeu | `GameConstants.RAM_CEILING_ENFORCED=false`, `PlayCardCommand` sans vérif RAM |
| Eddies : cycle 0→tap→pay→lost (R2) ; Legends et Eddies cards +1 par tap, redress au START, reset à 0 | `Player.spendResourceForEddies` (R4 unifié, Mini-Feature 4), `Player.spendLegendForEddies`, `Player.spendEddiesCardForEddies` (déléguent), `SpendResourceCommand` (+ alias `SpendLegendCommand`/`SpendEddiesCommand`), `Player.startTurn` (eddies=0 & readyAll) |
| Eddies / Street Cred | réserve dépensée / seuil non consommé (`Player`, `PlayCardCommand`) |
| Vente 1 carte/tour, **0 Eddie immédiat** (création de ressource) ; **`UNIT`/`LEGEND` invendables** (Mini-Feature 10C) | `SALES_PER_TURN`, `SellCardCommand` + `hasSoldThisTurn` + `isSellableType`, gain via `SpendEddiesCommand` |
| Réactions QUICK uniquement | `ReactionWindow`, `PlayCardCommand` (défenseur), fermeture en fin de tour |
| BLOCKER intercepte **au choix du défenseur** (blocage multiple, dernier Blocker seul touché) | `CombatResolver.openAttack`/`resolveBlock`, `BlockCommand` (`USE_BLOCKER`), `DeclineBlockCommand`, `Player.readyBlockers()`, événement `ATTACK_BLOCKED`, déclencheur `ON_BLOCK` |
| Vol de Gigs : quota `N = (power / 10) + 1`, 0 si power 0 | `RuleEngine.calculateQuota`, `GameConstants.POWER_PER_EXTRA_GIG` |
| **Plafond strict** : `M = min(N, dés Gigs actifs du défenseur)`, jamais la Fixer Area, jamais de dé créé | `RuleEngine.calculateActualStealable`, `Player.activeGigs()`/`getActiveGigCount()`, `CombatResolver.resolveAttack` |
| L'attaquant choisit **quels** dés voler, type + valeur conservés | `StealGigCommand` (`STEAL_GIG`), `GameState.stealGig(from, to, dieId)`, `Player.addGigDie`/`removeGigById`, `GigDie` |
| Victoire : 7 dés au **tout début** de la phase DRAW (jamais pendant un vol) | `EndTurnCommand` → `DrawPhaseHandler` (`VICTORY_CHECK`), `GameConstants.GIGS_TO_WIN` |
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
  (`EndTurnCommand` à 7 Gigs, `DrawCardCommand` sur deck vide) ; toute commande
  ultérieure est refusée.
- `setPhase(gameId, phase, actorId)` (debug) : forcer `DRAW` positionne
  `drawStep = AWAITING_DRAW` pour que la phase reste jouable.
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
  combat (victoire/égalité, cibles dépensées), BLOCKER (interception au choix du
  défenseur), vol (choix de l'attaquant + plafond strict), QUICK, vente unique,
  victoire à 7, phase DRAW interactive (pioche puis dé sur action du joueur),
  deck-out, `GO_SOLO`.
- `engine/CombatStealTest` (Mini-Feature 6 — « Combat, Vol de Dés & Plafond des
  Dés Actifs ») : `testR6_Power0_StealsZeroGigs`, `testR6_Power1To9_StealsOneGig`,
  `testR6_Power10To19_StealsTwoGigs`, `testR6_Power25_TargetHasOnly2Gigs_StealsOnly2Gigs`,
  `testR6_TargetHas0Gigs_Steals0Gigs_AttackSucceeds`,
  `testR6_Blocker_RedirectsAttackToBlockerUnit`,
  `testR6_UnitVsUnit_DefeatsUnitIfDamageGreaterOrEqualPower`,
  `testR6_VictoryCondition_7Gigs_AtStartOfDrawPhase`, plus
  `testR6_Quota_Formula_AndStrictCeiling`, `testR6_StolenDie_KeepsExactDieAndValue`,
  `testR6_StealChoice_ValidationGuards`, `testR6_OnlyActiveDiceAreStealable`,
  `testR6_Steal_NeverWinsImmediately`, `testR6_Blocker_DirectAttack_StealsNothing`,
  `testR6_DeclineBlock_ThenAttackResolves`, `testR6_MultiBlock_OnlyLastBlockerTakesDamage`,
  `testR6_MultiBlock_LastBlockerMayDie`, `testR6_Block_ValidationGuards`,
  `testR6_NoReadyBlocker_AttackResolvesImmediately`,
  `testR6_AttackTargets_OnlySpentRivalUnits`, `testR6_Haste_IgnoresSummoningSickness`,
  `testR6_OneAttackAtATime`, `testR6_EndTurn_AutoResolvesPendingAttack`.
  > Numérotation : les noms `testR6_*` suivent le brief de la Mini-Feature 6
  > (« règle R6 : Combat, Vol de Dés & Plafond ») et ne recouvrent pas le `R6`
  > de `docs/RULE-CHECKLIST.md` (cartes de l'Eddies Area) — les entrées de
  > checklist concernées sont R9 (combat), R10 (Lag/`HASTE`), R11 (`{Blocker}`)
  > et R12 (Gigs & victoire).
- `engine/DrawPhaseTest` (Mini-Feature 5 — « Phase DRAW interactive & choix des
  dés ») : `testR5_Draw_Sequence_RequiresPlayerActionForDraw` (fin de tour →
  `AWAITING_DRAW`, rien de pioché ni lancé, `END_TURN`/`SELECT_DIE`/jeu refusés,
  `DRAW_CARD` → `AWAITING_DIE_SELECT`, `SELECT_DIE` → `MAIN`, journal ordonné),
  `testR5_SelectDie_OnlySmallestDieUntilAllUsedExceptD20` (tout dé sauf le `d20`
  tant qu'il en reste d'autres, dé inconnu/déjà lancé refusés, normalisation
  `D10`, `d20` seulement en dernier, Fixer Area vide → `MAIN` direct),
  `testR5_EmptyDeck_IsLoss` (défaite au clic sur le deck vide, journal `DRAW`
  `FAILED` + `VICTORY`), plus victoire à 7 Gigs avant la pioche, vues masquées,
  type de dé du Gig volé, ressources refusées en `DRAW`.
- `engine/command/SellCardCommandTest` (Mini-Feature 3 — « Vente = Création de
  ressource » + Mini-Feature 10C — « Restriction de vente par Type de Carte ») :
  `testR3_SellCard_GoesToEddiesArea_FaceDown_NotExhausted`,
  `testR3_SellCard_LimitOnePerTurn`, `testR3_SellCard_CreatesResourceUsableSameTurn`,
  `testR3_SellCard_IllegalContexts`, `testR3_SellCard_CommandContract`,
  `testR_SellCard_ProgramOrGear_Success` (PROGRAM puis GEAR vendus, 1/tour),
  `testR_SellCard_Unit_Rejected`, `testR_SellCard_Legend_Rejected` (refus
  « Les Unités et les Légendes ne peuvent pas être vendues », aucune mutation,
  quota intact) — main → `EDDIES_AREA` (`faceDown=true`, `exhausted=false`,
  0 Eddie), 1 vente/tour, ressource inclinable dès ce tour, refus (hors tour,
  hors `MAIN`, carte hors main, partie terminée, joueur inconnu, type `UNIT`/`LEGEND`).
- `engine/command/SpendResourceCommandTest` (Mini-Feature 4 — « Générer des
  Eddies ») : `testR4_SpendLegend_Gives1Eddie_AndBecomesExhausted`,
  `testR4_SpendEddieCard_Gives1Eddie_AndBecomesExhausted`,
  `testR4_ResourceReadyAgainNextTurn`, `testR4_AlreadyExhausted_Refused`,
  `testR4_OwnershipAndZone_Refused`, `testR4_IllegalContexts_Refused`,
  `testR4_CommandContract` — Legend **ou** carte Eddies : +1 Eddie,
  `exhausted=true`, carte en zone ; refus (déjà inclinée, carte du rival,
  hors zones ressources, instance inconnue, hors `MAIN`, partie terminée).
- `service/GameServiceTest` (Mockito, sans Spring) : création, premier joueur
  tiré au sort + malus, exécution, refus consignés au journal, masquage, 404.
- `ws/LobbyGameFlowWebSocketIntegrationTest` : partie STOMP de bout en bout
  (états masqués, actions, phase DRAW interactive `DRAW_CARD`/`SELECT_DIE` avec
  refus du `d20`, erreurs privées, abandon) avec premier joueur aléatoire.
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
  en fin de tour (pas de passe explicite ni de pile proposée/répondue). Les cartes
  `QUICK` se jouent **avant** la décision de blocage (la fenêtre Blocker s'ouvre
  après `ON_ATTACK`) ; un empilement fin de réactions pendant le combat reste à
  faire (V2).
- Combat interactif (Mini-Feature 6) : pas de délai côté serveur pour la fenêtre
  « Utiliser Blocker ? » ni pour le choix des dés — `END_TURN` résout d'office
  (blocage refusé, vol des `M` dés les plus forts). Un minuteur de décision
  resterait à ajouter pour le jeu compétitif.
- `haste` est accepté (moteur, schéma `card-schema.json`, types front) mais aucune
  carte du catalogue officiel ne le porte : le scraper ne le produit pas encore.
- Pas de persistance des parties ni de diffusion STOMP (feature 04) ; pas de
  contrôle de deck (feature deck-builder).
