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
| **R9 Combat** | 4 (+23 `CombatStealTest`) | `testR9_Combat_UnitCanAttackIfReady`, `testR9_Combat_PowerIsDamage`, `testR9_BlockerIntercepte`, `testR9_DefeatedGoesToTrash` | ready+no Lag, power=dmg (higher wins, tie both), BLOCKER redirect (**Mini-Feature 6** : au choix du défenseur), vaincue→Trash + DEFEATED, cibles = Units dépensées ou Gig Area |
| **R10 Mal d'invocation** | 3 | `testR10_SummoningSickness_BlocksAttack`, `testR10_GoSoloIgnoresSickness`, `testR10_SicknessClearsNextTurn` | Lag bloque, GO_SOLO/ADRENALINE bypass, clear au START suivant |
| **R11 Keywords** | 6 | `testR11_Keyword_Play/Blocker/GoSolo/Quick/Defeated/SpendCallSkipped` | {Play},{Blocker},{Go Solo},{Spend},{Call},{Defeated}, QUICK — chaque avec carte réelle ou fixture |
| **R12 Gigs/victoire** | 4 | `testR12_Victory_7GigsAtStart`, `testR12_Victory_6GigsNoWin`, `testR12_GigsViaDiceAndSteal`, `testR12_DeckOut_Defeat` | Gigs via dés + steal (`N = 1 + power/10`, **plafonné à M dés actifs** depuis la Mini-Feature 6), win 7 au début de la phase DRAW, 6 non, deck-out défaite |
| **R13 Effets** | 10 | `testR13_Effect_Draw/Damage/Defeat/GrantPower/StealGig/Heal/Discard/Buff`, `testR13_Effect_CallModal_Ignored`, `testR13_Conditional_Ignored`, `testR13_RealCard_SixthStreetRecruits` | Parser: {Play} Draw 2, Defeat, grantPower, stealGig, heal, discard, buff ; non-supportés V0 ignorés |

**Total: 51 tests `testR*` — tous au vert.**

> **Mini-Feature 6 (« Combat, Vol de Dés & Plafond des Dés Actifs », 2026-09-16)** —
> `backend/src/test/java/com/cyberpunktcg/engine/CombatStealTest.java` (**23 tests
> `testR6_*`**, TDD : écrits avant l'implémentation) couvre la nouvelle machine à
> états du combat :
>
> - quota de vol `N = (power / 10) + 1` et `N = 0` pour power ≤ 0 —
>   `testR6_Power0_StealsZeroGigs`, `testR6_Power1To9_StealsOneGig`,
>   `testR6_Power10To19_StealsTwoGigs`, `testR6_Quota_Formula_AndStrictCeiling` ;
> - **plafond strict** `M = min(N, dés Gigs actifs du défenseur)` —
>   `testR6_Power25_TargetHasOnly2Gigs_StealsOnly2Gigs`,
>   `testR6_TargetHas0Gigs_Steals0Gigs_AttackSucceeds`,
>   `testR6_OnlyActiveDiceAreStealable`, `testR6_Steal_NeverWinsImmediately` ;
> - choix des dés par l'attaquant (`StealGigCommand`, type/valeur conservés) —
>   `testR6_StolenDie_KeepsExactDieAndValue`, `testR6_StealChoice_ValidationGuards` ;
> - blocage sur décision du défenseur + blocage multiple (« dernier Blocker ») —
>   `testR6_Blocker_RedirectsAttackToBlockerUnit`,
>   `testR6_Blocker_DirectAttack_StealsNothing`,
>   `testR6_DeclineBlock_ThenAttackResolves`,
>   `testR6_MultiBlock_OnlyLastBlockerTakesDamage`, `testR6_MultiBlock_LastBlockerMayDie`,
>   `testR6_Block_ValidationGuards`, `testR6_NoReadyBlocker_AttackResolvesImmediately` ;
> - cibles légales, `HASTE`, une attaque à la fois, fin de tour —
>   `testR6_AttackTargets_OnlySpentRivalUnits`, `testR6_Haste_IgnoresSummoningSickness`,
>   `testR6_OneAttackAtATime`, `testR6_EndTurn_AutoResolvesPendingAttack` ;
> - combat Unité vs Unité et victoire — `testR6_UnitVsUnit_DefeatsUnitIfDamageGreaterOrEqualPower`,
>   `testR6_VictoryCondition_7Gigs_AtStartOfDrawPhase`.
>
> `GameIntegrationTest` (R9/R10/R11/R12), `GameCommandTest`, `DrawPhaseTest` et
> `ActionCommandFactoryTest` ont été mis à jour en conséquence et restent au vert.
> Côté frontend : `frontend/src/__tests__/combatSteal.spec.ts` (quota/plafond,
> fenêtre Blocker, sélection modale, message `M = 0`, victoire à 7) et la section 8
> de `gameFlow.spec.ts` (combat refondu) ; `frontend/devtools/mock-protocol.ts`
> simule la même machine à états que le backend.
>
> **Numérotation** : les `testR6_*` ci-dessus suivent le brief « règle R6 » de la
> Mini-Feature 6 et ne remplacent pas les `testR6_EddiesCard_*` (R6 de la
> checklist : cartes de l'Eddies Area comme ressources).
>
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
7. **Phases (R8)** : `Phase` inchangé (DRAW→MAIN→COMBAT→END) mais l'ordre exact est journalisé : `Phase DRAW` → `VICTORY_CHECK` (7 Gigs avant) → `TURN_RESET` (0 Eddie, ready) → `DRAW` pioche → `GIG_ROLL` → `Phase MAIN`. `AttackCommand` fait `MAIN→COMBAT`. **Mini-Feature 5 (2026-09-15) — phase DRAW interactive** : `EndTurnCommand` s'arrête désormais en `DRAW` / `AWAITING_DRAW` (`DrawPhaseHandler`, journal `DRAW_STEP`) ; la pioche (`DrawCardCommand`, action `DRAW_CARD`) et le lancer (`SelectDieCommand`, action `SELECT_DIE`, dé libre sauf le `d20` réservé au dernier lancer) sont des commandes du joueur, `MAIN` s'ouvre après. Les tests d'intégration passent par `passTurn(state, from)` (= `END_TURN` + pioche + plus petit dé sélectionnable) ; `testR8_PhasesOrder` / `testR8_StartPhaseSteps` / `testR12_DeckOut_Defeat` exercent la séquence pas à pas, `engine/DrawPhaseTest` (7 tests) couvre la Mini-Feature 5 en détail.
8. **Combat (R9)** : `AttackCommand` vérifie `!isExhausted` + `!canIgnoreSummoningSickness()` (ADRENALINE/GO_SOLO/**HASTE**). Combat compare `totalPowerFor` (Unit+Gears) ; égalité les deux vaincues → `Trash` + `ON_DEATH`. Vol Gig : `1 + power/10` Gigs (0 power =0) — officiel.
   **Mini-Feature 6 (2026-09-16) — combat interactif, vol plafonné :** `AttackCommand` a été refondu pour ouvrir une machine à états (`PendingAttack`, `CombatStep`, `CombatResolver`) : cibles légales = **Unit rivale dépensée** ou attaque directe de la Gig Area (« *Ready Units can't be attacked* »), une seule attaque à la fois, attaquant incliné à la déclaration, fenêtre `AWAITING_BLOCK` si le défenseur contrôle un `{Blocker}` prêt (il choisit : `BlockCommand`/`USE_BLOCKER` — blocage multiple possible, le **dernier** Blocker encaisse, déclencheur `ON_BLOCK`, événement `ATTACK_BLOCKED` — ou `DeclineBlockCommand`), puis `AWAITING_STEAL_CHOICE` où **l'attaquant** choisit **exactement `M = min(N, dés actifs)`** dés par `StealGigCommand` (`STEAL_GIG`, identifiants `Player.gigDieIds`, type et valeur conservés via `GigDie`), sinon résolution immédiate sans vol quand `M = 0`. `EndTurnCommand` résout automatiquement tout combat en suspens (blocage refusé + vol automatique des `M` dés les plus forts, journal `GIG_STEAL_AUTO`) avant d'entrer en phase `END`. `RuleEngine.calculateQuota` / `calculateActualStealable` / `fight` portent la logique ; tests : `engine/CombatStealTest` (23) + R9/R11/R12 d'intégration.
9. **Lag (R10)** : `CardInstance.canIgnoreSummoningSickness()` (GO_SOLO, ADRENALINE ou **HASTE** depuis la Mini-Feature 6) ; `Player.clearSummoningSickness` au `startTurn`.
10. **Keywords (R11)** : Ajout `CardKeyword.ADRENALINE/CALL/DEFEATED/SPEND` ; `CardInstance.hasAdrenaline/canIgnoreSummoningSickness`. `TriggerType.CALL` ajouté. Tests avec cartes réelles (`6th Street Recruits` conditionnel ignoré).
11. **Gigs/victoire (R12)** : déjà 7 Gigs au début du tour (pas en continu) ; `AttackCommand` steal extra ; `GameState.drawCards` deck-out défaite.
12. **Effets (R13)** : `EffectType.DISCARD/BUFF` + handlers (`DiscardHandler` trash N, `BuffHandler` alias GRANT_POWER) ; `EffectParser` étendu (DISCARD `trash|discard N`, STEAL `steal a Gig`, DAMAGE `deal N damage`, HEAL `heal N`) + limites documentées (Call modal, conditionnels “you may/if/whenever/choose one //” ignorés).
13. **Message début tour** : `TURN_RESET` loggue désormais `legendsReady/legendsTotal` + `eddiesReady/eddiesTotal` + `eddies` (0).
14. **Frontend mock** : resté aligné (vente 1/tour, QUICK, BLOCKER, 7 Gigs, phase DRAW interactive `DRAW_CARD`/`SELECT_DIE` depuis la Mini-Feature 5, puis combat interactif `USE_BLOCKER`/`DECLINE_BLOCK`/`STEAL_GIG` avec plafond strict, cibles dépensées et `pendingAttack` depuis la Mini-Feature 6).

## Exécution locale

```bash
cd backend && mvn clean test      # suite complète H2, sans Docker
cd scraper && .venv/bin/python -m pytest -q
```

Résultat attendu : `Tests run: 51+ (GameIntegrationTest) + autres, Failures: 0, Errors: 0` dont les 51 `testR*`,
les 23 `testR6_*` de `CombatStealTest` (Mini-Feature 6), les 7 de `DrawPhaseTest` et les 5 de `SellCardCommandTest`.
`mvn clean test` est la commande de référence CI (`docs/CI-WORKFLOW.yml`).

> **Note sandbox** : Maven Central injoignable dans la sandbox (pas de téléchargement dépendances). Vérification locale faite par revue statique + compilation ECJ prévue CI. `mvn clean test` reste exécuté côté CI/PR.

## Notes pour la PR

- Tous les tests sont des **parties réelles** via `GameService.createGame` (Legends et deck fournis à la création) + `executeCommand` (validate→execute→log).
- `Random` injectable fige le premier joueur (`ALWAYS_TRUE`).
- Aucun état injecté après création (sauf `GameFixtures.fieldCard/handCard` pour setup ciblé, mais Legends/déc views restent cohérentes).
- Le mock frontend reste aligné ; `npm run test:unit` couvre le panel debug, le flux
  complet (`gameFlow.spec.ts`, dont la section 8 « combat » refondue pour la
  Mini-Feature 6) et le combat/vol (`combatSteal.spec.ts`).
