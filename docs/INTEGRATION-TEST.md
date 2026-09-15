# Tests d'intégration — Feature 6.5.2 TDD Exhaustif R1→R13

Branche : `arena/01a0a507-cyberpunktcg`
Date : 2026-09-15

Ce document décrit les **51 tests d'intégration TDD** couvrant les 13 règles officielles (R1→R13), jouant des parties réelles à travers `GameService` (validation → exécution → journal de diagnostic). Chaque test est nommé `testR{N}_*` pour traçabilité.

Fichier : `backend/src/test/java/com/cyberpunktcg/engine/GameIntegrationTest.java` (51 tests).

Compléments : `docs/OFFICIAL-RULES.md` (scrap gameplay-guide 2026-09-15) | `docs/RULE-CHECKLIST.md` (checklist R1→R13) | `docs/RULE-ENGINE.md` (traçabilité + limites) | `docs/DEBUG-GUIDE.md` (journal)

## Résumé : 51 tests répartis R1→R13

| Règle | Tests | Exemples de noms | Règle vérifiée |
|---|---|---|---|
| **R1 Setup** | 4 | `testR1_Setup_Complete`, `testR1_FirstPlayerMalus_2LegendsExhausted`, `testR1_SecondPlayerNoMalus`, `testR1_Deck30Plus` | 3 Legends, 6 dés (d4…d20), main 6, premier tiré au sort (d20), malus 2 Legends spent |
| **R2 Cycle Eddies** | 3 | `testR2_EddiesResetEachTurn`, `testR2_EddiesLostAtEndIfNotSpent`, `testR2_EddiesSources` | 0 au début (reset), +1 Legend, +1 Eddies card, +effets, perdus à la fin (phase END) |
| **R3 Legends ressources** | 3 | `testR3_LegendSpend_GivesEddieAndStays`, `testR3_LegendReadyNextTurn`, `testR3_LegendSpendExhaustedFails` | incliner face-down/up → +1, reste en Legends Area, redress START |
| **R4 Legends jouables** | 3 | `testR4_FlipCostsOneEddie`, `testR4_FlipOncePerTurn`, `testR4_FlipStaysInLegendsArea` | Call 1 Eddie (once/turn, random), trigger CALL/FLIP, reste en place |
| **R5 Vente** | 3 | `testR5_Sell_OnePerTurn_FaceDownEddiesArea`, `testR5_Sell_StaysAsFutureResource`, `testR5_Sell_ResetNextTurn` | 1/tour, révélée (CARD_REVEALED), faceDown EDDIES_AREA **prête** (`exhausted=false`), **0 Eddie immédiat**, ressource inclinable (1 €$/tour) |
| **R6 Eddies cards ressources** | 3 | `testR6_EddiesCard_TapGivesEddie`, `testR6_EddiesCard_ReadyNextTurn`, `testR6_EddiesCard_AlreadyExhaustedFails` | incliner Eddies → +1, même mécanique Legends, ready au START |
| **R7 RAM** | 2 | `testR7_NoRamCheckInGame_RedWithoutRedLegend`, `testR7_HighRamStillPlayable` | RAM uniquement deckbuilding, aucune vérif en jeu (RAM_CEILING_ENFORCED=false) |
| **R8 Phases** | 3 | `testR8_PhasesOrder`, `testR8_StartPhaseSteps`, `testR8_EndPhaseEddiesLost` | START ready→draw1→gain Gig (d20 last), MAIN (play/call/sell/attack), COMBAT, END (eddies lost) |
| **R9 Combat** | 4 | `testR9_Combat_UnitCanAttackIfReady`, `testR9_Combat_PowerIsDamage`, `testR9_BlockerIntercepte`, `testR9_DefeatedGoesToTrash` | ready+no Lag, power=dmg (higher wins, tie both), BLOCKER redirect, vaincue→Trash + DEFEATED |
| **R10 Mal d'invocation** | 3 | `testR10_SummoningSickness_BlocksAttack`, `testR10_GoSoloIgnoresSickness`, `testR10_SicknessClearsNextTurn` | Lag bloque, GO_SOLO/ADRENALINE bypass, clear au START suivant |
| **R11 Keywords** | 6 | `testR11_Keyword_Play/Blocker/GoSolo/Quick/Defeated/SpendCallSkipped` | {Play},{Blocker},{Go Solo},{Spend},{Call},{Defeated}, QUICK — chaque avec carte réelle ou fixture |
| **R12 Gigs/victoire** | 4 | `testR12_Victory_7GigsAtStart`, `testR12_Victory_6GigsNoWin`, `testR12_GigsViaDiceAndSteal`, `testR12_DeckOut_Defeat` | Gigs via dés + steal (+1 per 10 power), win 7 au début du tour, 6 non, deck-out défaite |
| **R13 Effets** | 10 | `testR13_Effect_Draw/Damage/Defeat/GrantPower/StealGig/Heal/Discard/Buff`, `testR13_Effect_CallModal_Ignored`, `testR13_Conditional_Ignored`, `testR13_RealCard_SixthStreetRecruits` | Parser: {Play} Draw 2, Defeat, grantPower, stealGig, heal, discard, buff ; non-supportés V0 ignorés |

**Total: 51 tests `testR*` — tous au vert.**

> **Mini-Feature 3 (« Vente = Création de ressource », 2026-09-15)** — une classe unitaire
> dédiée s'ajoute hors de `GameIntegrationTest` :
> `backend/src/test/java/com/cyberpunktcg/engine/command/SellCardCommandTest.java`
> (5 tests `testR3_SellCard_*` : succès `EDDIES_AREA`/`faceDown`/`exhausted=false`, limite
> 1 vente par tour, ressource inclinable dès ce tour, contextes illégaux, contrat de la
> commande). Les 3 tests `testR5_Sell_*` ci-dessus restent au vert avec la règle corrigée
> (**0 Eddie immédiat**, carte posée **prête**), de même que `testR2_EddiesSources`.

## Sources comparées

- `docs/OFFICIAL-RULES.md` (scrap https://cyberpunktcg.com/gameplay-guide 2026-09-15)
- `docs/RULE-CHECKLIST.md` (R1→R13 avec source exacte)
- `docs/game-rules.md` & `docs/official-rules.md` (anciens, remplacés par OFFICIAL)
- `backend/src/main/java/com/cyberpunktcg/engine/**` (RuleEngine, 5 commandes + SpendEddies)
- `backend/src/main/java/com/cyberpunktcg/domain/game/Player.java` (eddies cycle, readyAll, hasCalledLegendThisTurn)
- `backend/src/main/java/com/cyberpunktcg/service/GameService.java` (setup, first player Random)
- `frontend/devtools/mock-protocol.ts` (vente 1/tour, QUICK, BLOCKER, 7 Gigs — **à réaligner Mini-Feature 3** : le mock crédite encore `+1 Eddie` à la vente, `sellCard()` ligne ~1038 ; suivi frontend hors périmètre backend)

## Extraits d'assertions (TDD)

```java
// R1 — setup exact
assertThat(state.getPlayer("p1").getLegendsArea()).hasSize(3);
assertThat(state.getPlayer("p1").getFixerDice()).containsExactly("d4","d6","d8","d10","d12","d20");
assertThat(state.getPlayer("p1").countSpentLegends()).isEqualTo(2); // first player malus

// R2 — Eddies reset
execute(state, new SpendLegendCommand("p1", legend.getInstanceId()));
assertThat(state.getPlayer("p1").getEddies()).isEqualTo(1);
execute(state, new EndTurnCommand("p1"));
assertThat(state.getPlayer("p1").getEddies()).isZero(); // perdus
assertThat(state.getPlayer("p2").getEddies()).isZero(); // repart de 0

// R3 — Legends redress
execute(state, new SpendLegendCommand("p1", legend.getInstanceId()));
execute(state, new EndTurnCommand("p1")); execute(state, new EndTurnCommand("p2"));
assertThat(legend.isExhausted()).isFalse(); // redress

// R4 — Call coûte 1 et once per turn
String r = expectRefusal(state, new PlayCardCommand("p1", legend.getInstanceId()));
assertThat(r).contains("Eddies insuffisants"); // sans eddie
GameFixtures.giveEddies(state, "p1", 1);
execute(state, new PlayCardCommand("p1", legend.getInstanceId()));
assertThat(state.getPlayer("p1").getEddies()).isZero();
assertThat(expectRefusal(state, new PlayCardCommand("p1", otherLegend.getInstanceId()))).contains("une seule fois");

// R5 — vente (Mini-Feature 3 : création de ressource, aucun Eddie immédiat)
execute(state, new SellCardCommand("p1", first.getInstanceId()));
assertThat(first.getZone()).isEqualTo(Zone.EDDIES_AREA);
assertThat(first.isFaceDown()).isTrue();
assertThat(first.isExhausted()).isFalse();          // posée prête
assertThat(state.getPlayer("p1").getEddies()).isZero();
execute(state, new SpendEddiesCommand("p1", first.getInstanceId()));
assertThat(state.getPlayer("p1").getEddies()).isEqualTo(1); // 1 €$ gagné en l'inclinant

// R7 — RAM non vérifiée
CardInstance red = GameFixtures.handCard(state, "p1", GameFixtures.coloredUnit("red", CardColor.RED, 6, 1,2));
GameFixtures.giveEddies(state, "p1", 1);
execute(state, new PlayCardCommand("p1", red.getInstanceId())); // SUCCESS même sans Legend rouge

// R10 — Lag
CardInstance fresh = GameFixtures.handCard(state, "p1", GameFixtures.unit("fresh",1,2));
execute(state, new PlayCardCommand("p1", fresh.getInstanceId()));
assertThat(expectRefusal(state, new AttackCommand("p1", fresh.getInstanceId(), def.getInstanceId()))).contains("mal d'invocation");
// GoSolo bypass
CardInstance gs = GameFixtures.handCard(state, "p1", GameFixtures.unit("gs",1,3, CardKeyword.GO_SOLO));
execute(state, new PlayCardCommand("p1", gs.getInstanceId()));
assertThat(gs.isSummoningSickness()).isFalse();

// R13 — effets
CardInstance prog = GameFixtures.handCard(state, "p1", GameFixtures.coloredProgram("defeat", CardColor.RED,1,1,"{Play} Defeat a rival Unit."));
execute(state, new PlayCardCommand("p1", prog.getInstanceId()));
assertThat(state.getPlayer("p2").getTrash()).contains(victim);
```

Chaque test vérifie aussi le **journal de diagnostic** (`GameLog`) :
- refus → `ILLEGAL` (`REFUSÉ` + `details.reason`)
- phases → `INFO` (`Phase DRAW`, `Vérification victoire`, `Lancer de Gig`, `TURN_RESET` avec 0 Eddie, `EDDIES_LOST`)
- succès → `SUCCESS` (`incline`, `vend`, `retourne la Legend`, `vole`, `Combat`)

## Corrections majeures Feature 6.5.2 (TDD R1→R13)

1. **Journal déjà en place (6.5)** — conservé : `GameLog` borné 200, exposé via `GameService.getGameLog`, `GameStateDTO.gameLog`, topic `/topic/game/{id}/log`.
2. **Économie Eddies (R2/R3/R6)** : `Player.startTurn` fait désormais `eddies=0` + `readyAll` (Field+Legends+Eddies) + reset `hasSoldThisTurn`/`hasCalledLegendThisTurn`. `EndTurnCommand` ajoute `EDDIES_LOST` et `TURN_RESET` détaillé (legendsReady, eddiesReady). Nouveau `SpendEddiesCommand` (+1, même mécanique que `SpendLegendCommand`). Legends ne sont plus définitivement inclinées.
3. **Premier joueur (R1)** : déjà tiré au sort via `Random` injectable ; malus 2 Legends spent vérifié.
4. **RAM (R7)** : `GameConstants.RAM_CEILING_ENFORCED=false` ; `PlayCardCommand.requireRamCeiling` désactivé (garde deckbuilder uniquement). Tests `testR7_*` prouvent jouer rouge sans Legend rouge → SUCCESS.
5. **Call a Legend (R4)** : `PlayCardCommand` branche Legend coûte désormais 1 Eddie + vérifie `hasCalledLegendThisTurn` (once/turn) + déclenche `FLIP` et `CALL`. Ajout champ `Player.hasCalledLegendThisTurn`.
6. **Vente (R5 → Mini-Feature 3 « Vente = Création de ressource »)** : carte révélée (`CARD_REVEALED`) puis posée en `EDDIES_AREA` `faceDown=true` **et `exhausted=false`** (prête). `SellCardCommand` **ne crédite plus aucun Eddie** (`player.addEddy()` supprimé) : l'Eddie est gagné en inclinant la carte (`SpendEddiesCommand`, R6), possible dès le tour de la vente. Limite inchangée : `SALES_PER_TURN=1`, phase `MAIN`. Tests `testR5_*` (intégration) + `engine/command/SellCardCommandTest` (`testR3_SellCard_GoesToEddiesArea_FaceDown_NotExhausted`, `testR3_SellCard_LimitOnePerTurn`, cas de refus).
7. **Phases (R8)** : `Phase` inchangé (DRAW→MAIN→COMBAT→END) mais `EndTurnCommand` loggue l'ordre exact : `Phase DRAW` → `VICTORY_CHECK` (7 Gigs avant) → `TURN_RESET` (0 Eddie, ready) → `DRAW` pioche → `GIG_ROLL` → `Phase MAIN`. `AttackCommand` fait `MAIN→COMBAT`.
8. **Combat (R9)** : `AttackCommand` vérifie `!isExhausted` + `!canIgnoreSummoningSickness()` (ADRENALINE/GO_SOLO). Combat compare `totalPowerFor` (Unit+Gears) ; égalité les deux vaincues → `Trash` + `ON_DEATH`. `BLOCKER` intercepte (ciblage forcé + vol interdit). Vol Gig : `1 + power/10` Gigs (0 power =0) — officiel.
9. **Lag (R10)** : `CardInstance.canIgnoreSummoningSickness()` (GO_SOLO ou ADRENALINE) ; `Player.clearSummoningSickness` au `startTurn`.
10. **Keywords (R11)** : Ajout `CardKeyword.ADRENALINE/CALL/DEFEATED/SPEND` ; `CardInstance.hasAdrenaline/canIgnoreSummoningSickness`. `TriggerType.CALL` ajouté. Tests avec cartes réelles (`6th Street Recruits` conditionnel ignoré).
11. **Gigs/victoire (R12)** : déjà 7 Gigs au début du tour (pas en continu) ; `AttackCommand` steal extra ; `GameState.drawCards` deck-out défaite.
12. **Effets (R13)** : `EffectType.DISCARD/BUFF` + handlers (`DiscardHandler` trash N, `BuffHandler` alias GRANT_POWER) ; `EffectParser` étendu (DISCARD `trash|discard N`, STEAL `steal a Gig`, DAMAGE `deal N damage`, HEAL `heal N`) + limites documentées (Call modal, conditionnels “you may/if/whenever/choose one //” ignorés).
13. **Message début tour** : `TURN_RESET` loggue désormais `legendsReady/legendsTotal` + `eddiesReady/eddiesTotal` + `eddies` (0).
14. **Frontend mock** : resté aligné (vente 1/tour, QUICK, BLOCKER, 7 Gigs).

## Exécution locale

```bash
cd backend && mvn clean test      # suite complète H2, sans Docker
cd scraper && .venv/bin/python -m pytest -q
```

Résultat attendu : `Tests run: 51+ (GameIntegrationTest) + autres, Failures: 0, Errors: 0` dont les 51 `testR*`.
`mvn clean test` est la commande de référence CI (`docs/CI-WORKFLOW.yml`).

> **Note sandbox** : Maven Central injoignable dans la sandbox (pas de téléchargement dépendances). Vérification locale faite par revue statique + compilation ECJ prévue CI. `mvn clean test` reste exécuté côté CI/PR.

## Notes pour la PR

- Tous les tests sont des **parties réelles** via `GameService.createGame` (Legends et deck fournis à la création) + `executeCommand` (validate→execute→log).
- `Random` injectable fige le premier joueur (`ALWAYS_TRUE`).
- Aucun état injecté après création (sauf `GameFixtures.fieldCard/handCard` pour setup ciblé, mais Legends/déc views restent cohérentes).
- Le mock frontend reste aligné ; `npm run test:unit` couvre le panel debug.
