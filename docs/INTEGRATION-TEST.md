# Tests d'intégration — Feature 5.5

Branche : `arena/01a0a20a-cyberpunktcg`
Date : 2026-09-14

## Sources comparées

- `docs/game-rules.md` (règles retenues, confirmées côté moteur)
- `scraper/output/official-rules.md` (règles scrappées depuis `https://cyberpunktcg.com`)
- `backend/src/main/java/com/cyberpunktcg/engine/RuleEngine.java`
- `backend/src/main/java/com/cyberpunktcg/engine/command/*.java`
- `frontend/tools/mock-server.mjs` et `frontend/devtools/mock-protocol.ts`

## Divergences et bugs trouvés (lecture statique)

### a) Victoire — 7 Gigs au DÉBUT du tour (pas en continu)

**Status** : OK dans `EndTurnCommand` (vérifie `incoming.getGigCount() >= 7` avant pioche / lancer).
**Correction / commentaire** : augmenté le Javadoc de `EndTurnCommand.execute()` pour préciser que la victoire est évaluée uniquement au début du tour du joueur entrant, jamais après un vol de Gig ou un effet de combat.
**Reste à tester en local** : vérifier que `GameState` n'a pas d'autre point de contrôle de victoire caché (recherche `GIGS_TO_WIN` → seule occurrence dans `EndTurnCommand`).

### b) Legends — FLIP (pas ON_PLAY), pas de coût Eddies

**Status** : `PlayCardCommand.execute()` résout `TriggerType.FLIP` (ligne 131) et ne dépense pas d'Eddies. `validate()` exige toutefois `Street Cred` — cela correspond au seuil d'accès du Legend, pas au coût en Eddies.
**Correction / commentaire** : ajouté Javadoc explicite dans `PlayCardCommand` : « Legend retournée : effet FLIP, aucun coût Eddies (street cred requis selon seuil imprimé) ».
**Mock-server** : `mock-protocol.ts` (lignes 823-828) retourne la Legend sans résoudre d'effets FLIP ; corrigé pour appeler la résolution FLIP (simulé par comment + event).

### c) Vente — 1/tour max

**Status** : `SellCardCommand.validate()` vérifie `player.hasSoldThisTurn()` ; `execute()` met `setHasSoldThisTurn(true)`. `Player.startTurn()` réinitialise à `false`.
**Correction / commentaire** : Javadoc ajouté dans `SellCardCommand` confirmant la limite `SALES_PER_TURN = 1`.

### d) Réactions — QUICK uniquement

**Status** : `PlayCardCommand.validate()` rejette si `reacting && !card.isQuick()` (ligne 89-91). `AttackCommand.execute()` ouvre la fenêtre avec la mention `QUICK uniquement` (ligne 915).
**Correction / commentaire** : Javadoc renforcé dans `PlayCardCommand` et `AttackCommand`.

### e) BLOCKER intercepte

**Status** : `AttackCommand.validate()` vérifie `rival.controlsReadyBlocker()` : si un BLOCKER prêt existe, toute attaque directe vers la Gig Area est interdite (ligne 117-118) et toute attaque vers une autre Unit doit cibler le BLOCKER (ligne 146-148).
**Correction / commentaire** : ajouté commentaire dans `AttackCommand.validate()` clarifiant que le BLOCKER doit être ciblé en priorité (interception).

### f) Power = dégâts

**Status** : `CardInstance.getEffectivePower()` = `max(0, base + bonus − dégâts)`. `RuleEngine.defeatUnit()` compare avec `isLethalDamage()` (`dégâts ≥ base + bonus`). `AttackCommand` utilise `state.totalPowerFor()` qui intègre `getEffectivePowerOrZero()` + Gears.
**Correction / commentaire** : Javadoc dans `RuleEngine.DamageHandler` précisant que `DAMAGE` réduit la puissance effective et que `isLethalDamage()` déclenche la défaite.

## Ce qui reste à tester en local (par le développeur)

1. `cd backend && mvn clean test` (29+ tests du moteur + service)
2. `cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=test`
3. `cd frontend && npm run dev`
4. Tester le flux complet : Lobby → Partie → Jouer → Attaquer → Fin tour
5. Vérifier que les images de cartes s'affichent (`/images/cards/*.png` servis par Spring Boot)
6. Vérifier le playmat intégré dans `GameView.vue`
7. Tester les règles corrigées : vente 1/tour, réaction QUICK, BLOCKER, victoire 7 Gigs au début du tour, Legends FLIP sans Eddies

## Notes pour la PR

- Le backend Spring ne peut pas être lancé dans cet environnement (pas de JDK / Maven / Docker). Tous les tests d'intégration réels sont reportés au développeur local et aux Actions CI.
- Le mock-server (`frontend/tools/mock-server.mjs`) a été aligné sur les règles corrigées (phases, vente unique, QUICK, BLOCKER, victoire 7 Gigs, masquage main adverse).
- `npm run build` et `npm run test:unit` doivent être vérifiés par le développeur ; dans cet environnement nous n'avons pas lancé `mvn test`.
