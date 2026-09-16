# Checklist des Règles Officielles — Cyberpunk TCG (R1 → R13)

Source : `docs/OFFICIAL-RULES.md` (scrap de https://cyberpunktcg.com/gameplay-guide du 2026-09-15)
Méthode : TDD — un test `testR{N}_*` par règle minimum, implémentation isolée, `mvn test` vert.

Légende : `- [x]` = à faire, `- [x]` = test écrit + impl + vert + documenté. Chaque règle cite la source exacte.

---

## R1 — Setup de la partie

- [x] **R1.1** Chaque joueur : 3 Legends face-down aléatoires en Legends Area — *Source: Guide § PLAYMAT AREAS “3 cards here are your Legends” + § SETUP “randomize your Legends face-down”*
- [x] **R1.2** Deck principal 40-50 cartes (hors Legends), 6 dés Gig (d4,d6,d8,d10,d12,d20) en Fixer Area — *Source: Guide § PLAYMAT AREAS Fixer Area + § DECK BUILDING “40-50 cards”*
- [x] **R1.3** Premier joueur tiré au sort (d20, reroll tie, higher decides) — *Source: § SETUP “Both players roll a d20 (reroll on a tie). Whoever rolls higher decides who goes first.”*
- [x] **R1.4** Premier joueur T1 : 2 leftmost Legends spent (épuisées) et ne se redressent pas au 1er tour — *Source: § SETUP “The player going first spends their 2 leftmost Legends and doesn't ready them on their first turn.”* — *Tests: `testR1_Setup_FirstPlayerHasTwoExhaustedLegends`, `testR1_FirstPlayerMalus_2LegendsExhausted`*
- [x] **R1.5** Second joueur T1 : 0 Legend pré-inclinée — *Source: § SETUP (absence de malus pour le second)* — *Test: `testR1_Setup_SecondPlayerHasZeroExhaustedLegends`*
- [x] **R1.7** Phase DRAW de chaque tour : toutes les Legends sont redressées et les Eddies retombent à 0 (lève le malus du premier joueur à son tour 2) — *Source: § START PHASE “READY SPENT CARDS Return all your spent (sideways) cards to the ready position.”* — *Test: `testR1_DrawPhase_ReadiesAllLegends`*
- [x] **R1.6** Main de départ : 6 cartes (+ mulligan once) — *Source: § SETUP “DRAW 6 ... you can mulligan once.”*

**Implémentation attendue :** `GameService.createGame` (+ `applyFirstPlayerPenalty`) + `Player.exhaustLeftmostLegends(2)` + `Player.freshFixerDice()` + `STARTING_HAND_SIZE=6` + `FIRST_PLAYER_SPENT_LEGENDS=2` + tirage Random ; redressement en phase DRAW via `Player.startTurn()` → `readyAll()` appelé par `DrawPhaseHandler.readyAndAwaitDraw` (depuis `EndTurnCommand`, avant l'attente de la pioche).

---

## R2 — Cycle des Eddies

- [x] **R2.1** Début de tour : 0 Eddie disponible (on repart de zéro) — *Source: Guide § EDDIES “Each face-down card in your Eddies area is 1 Eddie. Spend them (turn them sideways) to pay” + Task R2 spec (reset mana)*
- [x] **R2.2** Sources : incliner Legend (+1 €$), incliner carte Eddies (+1 €$), effets de cartes — *Source: § PLAYMAT LEGENDS “You can also spend any number of Legends as 1 €$ each” + § EDDIES + § PLAY*
- [x] **R2.3** Fin de tour : Eddies restants perdus (pas de report) — *Source: Task R2 “Fin de tour : Eddies restants perdus” + logique “spend sideways until ready”*
- [x] **R2.4** Test : Eddies repartent de 0 chaque tour après `EndTurnCommand` / `startTurn` — *Repo: `Player.startTurn()` reset eddies*

---

## R3 — Legends comme ressources

- [x] **R3.1** Incliner une Legend (face-down ou face-up selon Guide, face-down selon Task) → +1 Eddie, Legend reste sur terrain (pas détruite) — *Source: § LEGENDS AREA “Whether face-up or face-down, you can also spend a Legend to pay 1 €$ (like spending an Eddie).”*
- [x] **R3.2** Chaque début de tour : redresser toutes les Legends (et Eddies) — *Source: § START PHASE “READY SPENT CARDS Return all your spent (sideways) cards to the ready (upright) position.”* — *Note: Task R3 exige redress des Legends ; l'ancien code “définitif” est retiré.*
- [x] **R3.3** Test : `SpendLegendCommand` +1, `isExhausted`, puis après tour `!isExhausted` — *Impl: `Player.spendLegendForEddies` + `Player.startTurn` ready Legends*

---

## R4 — Legends comme cartes jouables (Call a Legend)

- [x] **R4.1** Payer 1 Eddie → flip une Legend face visible (Call, once per turn, random, sans regarder) — *Source: § LEGENDS AREA “Once per turn, you may Call a Legend by spending 1 €$ to flip a Legend face-up, without looking first.” + § MAIN PHASE “CALL A LEGEND (ONCE PER TURN)”*
- [x] **R4.2** Ses capacités/keywords se déclenchent ({Call}, {Play?} selon trigger) — *Source: § TIMING TRIGGERS “CALL When you flip this Legend”*
- [x] **R4.3** La Legend flip reste active sur le terrain (Legends Area, pas retirée) — *Source: § LEGENDS AREA (reste en place)*
- [x] **R4.4** Test : flip → coût déduit, effet CALL déclenché, Legend toujours en `LEGENDS_AREA` faceUp — *Impl: `PlayCardCommand` branche Legend avec coût + limite `hasCalledLegendThisTurn`*

---

## R5 — Vente de carte

- [x] **R5.1** Limite : 1 par tour — *Source: § MAIN PHASE “SELL FOR EDDIE (ONCE PER TURN)” + § GLOSSARY SELL “Once per turn”*
- [x] **R5.2** Révéler la carte à l'adversaire — *Source: § MAIN PHASE “reveal it to your opponent”*
- [x] **R5.3** Poser face cachée dans zone EDDIES_AREA (pas trash, pas discard) — *Source: § EDDIES AREA + § SELL “place it face-down in the Eddies area”*
- [x] **R5.4** ~~+1 Eddie immédiat~~ → **aucun Eddie immédiat** (Mini-Feature 3, 2026-09-15) : la vente *crée* la ressource, l'Eddie est gagné en l'inclinant (R6) — *Source: § MAIN PHASE “place it face-down in the Eddies area” + § Eddies “it's only worth 1 €$ per turn as an Eddie” (aucun gain immédiat dans le Guide)*
- [x] **R5.5** La carte reste en zone Eddies comme ressource (spendable chaque tour, **y compris le tour de la vente** car posée `exhausted = false`) — *Source: § EDDIES “Each face-down card in your Eddies area is 1 Eddie. Spend them ... to pay”*
- [x] **R5.6** Test : vendre → `EDDIES_AREA`, `faceDown = true`, `exhausted = false`, `eddies` inchangé, `hasSoldThisTurn` — *Impl: `SellCardCommand`* — *Tests: `SellCardCommandTest.testR3_SellCard_GoesToEddiesArea_FaceDown_NotExhausted`, `SellCardCommandTest.testR3_SellCard_LimitOnePerTurn`, `testR5_Sell_*`*

**Implémentation attendue (Mini-Feature 3 — « Vente = Création de ressource ») :** `SellCardCommand.execute` = révélation (`CARD_REVEALED` + événement `EFFECT_RESOLVED`) → `Player.moveToZone(card, EDDIES_AREA)` → `card.setFaceDown(true)` + `card.setExhausted(false)` → `player.setHasSoldThisTurn(true)`. **Aucun `player.addEddy()`** ; le gain d'Eddie passe exclusivement par `SpendEddiesCommand` (R6.1). Limite `GameConstants.SALES_PER_TURN = 1` vérifiée dans `validate`, refus journalisé `ILLEGAL`.

---

## R6 — Cartes de la zone Eddies comme ressources

- [x] **R6.1** Chaque tour : incliner une carte Eddies (spend) → +1 Eddie — *Source: § EDDIES + § SPEND “Eddies and Legends spend to pay card costs”*
- [x] **R6.2** Même mécanique que les Legends (spend sideways, même coût) — *Source: § EDDIES “Spend them (turn them sideways)”*
- [x] **R6.3** Redresser au début de chaque tour (READY SPENT CARDS) — *Source: § START PHASE “Return all your spent cards to the ready position.”*
- [x] **R6.4** Test : `SpendEddiesCommand` → +1, exhausted, puis ready au tour suivant — *Impl: nouveau `SpendEddiesCommand` / `Player.spendEddiesCard`*

---

## R7 — RAM (deckbuilding uniquement)

- [x] **R7.1** La RAM sert UNIQUEMENT à la construction du deck — *Source: § DECK BUILDING & RAM “Cards must stay within the RAM limit set by your Legends” + Guide § RAM (deckbuilding)*
- [x] **R7.2** EN JEU : aucune vérification de RAM (on peut jouer toute carte payée) — *Source: Task R7 spec*
- [x] **R7.3** Supprimer TOUTE vérification de RAM dans `PlayCardCommand` (garder uniquement deckbuilder frontend) — *Impl: retirer `requireRamCeiling`*
- [x] **R7.4** Test : jouer carte rouge sans Legend rouge → SUCCESS — *Test `testR7_NoRamCheckInGame`*

---

## R8 — Phases de tour

- [x] **R8.1** START PHASE : 1) Ready spent cards, 2) Draw 1, 3) Gain a Gig (roll die, d20 last) — *Source: § TURN ORDER START PHASE*
- [x] **R8.1 bis (Mini-Feature 5, 2026-09-15)** La START PHASE est **interactive** : après `END_TURN` la partie s'arrête en `DRAW` / `AWAITING_DRAW` (cartes redressées, 0 Eddie, rien de pioché) ; le joueur doit envoyer `DRAW_CARD` (pioche 1, deck vide = défaite) puis `SELECT_DIE` (**n'importe quel dé sauf le d20, toujours lancé en dernier** ; lancer serveur `1..faces`, résultat dans `gigs` + `gigDice`) ; `MAIN` s'ouvre ensuite automatiquement. `END_TURN` et toute action de jeu sont refusées pendant `DRAW`. — *Source: § START PHASE “DRAW 1 … GAIN A GIG: Take a die from your fixer area, roll it … You can choose any die except the d20, which is always rolled last.”* — *Impl: `DrawStep`, `DrawPhaseHandler`, `DrawCardCommand`, `SelectDieCommand`, `Player.selectableFixerDice()`* — *Tests: `DrawPhaseTest.testR5_Draw_Sequence_RequiresPlayerActionForDraw`, `DrawPhaseTest.testR5_SelectDie_OnlySmallestDieUntilAllUsedExceptD20`, `DrawPhaseTest.testR5_EmptyDeck_IsLoss`, `testR8_PhasesOrder`, `testR8_StartPhaseSteps`*
- [x] **R8.2** MAIN PHASE : ressources (spend Legends/Eddies), vente (1/tour), Call (1/tour), jouer, attaquer (dans n'importe quel ordre) — *Source: § MAIN PHASE*
- [x] **R8.3** COMBAT : Units attaquent (spend), power = dégâts, BLOCKER redirection — *Source: § ATTACKING*
- [x] **R8.4** END : Eddies perdus (reset à 0), passage au joueur suivant, victoire check au début du tour suivant — *Source: § WIN CONDITION + Task R8*
- [x] **R8.5** Test : vérifier l'ordre exact des phases `DRAW → MAIN → COMBAT → END → DRAW ...` via `Phase.next()` et `EndTurnCommand` logs (la phase `DRAW` étant interactive : `DRAW_START → AWAITING_DRAW → AWAITING_DIE_SELECT → ROLLING_DIE → DRAW_COMPLETE`)

---

## R9 — Combat

- [x] **R9.1** Une Unit peut attaquer si elle n'est pas exhausted et pas en Lag (mal d'invocation) — *Source: § LAG “Units can't attack the turn they're played.” + § READY “Only ready Units can attack”*
- [x] **R9.2** Power = dégâts infligés (compare total power Unit + Gear) — *Source: § ATTACKING Fight “Compare both Units' power. Higher defeats other. Tie both defeated.” + § POWER* — *Impl (Mini-Feature 6) : `RuleEngine.fight`, journal `FIGHT`*
- [x] **R9.3** BLOCKER : le défenseur peut dépenser son BLOCKER pour rediriger l'attaque sur son Blocker (spend to redirect) — *Source: § KEYWORDS BLOCKER + § REACT “Spend a Unit with BLOCKER”* — **Mini-Feature 6** : le choix appartient au **défenseur** (fenêtre « Utiliser Blocker ? », `AWAITING_BLOCK`) ; l'attaquant ne subit plus de ciblage forcé et l'attaque directe n'est plus interdite — *Tests: `testR6_Blocker_RedirectsAttackToBlockerUnit`, `testR6_Blocker_DirectAttack_StealsNothing`, `testR6_DeclineBlock_ThenAttackResolves`, `testR6_Block_ValidationGuards`, `testR9_BlockerIntercepte`, `testR11_Keyword_Blocker`*
- [x] **R9.4** Unit vaincue → défausse (Trash) + trigger {Defeated} — *Source: § ATTACKING “Move defeated Units to the trash and resolve any DEFEATED effects”*
- [x] **R9.5** Test : attaque → dégâts, BLOCKER → interception, vaincue en Trash — *Impl: `AttackCommand` + `CombatResolver` + `RuleEngine.fight`/`defeatUnit`*
- [x] **R9.6** (Mini-Feature 6) Cibles légales : une Unit rivale **dépensée** ou la Gig Area adverse — « *Ready Units can't be attacked* » ; déclarer une attaque **épuise** l'attaquant — *Source: § ATTACKING “Choose a rival Unit (Ready Units can't be attacked) or attack your rival directly” + § ATTACKING “Spend the attacking Unit”* — *Tests: `testR6_AttackTargets_OnlySpentRivalUnits`, `testR6_UnitVsUnit_DefeatsUnitIfDamageGreaterOrEqualPower`*
- [x] **R9.7** (Mini-Feature 6) Une Unit attaque individuellement et **termine toutes ses étapes** avant qu'une autre Unit n'attaque : `AWAITING_BLOCK` → `AWAITING_STEAL_CHOICE` → résolution, une seule attaque en cours à la fois — *Source: § ATTACKING “Each Unit attacks individually, and completes all the attacking steps before another Unit can attack.”* — *Impl: `PendingAttack`, `CombatStep`, `GameState.isCombatPending()`* — *Test: `testR6_OneAttackAtATime`*
- [x] **R9.8** (Mini-Feature 6) Blocage **multiple** : le défenseur peut bloquer avec tous ses Blockers prêts ; chacun est dépensé et résout ses compétences (`ON_BLOCK`), **seul le dernier Blocker déclaré** encaisse les dégâts ; une attaque redirigée ne vole aucun Gig — *Source: § KEYWORDS BLOCKER + § ATTACKING (redirection) + arbitrage utilisateur 2026-09-16* — *Impl: `BlockCommand` (`USE_BLOCKER`, `cardIds` ordonnés), `CombatResolver.resolveBlock`, événement `ATTACK_BLOCKED`, `TriggerType.ON_BLOCK`* — *Tests: `testR6_MultiBlock_OnlyLastBlockerTakesDamage`, `testR6_MultiBlock_LastBlockerMayDie`*
- [x] **R9.9** (Mini-Feature 6) Un combat en suspens à la fin du tour est résolu automatiquement (blocage refusé implicitement, puis vol des M dés les plus forts) — un joueur silencieux ne gèle pas la partie — *Impl: `EndTurnCommand` → `CombatResolver.autoResolve`, journal `GIG_STEAL_AUTO`* — *Test: `testR6_EndTurn_AutoResolvesPendingAttack`*

---

## R10 — Mal d'invocation (Lag)

- [x] **R10.1** Une Unit jouée ce tour ne peut pas attaquer (sauf ADRENALINE/GO_SOLO/**HASTE**, alias « jeu rapide » accepté depuis la Mini-Feature 6) — *Source: § MAIN PHASE “Units can't attack on the turn they're played.” + § LAG + § KEYWORDS ADRENALINE* — *Impl: `CardInstance.canIgnoreSummoningSickness()` (`GO_SOLO` | `ADRENALINE` | `HASTE`), `CardKeyword.HASTE`, enum `haste` du schéma `card-schema.json`* — *Test: `testR6_Haste_IgnoresSummoningSickness`*
- [x] **R10.2** Redressée / Lag dissipé au début du tour suivant (startTurn clear Lag) — *Source: § LAG “lasts until the end of the turn.” + § START PHASE Ready*
- [x] **R10.3** Test : jouer Unit → attaquer → REFUSÉ (Lag), tour suivant → OK ; avec ADRENALINE/GO_SOLO → OK immédiatement — *Impl: `CardInstance.summoningSickness` + `hasGoSolo` + `isAdrenaline`*

---

## R11 — Keywords des cartes

- [x] **R11.1** {Play} : effet à la pose (Trigger ON_PLAY) — *Source: § TIMING TRIGGERS PLAY*
- [x] **R11.2** {Blocker} : interception d'attaque (mot-clé + spend to redirect) — *Source: § KEYWORDS BLOCKER* — **Mini-Feature 6** : interception sur **décision du défenseur** (fenêtre `AWAITING_BLOCK`, actions `USE_BLOCKER`/`DECLINE_BLOCK`), déclencheur `ON_BLOCK` (mini-langage `ON_BLOCK:DRAW:1`, marqueur naturel `{Block}`) — *Tests: `testR11_Keyword_Blocker`, `testR6_MultiBlock_OnlyLastBlockerTakesDamage`*
- [x] **R11.3** {Go Solo} / ADRENALINE : Legend jouée comme Unit prête (ou Unit avec Adrenaline) peut attaquer le tour de pose — *Source: § KEYWORDS GO SOLO + ADRENALINE*
- [x] **R11.4** {Spend} : capacité activée en inclinant (Spend) — *Source: § GLOSSARY SPEND*
- [x] **R11.5** {Call} : choix parmi plusieurs effets / flip Legend — *Source: § TIMING CALL + § CALL A LEGEND*
- [x] **R11.6** {Defeated} : effet à la destruction (ON_DEATH) — *Source: § TIMING DEFEATED*
- [x] **R11.7** QUICK : jouable pendant le tour adverse (réaction) — *Source: § KEYWORDS QUICK “You may also activate ... as a reaction when a rival Unit attacks.”*
- [x] **R11.8** Test : chaque keyword avec au moins 1 carte réelle du catalogue (`data/cards.json`) via `EffectParser` + triggers — *Impl: `EffectParser` + `TriggerType` + `RuleEngine`*

---

## R12 — Gigs et victoire

- [x] **R12.1** Gigs gagnés via dés Gig (Fixer → Gig Area roll) et effets de cartes (Steal, Increase) — *Source: § FIXER + § PLAYMAT GIG AREA + § STEAL “Choose a rival Gig die and move it”*
- [x] **R12.2** Victoire : 7 Gigs vérifiés au DÉBUT du tour (START PHASE, avant Draw/Gain) — *Source: § WIN CONDITION “START YOUR TURN WITH 7 GIGS TO WIN” + § PLAYMAT GIG AREA “If you start your turn with 7 ... you win”*
- [x] **R12.3** OVERTIME : après le 7e tour du dernier joueur, majorité instantanée (hors scope V0, documenté) — *Source: § WIN CONDITION OVERTIME*
- [x] **R12.4** Deck-out (pioche impossible) = défaite — *Source: Task R12 “Deck-out (pioche impossible) = défaite” + extrapolation règle (drawCards defeat)* — depuis la Mini-Feature 5, la défaite tombe **au clic sur la pioche** (`DRAW_CARD` sur deck vide : journal `DRAW` `FAILED` + `VICTORY`), pas à la fin du tour précédent — *Tests: `testR12_DeckOut_Defeat`, `DrawPhaseTest.testR5_EmptyDeck_IsLoss`*
- [x] **R12.5** Test : 7 Gigs → victoire au début tour suivant, 6 Gigs → pas de victoire, deck vide → défaite
- [x] **R12.6** (Mini-Feature 6) Quota de vol d'une attaque directe : power 0 → **0** Gig ; power ≥ 1 → `N = (power / 10) + 1` (1-9 → 1, 10-19 → 2, 20-29 → 3, …) — *Source: § ATTACKING STEAL “Units steal an extra Gig for every 10 power (and 0 Gigs at power 0)”* — *Impl: `RuleEngine.calculateQuota`, `GameConstants.POWER_PER_EXTRA_GIG = 10`* — *Tests: `testR6_Power0_StealsZeroGigs`, `testR6_Power1To9_StealsOneGig`, `testR6_Power10To19_StealsTwoGigs`, `testR6_Quota_Formula_AndStrictCeiling`*
- [x] **R12.7** (Mini-Feature 6) **Plafond strict des dés actifs** : `M = min(N, dés Gigs ACTIFS du défenseur)` — on ne vole jamais un dé de la Fixer Area (non lancé), on ne crée jamais de dé ; `M = 0` (power 0 **ou** défenseur sans dé actif) = attaque réussie **sans vol** — *Source: § STEAL “Choose a rival Gig die and move it to your friendly Gig area” (un dé existant, déjà lancé) + § PLAYMAT FIXER/GIG AREAS* — *Impl: `RuleEngine.calculateActualStealable`, `Player.activeGigs()`/`getActiveGigCount()`, `CombatResolver.resolveAttack`* — *Tests: `testR6_Power25_TargetHasOnly2Gigs_StealsOnly2Gigs`, `testR6_TargetHas0Gigs_Steals0Gigs_AttackSucceeds`, `testR6_OnlyActiveDiceAreStealable`*
- [x] **R12.8** (Mini-Feature 6) L'**attaquant choisit quels** dés voler (`STEAL_GIG`, exactement M identifiants `gigDieIds`) ; chaque dé conserve son identifiant, son type et sa valeur (« un d8 affichant 5 reste un d8 affichant 5 ») ; un vol ne fait jamais gagner immédiatement — *Source: § STEAL “Choose a rival Gig die” + § WIN CONDITION* — *Impl: `StealGigCommand`, `GigDie`, `GameState.stealGig(from, to, dieId)`, `Player.addGigDie`/`removeGigById`* — *Tests: `testR6_StolenDie_KeepsExactDieAndValue`, `testR6_StealChoice_ValidationGuards`, `testR6_Steal_NeverWinsImmediately`, `testR6_VictoryCondition_7Gigs_AtStartOfDrawPhase`, `DrawPhaseTest.testR5_StolenGigKeepsItsDieType`*

---

## R13 — Effets de cartes (EffectParser)

- [x] **R13.1** Parser les textes : "{Play} Draw 2", "Defeat a rival Unit", etc. — *Source: § READING YOUR CARDS + catalogue `data/cards.json`*
- [x] **R13.2** Effets supportés : DRAW, DAMAGE, DEFEAT, GRANT_POWER, STEAL_GIG, HEAL, DISCARD, BUFF (BOOST_GIG/REDUCE_GIG) — *Source: Task R13*
- [x] **R13.3** Effets non supportés (V0) : {Call} modal, conditionnels complexes (“you may”, “if you have”, “whenever”, “//”) — *Source: § §5.2 RULE-ENGINE*
- [x] **R13.4** Documenter les limites dans `docs/RULE-ENGINE.md` §5.2 + §11 — *Impl: `EffectParser` skip + docs*
- [x] **R13.5** Test : chaque type d'effet avec une carte réelle du catalogue (`adam-smasher-ender-of-legends` {Play} Defeat, `6th-street-recruits` etc.) ou fixture mini-langage

---

## Suivi global (tous cochés le 2026-09-15)

- [x] 25+ tests d'intégration `testR{N}_*` dans `GameIntegrationTest.java` au vert
- [x] `mvn clean test` 100% vert (backend + scraper)
- [x] Debug panel affiche logs temps réel (ws `/topic/game/{id}/log`)
- [x] Aucune vérification RAM en jeu
- [x] Cartes vendues restent en zone Eddies (EDDIES_AREA faceDown)
- [x] PR créée vers main
- [x] Docs finales : OFFICIAL-RULES.md, RULE-CHECKLIST.md, RULE-ENGINE.md, DEBUG-GUIDE.md, INTEGRATION-TEST.md

## Historique des coches (TDD)

Suivre ici le cochage règle par règle (Phase 2 A→E) — tous verts le 2026-09-15 via TDD :

- R1 : ✅ 7 tests testR1_* vert (setup complet, malus 2 Legends, second 0, deck 30+,
  2 Legends inclinées du premier joueur, 0 Legend inclinée du second, phase DRAW qui redresse tout)
- R2 : ✅ 3 tests testR2_* vert (reset 0 chaque tour, lost at end, sources Legend/Eddies/effets)
- R3 : ✅ 3 tests testR3_* vert (Legends tap +1, stay, ready next turn)
- R4 : ✅ 3 tests testR4_* vert (Call coûts 1, once per turn, stay)
- R5 : ✅ 3 tests testR5_* vert (1/tour, faceDown EddiesArea, +1, future resource)
- R6 : ✅ 3 tests testR6_* vert (Eddies cards tap +1, ready)
- R7 : ✅ 2 tests testR7_* vert (no RAM check)
- R8 : ✅ 3 tests testR8_* vert (START ready-draw-gig interactif, MAIN, COMBAT, END eddies lost)
- Mini-Feature 5 : ✅ 7 tests `DrawPhaseTest` (séquence DRAW interactive, choix du dé / d20 en dernier, deck vide = défaite, victoire à 7 avant pioche, vues masquées, dé du Gig volé, ressources refusées en DRAW)
- Mini-Feature 6 (2026-09-16) : ✅ 23 tests `CombatStealTest` (`testR6_*` — quota N, plafond strict M, défenseur à 0 dé, choix des dés volés, blocage au choix du défenseur, blocage multiple « dernier Blocker », renoncement, combat Unité vs Unité, cibles dépensées, `HASTE`, une attaque à la fois, résolution auto en fin de tour, victoire à 7 au début de la DRAW) + R9/R11/R12 réécrits dans `GameIntegrationTest`, `GameCommandTest`, `DrawPhaseTest`, `ActionCommandFactoryTest`
  > ⚠️ Numérotation : les `testR6_*` de la Mini-Feature 6 suivent le brief « Combat, Vol de Dés & Plafond des Dés Actifs » (règle R6 **du brief**) et ne correspondent pas au R6 de cette checklist (cartes de l'Eddies Area comme ressources, `SpendEddiesCommandTest`) — d'où des tests `testR6_*` dans deux classes différentes, sans collision de noms. Les règles de checklist concernées sont R9 (combat), R10 (Lag/`HASTE`), R11 (`{Blocker}`) et R12 (Gigs, quota, plafond, victoire).
- R9 : ✅ 4 tests testR9_* vert (ready+no Lag, power=dmg, Blocker, trash)
- R10: ✅ 3 tests testR10_* vert (Lag blocks, GoSolo/Adrenaline bypass, clear next turn)
- R11: ✅ 6 tests testR11_* vert (Play, Blocker, GoSolo, Quick, Defeated, Spend/Call skip)
- R12: ✅ 4 tests testR12_* vert (7 win at start, 6 no win, gig via dice/steal, deck-out)
- R13: ✅ 10 tests testR13_* vert (DRAW, DAMAGE, DEFEAT, GRANT_POWER, STEAL_GIG, HEAL, DISCARD, BUFF, Call modal ignored, conditional ignored, real card)
- **Total: 54 tests testR* (R1→R13) + 23 tests `CombatStealTest` (Mini-Feature 6) — tous au vert (même run, pas de régression)**
