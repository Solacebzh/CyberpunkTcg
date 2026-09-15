# Tests d'intégration — Feature 6.5 (Debug, Logging & Correction des Règles)

Branche : `arena/01a0a43d-cyberpunktcg`
Date : 2026-09-15

Ce document décrit les **7 tests d'intégration critiques** demandés par la
feature 6.5 : chacun joue une partie réelle à travers `GameService` (validation →
exécution → journal de diagnostic) et vérifie une règle confirmée.
Complément : `docs/DEBUG-GUIDE.md` (journal et endpoints de debug) et
`docs/RULE-ENGINE.md` (traçabilité règle → code).

Fichier : `backend/src/test/java/com/cyberpunktcg/engine/GameIntegrationTest.java`.

## Sources comparées

- `docs/official-rules.md` (règles officielles scrappées, **référence**)
- `docs/game-rules.md` (règles retenues côté projet)
- `backend/src/main/java/com/cyberpunktcg/engine/**` (`RuleEngine`, commandes)
- `backend/src/main/java/com/cyberpunktcg/service/GameService.java`
- `frontend/devtools/mock-protocol.ts` (réplique cliente du protocole)

## Les 7 tests

| # | Test | Scénario joué | Règle vérifiée |
|---|---|---|---|
| 1 | `testFullGameFlow` | création (Legends en zone, main de 6), inclinaison d'une Legend → 1 Eddie, 2 fins de tour (DRAW → pioche → lancer de Gig), attaque directe de la Gig Area | partie complète : phases, économie des Legends, pioche, Gig, vol |
| 2 | `testPlayCardCostValidation` | pose d'une Unit à 4 Eddies sans ressource puis avec, plafonds de RAM par couleur, 3 poses à la RAM maximale | coûts en Eddies + RAM = plafond de deck (jamais consommée en partie) |
| 3 | `testSellCardLimit` | vente d'une carte (révélée puis face cachée, +1 Eddie), seconde vente refusée, réinitialisation au tour suivant | 1 seule vente par tour |
| 4 | `testCombatWithBlocker` | BLOCKER prêt : refus d'une autre cible et du vol de Gig ; combat perdu (4 vs 5) puis gagné (6 vs 5) | BLOCKER intercepte ; puissance = dégâts ; vaincue → défausse |
| 5 | `testVictoryCondition` | p1 à 6 Gigs, tours alternés, lancer de Gig qui passe à 7 | victoire à 7 Gigs **au début** du tour, jamais pendant |
| 6 | `testLegendFlip` | Legend face cachée retournée sans aucun Eddie (effet `FLIP:DRAW:2`), seconde tentative | FLIP gratuit, déclencheur `FLIP` (pas `ON_PLAY`) |
| 7 | `testQuickReaction` | attaque de p1 → fenêtre du défenseur ; Program lent refusé, Program `QUICK` accepté hors tour ; fin de tour | réactions `QUICK` uniquement, fenêtre fermée en fin de tour |

### Assertions clés (extraits)

```java
// 1 — économie : la Legend inclinée ne se redresse jamais
execute(state, new SpendLegendCommand("p1", thirdLegend.getInstanceId()));
assertThat(state.getPlayer("p1").getEddies()).isEqualTo(1);
execute(state, new EndTurnCommand("p1"));
execute(state, new EndTurnCommand("p2"));
assertThat(thirdLegend.isExhausted()).isTrue();

// 2 — RAM : plafond = somme des RAM des Legends de la couleur
assertThat(player.ramCeilingFor(CardColor.BLUE)).isEqualTo(4);
assertThat(expectRefusal(state, new PlayCardCommand("p1", tooBig.getInstanceId())))
        .contains("RAM rouge");

// 4 — BLOCKER : ni une autre Unit, ni la Gig Area
assertThat(expectRefusal(state, new AttackCommand("p1", attacker.getInstanceId())))
        .contains("intercepté");

// 5 — victoire au début du tour : p1 à 6 Gigs ne gagne pas pendant le tour de p2
execute(state, new EndTurnCommand("p2"));
assertThat(state.getWinnerId()).isEqualTo("p1");

// 7 — hors fenêtre/timing : un Program sans QUICK est refusé au défenseur
assertThat(expectRefusal(state, new PlayCardCommand("p2", slowProgram.getInstanceId())))
        .contains("QUICK");
```

Chaque test vérifie en plus que le **journal de diagnostic** contient la trace
attendue : les refus produisent une entrée `ILLEGAL` (`REFUSÉ` + `details.reason`),
les phases une entrée `INFO` (`Phase DRAW`, `Vérification victoire`, `Lancer de Gig`),
les succès une entrée `SUCCESS` (`incline`, `vole un Gig`, `retourne la Legend`).

## Corrections apportées (lecture statique → code)

### Feature 6.5

1. **Journal de diagnostic** : le moteur n'exposait que `GameEvent` (récit public) ;
   aucune trace des refus. Ajout de `GameLog`/`GameLogEntry`/`GameActionResult`,
   consignés par `GameState` (bornés à 200 entrées), exposés via
   `GameService.getGameLog`, `GameStateDTO.gameLog` et le topic
   `/topic/game/{id}/log` (incrémental, `GameBroadcaster.lastLogIndex`).
2. **Économie des Eddies** : la seule source d'Eddies était la vente. Règle
   officielle : les Legends face cachée sont la réserve ; ajout de
   `SpendLegendCommand` (+1 Eddie, inclinaison définitive,
   `GameConstants.EDDIES_PER_LEGEND`) et du malus de mise en place
   (`FIRST_PLAYER_SPENT_LEGENDS = 2`).
3. **Premier joueur** : il était toujours `p1`. Désormais tiré au sort dans
   `GameService.createGame` (`Random` injectable pour les tests) ; le premier
   joueur commence avec 2 Legends inclinées.
4. **RAM** : elle n'était ni portée par les instances ni contrôlée. Ajout de
   `CardInstance.ram` (snapshot, masqué pour l'adversaire), de
   `Player.ramCeilingFor(color)` / `hasLegendCeiling()` et du contrôle
   `PlayCardCommand.requireRamCeiling` — la RAM n'est **pas** consommée.
5. **Main de départ** : 6 cartes (`STARTING_HAND_SIZE`, règle officielle) au lieu
   de 5 dans le brief.
6. **Défaite par deck vide** : `GameState.drawCards` désigne le vainqueur quand la
   pioche est impossible (règle officielle §7), au lieu de laisser la partie
   continuer silencieusement.
7. **Vente** : la carte est **révélée** (`CARD_REVEALED`) avant de rejoindre
   l'Eddies Area face cachée, conformément à la règle « montrer la carte à
   l'adversaire ».
8. **Fin de tour** : la fenêtre de réaction non utilisée est fermée (le
   défenseur ne peut plus réagir après `EndTurnCommand`) ; la victoire est
   vérifiée au début du tour ; la pioche impossible est journalisée.
9. **Effets** : `EffectParser` interprète désormais des motifs non ambigus des
   textes du catalogue (`defeat a rival Unit`, `give a friendly Unit +N power`,
   `increase/decrease a Gig`, `draw N`) avec les cibles génériques
   `FRIENDLY_UNIT` / `RIVAL_UNIT` et les effets `DEFEAT_UNIT`, `BOOST_GIG`,
   `REDUCE_GIG` (voir `RULE-ENGINE.md` §5.2).
10. **Message de début de tour** : le journal annonçait « Cartes redressées
    (Units + Legends) » alors que seuls le Field et les mals d'invocation sont
    réinitialisés (`Player.startTurn`) : libellé corrigé.

### Héritage feature 5.5 (déjà corrigé, vérifié à nouveau ici)

| Point | Statut |
|---|---|
| Victoire 7 Gigs au début du tour (pas en continu) | OK — `EndTurnCommand`, test 5 |
| Legend : `FLIP` (pas `ON_PLAY`), sans coût Eddies | OK — `PlayCardCommand`, test 6 |
| Vente : 1/tour maximum | OK — `SALES_PER_TURN`, test 3 |
| Réactions : `QUICK` uniquement | OK — fenêtre + validation, test 7 |
| BLOCKER intercepte | OK — `AttackCommand.validate`, test 4 |
| Power = dégâts | OK — `getEffectivePower()` / `isLethalDamage()`, test 4 |

## Exécution locale

```bash
cd backend && mvn clean test      # suite complète (profils test, H2, aucun Docker)
```

Résultat attendu : `Tests run: … Failures: 0, Errors: 0, Skipped: 0`, dont les 7
tests de `GameIntegrationTest`.

> **Note environnement de développement de l'agent** : Maven Central et les
> miroirs Maven sont injoignables dans la sandbox (aucun téléchargement de
> dépendance possible). La suite a donc été compilée avec le compilateur Eclipse
> (ECJ 3.x, `-source/-target 17 -parameters`) puis exécutée avec le lanceur JUnit
> 5 « console » sur le classpath local, ce qui couvre exactement les mêmes
> classes de test que Surefire. Dernier résultat obtenu :
> `tests=… ok=… failed=0` (voir la PR). `mvn clean test` reste la commande de
> référence à lancer côté développeur/CI.

## Notes pour la PR

- Les 7 tests sont des **parties réelles** : aucune injection d'état après
  `createGame` (Legends et deck sont fournis à la création via
  `GameService.createGame`), sinon `legendsArea`/`deck` restaient incohérents.
- Le `Random` est injecté dans `GameService` : les tests figent `nextBoolean()`
  pour rendre le premier joueur déterministe, la CI laisse le tirage réel.
- Le mock frontend (`frontend/devtools/mock-protocol.ts`) reste aligné : vente
  unique, réactions `QUICK`, BLOCKER, victoire à 7 Gigs, masquage de la main
  adverse.
- `npm run test:unit` (frontend) couvre le panneau de debug
  (`src/__tests__/debugPanel.spec.ts`) et le flux de jeu.
