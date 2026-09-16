package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.CombatStep;
import com.cyberpunktcg.domain.game.GigDie;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.BlockCommand;
import com.cyberpunktcg.engine.command.DeclineBlockCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.StealGigCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mini-Feature 6 — <strong>Combat, Vol de Dés &amp; Plafond des Dés Actifs</strong>.
 *
 * <p>Règles couvertes (cf. {@code docs/OFFICIAL-RULES.md} § ATTACKING et
 * {@code docs/RULE-CHECKLIST.md} R6/R9/R12) :</p>
 * <ul>
 *   <li><strong>quota théorique N</strong> : {@code power <= 0 → 0}, sinon
 *   {@code N = (power / 10) + 1} ;</li>
 *   <li><strong>plafond strict M</strong> : {@code M = min(N, dés Gigs ACTIFS du
 *   défenseur)} — on ne vole jamais un dé de la Fixer Area (non lancé) et on ne
 *   crée jamais de dé ;</li>
 *   <li><strong>choix de l'attaquant</strong> : les M dés volés sont ceux qu'il
 *   désigne ({@code StealGigCommand}), chaque dé conservant son type et sa
 *   valeur exacte ;</li>
 *   <li><strong>BLOCKER au choix du défenseur</strong> : fenêtre « Utiliser
 *   Blocker ? », blocage multiple (tous inclinés, compétences résolues, seul le
 *   DERNIER bloqueur encaisse les dégâts), aucun Gig volé quand l'attaque est
 *   redirigée ;</li>
 *   <li><strong>combat Unité vs Unité</strong> : dégâts = puissance de
 *   l'attaquant, cible vaincue si dégâts ≥ sa puissance (égalité officielle :
 *   les deux Units tombent) ;</li>
 *   <li><strong>victoire</strong> : 7 dés Gigs ou plus vérifiés AU DÉBUT de la
 *   phase DRAW, jamais en continu après un vol.</li>
 * </ul>
 *
 * <p>Tests purs (aucun Spring) : les duels viennent de {@link GameFixtures}.</p>
 */
class CombatStealTest {

    private GameState state;
    private final RuleEngine engine = new RuleEngine();

    @BeforeEach
    void setUp() {
        state = GameFixtures.freshDuel();
    }

    // ------------------------------------------------------------------
    // C. Vol de Gigs — quota théorique N et plafond strict M
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R6 - Power 0 → 0 Gig volé (quota nul, attaque quand même résolue)")
    void testR6_Power0_StealsZeroGigs() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("nullifier", 1, 0));
        GameFixtures.addGigs(state, "p2", 4, 6);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);

        assertThat(engine.calculateQuota(0)).isZero();
        assertThat(engine.calculateActualStealable(0, 2)).isZero();
        // L'attaque a bien eu lieu (Unit inclinée, phase Combat) mais rien n'est volé.
        assertThat(attacker.isExhausted()).isTrue();
        assertThat(state.getPhase()).isEqualTo(Phase.COMBAT);
        assertThat(state.getPlayer("p2").getGigs()).containsExactly(4, 6);
        assertThat(state.getPlayer("p1").getGigCount()).isZero();
        // Aucun choix de dé attendu : M = 0.
        assertThat(state.isAwaitingStealChoice()).isFalse();
        assertThat(state.isCombatPending()).isFalse();
    }

    @Test
    @DisplayName("R6 - Power 1 à 9 → 1 Gig volé (au choix de l'attaquant)")
    void testR6_Power1To9_StealsOneGig() {
        for (int power : new int[]{1, 5, 9}) {
            GameState duel = GameFixtures.freshDuel();
            CardInstance attacker = GameFixtures.fieldCard(duel, "p1",
                    GameFixtures.unit("runner-" + power, 1, power));
            GameFixtures.addGigs(duel, "p2", 2, 3, 4);

            new AttackCommand("p1", attacker.getInstanceId()).execute(duel);

            assertThat(engine.calculateQuota(power)).isEqualTo(1);
            assertThat(duel.isAwaitingStealChoice()).isTrue();
            assertThat(duel.getPendingAttack().getQuota()).isEqualTo(1);
            assertThat(duel.getPendingAttack().getStealable()).isEqualTo(1);

            // L'attaquant choisit LE dé qu'il veut (ici celui qui affiche 3).
            new StealGigCommand("p1", Collections.singletonList(dieIdWithValue(duel, "p2", 3))).execute(duel);

            assertThat(duel.getPlayer("p1").getGigs()).containsExactly(3);
            assertThat(duel.getPlayer("p2").getGigs()).containsExactly(2, 4);
            assertThat(duel.isCombatPending()).isFalse();
        }
    }

    @Test
    @DisplayName("R6 - Power 10 à 19 → 2 Gigs volés")
    void testR6_Power10To19_StealsTwoGigs() {
        for (int power : new int[]{10, 14, 19}) {
            GameState duel = GameFixtures.freshDuel();
            CardInstance attacker = GameFixtures.fieldCard(duel, "p1",
                    GameFixtures.unit("breaker-" + power, 1, power));
            GameFixtures.addGigs(duel, "p2", 1, 2, 3, 4);

            new AttackCommand("p1", attacker.getInstanceId()).execute(duel);

            assertThat(engine.calculateQuota(power)).isEqualTo(2);
            assertThat(duel.getPendingAttack().getStealable()).isEqualTo(2);

            List<String> chosen = new ArrayList<String>();
            chosen.add(dieIdWithValue(duel, "p2", 4));
            chosen.add(dieIdWithValue(duel, "p2", 1));
            new StealGigCommand("p1", chosen).execute(duel);

            assertThat(duel.getPlayer("p1").getGigs()).containsExactly(4, 1);
            assertThat(duel.getPlayer("p2").getGigs()).containsExactly(2, 3);
        }
    }

    @Test
    @DisplayName("R6 - PLAFOND STRICT : power 25 (N = 3) mais 2 dés actifs → 2 dés volés")
    void testR6_Power25_TargetHasOnly2Gigs_StealsOnly2Gigs() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("titan", 1, 25));
        GameFixtures.addGigs(state, "p2", 7, 2);

        // Formule : N = (25 / 10) + 1 = 3, plafonné aux 2 dés actifs du défenseur.
        assertThat(engine.calculateQuota(25)).isEqualTo(3);
        assertThat(engine.calculateActualStealable(25, 2)).isEqualTo(2);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);

        assertThat(state.isAwaitingStealChoice()).isTrue();
        assertThat(state.getPendingAttack().getQuota()).isEqualTo(3);
        assertThat(state.getPendingAttack().getStealable()).isEqualTo(2);

        // Demander 3 dés (le quota théorique) est refusé : le plafond strict prime.
        List<String> tooMany = new ArrayList<String>(dieIds(state, "p2"));
        tooMany.add(UUID.randomUUID().toString());
        assertThatThrownBy(() -> new StealGigCommand("p1", tooMany).execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("exactement 2");

        // Tous les dés restants du défenseur passent chez l'attaquant.
        new StealGigCommand("p1", dieIds(state, "p2")).execute(state);

        assertThat(state.getPlayer("p1").getGigs()).containsExactly(7, 2);
        assertThat(state.getPlayer("p2").getGigCount()).isZero();
        // Aucun dé créé : le total des deux Gig Areas est conservé.
        assertThat(state.getPlayer("p1").getGigCount() + state.getPlayer("p2").getGigCount()).isEqualTo(2);
        // La Fixer Area (dés non lancés) du défenseur est intacte.
        assertThat(state.getPlayer("p2").getFixerDice()).hasSize(6);
        assertThat(state.isCombatPending()).isFalse();
    }

    @Test
    @DisplayName("R6 - Défenseur sans dé actif → 0 vol, l'attaque réussit quand même")
    void testR6_TargetHas0Gigs_Steals0Gigs_AttackSucceeds() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("ghost", 1, 12));
        assertThat(state.getPlayer("p2").getActiveGigCount()).isZero();

        // Ne lève PLUS « Le rival ne contrôle aucun Gig à voler » (Mini-Feature 6).
        new AttackCommand("p1", attacker.getInstanceId()).execute(state);

        assertThat(attacker.isExhausted()).isTrue();
        assertThat(state.getPhase()).isEqualTo(Phase.COMBAT);
        assertThat(state.getPlayer("p1").getGigCount()).isZero();
        assertThat(state.getPlayer("p2").getGigCount()).isZero();
        assertThat(state.isAwaitingStealChoice()).isFalse();
        assertThat(state.isCombatPending()).isFalse();
        // On ne vole JAMAIS dans la Fixer Area : les 6 dés non lancés restent en place.
        assertThat(state.getPlayer("p2").getFixerDice()).hasSize(6);
        // Aucun choix de dé à envoyer.
        assertThatThrownBy(() -> new StealGigCommand("p1", Collections.<String>emptyList()).execute(state))
                .isInstanceOf(GameRuleException.class);
    }

    @Test
    @DisplayName("R6 - Formule du quota : 0/1/9/10/19/20/29/30/39 et plafond strict")
    void testR6_Quota_Formula_AndStrictCeiling() {
        assertThat(engine.calculateQuota(-3)).isZero();
        assertThat(engine.calculateQuota(0)).isZero();
        assertThat(engine.calculateQuota(1)).isEqualTo(1);
        assertThat(engine.calculateQuota(9)).isEqualTo(1);
        assertThat(engine.calculateQuota(10)).isEqualTo(2);
        assertThat(engine.calculateQuota(19)).isEqualTo(2);
        assertThat(engine.calculateQuota(20)).isEqualTo(3);
        assertThat(engine.calculateQuota(29)).isEqualTo(3);
        assertThat(engine.calculateQuota(30)).isEqualTo(4);
        assertThat(engine.calculateQuota(39)).isEqualTo(4);

        assertThat(engine.calculateActualStealable(39, 0)).isZero();
        assertThat(engine.calculateActualStealable(39, 1)).isEqualTo(1);
        assertThat(engine.calculateActualStealable(39, 3)).isEqualTo(3);
        assertThat(engine.calculateActualStealable(39, 4)).isEqualTo(4);
        assertThat(engine.calculateActualStealable(39, 9)).isEqualTo(4);
        assertThat(engine.calculateActualStealable(0, 9)).isZero();
    }

    @Test
    @DisplayName("R6 - Un dé volé conserve son type et sa valeur exacte (d8 → 5 reste d8 → 5)")
    void testR6_StolenDie_KeepsExactDieAndValue() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("thief", 1, 11));
        state.getPlayer("p2").addRolledGig("d8", 5);
        state.getPlayer("p2").addRolledGig("d20", 19);
        String d8 = state.getPlayer("p2").activeGigs().get(0).id();

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        assertThat(state.getPendingAttack().getStealable()).isEqualTo(2);
        new StealGigCommand("p1", Arrays.asList(d8, otherDieId(state, "p2", d8))).execute(state);

        assertThat(state.getPlayer("p1").getGigs()).containsExactly(5, 19);
        assertThat(state.getPlayer("p1").getGigDice()).containsExactly("d8", "d20");
        assertThat(state.getPlayer("p1").getStreetCred()).isEqualTo(24);
        assertThat(state.getPlayer("p2").getGigCount()).isZero();
    }

    @Test
    @DisplayName("R6 - Choix de dés refusé : mauvais nombre, dé inconnu, doublon, autre joueur")
    void testR6_StealChoice_ValidationGuards() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("picker", 1, 21));
        GameFixtures.addGigs(state, "p2", 1, 2, 3);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        List<String> ids = dieIds(state, "p2");
        assertThat(state.getPendingAttack().getStealable()).isEqualTo(3);

        // Trop peu de dés choisis.
        assertThatThrownBy(() -> new StealGigCommand("p1", ids.subList(0, 2)).execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("exactement 3");
        // Dé inconnu (n'appartient pas à la Gig Area du défenseur).
        List<String> unknown = new ArrayList<String>(ids);
        unknown.set(0, UUID.randomUUID().toString());
        assertThatThrownBy(() -> new StealGigCommand("p1", unknown).execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("actif");
        // Doublon.
        assertThatThrownBy(() -> new StealGigCommand("p1", Arrays.asList(ids.get(0), ids.get(0), ids.get(1)))
                .execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("double");
        // Le défenseur ne choisit pas les dés.
        assertThatThrownBy(() -> new StealGigCommand("p2", ids).execute(state))
                .isInstanceOf(GameRuleException.class);
        // Aucune mutation n'a eu lieu pendant les refus.
        assertThat(state.getPlayer("p2").getGigs()).containsExactly(1, 2, 3);
        assertThat(state.getPlayer("p1").getGigCount()).isZero();
    }

    @Test
    @DisplayName("R6 - On ne vole QUE des dés actifs : la Fixer Area n'est jamais ponctionnée")
    void testR6_OnlyActiveDiceAreStealable() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("hoover", 1, 45));
        // Un seul dé lancé (actif) chez le défenseur, 5 dés encore en Fixer Area.
        state.getPlayer("p2").addRolledGig("d6", 6);
        state.getPlayer("p2").removeFixerDie("d6");

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);

        assertThat(engine.calculateQuota(45)).isEqualTo(5);
        assertThat(state.getPendingAttack().getQuota()).isEqualTo(5);
        assertThat(state.getPendingAttack().getStealable()).isEqualTo(1);
        new StealGigCommand("p1", dieIds(state, "p2")).execute(state);

        assertThat(state.getPlayer("p1").getGigs()).containsExactly(6);
        assertThat(state.getPlayer("p2").getFixerDice()).containsExactly("d4", "d8", "d10", "d12", "d20");
    }

    @Test
    @DisplayName("R6 - Un vol ne fait jamais gagner immédiatement (victoire au début de la DRAW)")
    void testR6_Steal_NeverWinsImmediately() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("collector", 1, 65));
        GameFixtures.addGigs(state, "p2", 1, 1, 1, 1, 1, 1, 1);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        new StealGigCommand("p1", dieIds(state, "p2")).execute(state);

        assertThat(state.getPlayer("p1").getGigCount()).isEqualTo(7);
        assertThat(state.isGameOver()).isFalse();

        // La victoire tombe au début du tour suivant de p1 (phase DRAW).
        new EndTurnCommand("p1").execute(state);
        GameFixtures.completeDraw(state, "p2");
        new EndTurnCommand("p2").execute(state);
        assertThat(state.isGameOver()).isTrue();
        assertThat(state.getWinnerId()).isEqualTo("p1");
    }

    // ------------------------------------------------------------------
    // B. Interception par un {Blocker} (au choix du défenseur)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R6 - BLOCKER : le défenseur redirige l'attaque sur son Blocker")
    void testR6_Blocker_RedirectsAttackToBlockerUnit() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("raider", 1, 4));
        CardInstance blocker = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("wall", 1, 2, CardKeyword.BLOCKER));
        CardInstance guard = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("guard", 1, 1));
        guard.setExhausted(true);
        GameFixtures.addGigs(state, "p2", 5);

        // L'attaquant vise une Unité dépensée : le défenseur peut encore intercepter.
        new AttackCommand("p1", attacker.getInstanceId(), guard.getInstanceId()).execute(state);

        assertThat(state.isAwaitingBlock()).isTrue();
        assertThat(state.getPendingAttack().getStep()).isEqualTo(CombatStep.AWAITING_BLOCK);
        // Rien n'est résolu tant que le défenseur n'a pas répondu.
        assertThat(state.getPlayer("p2").getField()).contains(guard, blocker);
        assertThat(blocker.isExhausted()).isFalse();

        new BlockCommand("p2", Collections.singletonList(blocker.getInstanceId())).execute(state);

        assertThat(blocker.isExhausted()).isTrue();
        // 4 > 2 : le Blocker est vaincu, la cible déclarée n'a rien subi.
        assertThat(state.getPlayer("p2").getTrash()).contains(blocker);
        assertThat(blocker.getZone()).isEqualTo(Zone.TRASH);
        assertThat(state.getPlayer("p2").getField()).contains(guard);
        assertThat(attacker.isExhausted()).isTrue();
        assertThat(state.getPlayer("p1").getField()).contains(attacker);
        // Attaque redirigée = aucun Gig volé (règle officielle § ATTACKING).
        assertThat(state.getPlayer("p1").getGigCount()).isZero();
        assertThat(state.getPlayer("p2").getGigs()).containsExactly(5);
        assertThat(state.isCombatPending()).isFalse();
    }

    @Test
    @DisplayName("R6 - BLOCKER sur attaque directe : aucun Gig volé même si le Blocker tombe")
    void testR6_Blocker_DirectAttack_StealsNothing() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("smuggler", 1, 15));
        CardInstance blocker = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("sentinel", 1, 3, CardKeyword.BLOCKER));
        GameFixtures.addGigs(state, "p2", 6, 2);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        assertThat(state.isAwaitingBlock()).isTrue();

        new BlockCommand("p2", Collections.singletonList(blocker.getInstanceId())).execute(state);

        assertThat(state.getPlayer("p2").getTrash()).contains(blocker);
        assertThat(state.getPlayer("p2").getGigs()).containsExactly(6, 2);
        assertThat(state.getPlayer("p1").getGigCount()).isZero();
        assertThat(state.isAwaitingStealChoice()).isFalse();
        assertThat(state.isCombatPending()).isFalse();
    }

    @Test
    @DisplayName("R6 - Le défenseur peut refuser de bloquer : l'attaque suit son cours")
    void testR6_DeclineBlock_ThenAttackResolves() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("blade", 1, 12));
        GameFixtures.fieldCard(state, "p2", GameFixtures.unit("idle-wall", 1, 1, CardKeyword.BLOCKER));
        GameFixtures.addGigs(state, "p2", 4, 4);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        assertThat(state.isAwaitingBlock()).isTrue();

        new DeclineBlockCommand("p2").execute(state);

        assertThat(state.isAwaitingStealChoice()).isTrue();
        assertThat(state.getPendingAttack().getQuota()).isEqualTo(2);
        assertThat(state.getPendingAttack().getStealable()).isEqualTo(2);
        new StealGigCommand("p1", dieIds(state, "p2")).execute(state);
        assertThat(state.getPlayer("p1").getGigCount()).isEqualTo(2);
        assertThat(state.getPlayer("p2").getGigCount()).isZero();
    }

    @Test
    @DisplayName("R6 - Blocage multiple : tous les Blockers inclinés, compétences résolues, seul le DERNIER encaisse")
    void testR6_MultiBlock_OnlyLastBlockerTakesDamage() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("juggernaut", 1, 5));
        CardInstance first = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("shield-1", 1, 1, "ON_BLOCK:DRAW:1", CardKeyword.BLOCKER));
        CardInstance second = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("shield-2", 1, 9, "ON_BLOCK:DRAW:1", CardKeyword.BLOCKER));
        int handBefore = state.getPlayer("p2").getHand().size();

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        new BlockCommand("p2", Arrays.asList(first.getInstanceId(), second.getInstanceId())).execute(state);

        // Les deux Blockers sont dépensés.
        assertThat(first.isExhausted()).isTrue();
        assertThat(second.isExhausted()).isTrue();
        // La compétence de CHAQUE Blocker est résolue (ici : pioche 1 chacun).
        assertThat(state.getPlayer("p2").getHand()).hasSize(handBefore + 2);
        // Seul le DERNIER Blocker déclaré encaisse les dégâts du combat (5 < 9).
        assertThat(state.getPlayer("p2").getField()).contains(first, second);
        assertThat(state.getPlayer("p1").getTrash()).contains(attacker);
        assertThat(state.isCombatPending()).isFalse();
    }

    @Test
    @DisplayName("R6 - Blocage multiple : le dernier Blocker est vaincu s'il est moins puissant")
    void testR6_MultiBlock_LastBlockerMayDie() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("reaper", 1, 8));
        CardInstance first = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("decoy", 1, 1, CardKeyword.BLOCKER));
        CardInstance last = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("duellist", 1, 3, CardKeyword.BLOCKER));

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        new BlockCommand("p2", Arrays.asList(first.getInstanceId(), last.getInstanceId())).execute(state);

        assertThat(state.getPlayer("p2").getField()).contains(first);
        assertThat(state.getPlayer("p2").getTrash()).contains(last);
        assertThat(state.getPlayer("p1").getField()).contains(attacker);
    }

    @Test
    @DisplayName("R6 - Blocage refusé : hors fenêtre, mauvais joueur, Unité sans BLOCKER ou déjà inclinée")
    void testR6_Block_ValidationGuards() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("aggressor", 1, 4));
        CardInstance blocker = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("wall", 1, 2, CardKeyword.BLOCKER));
        CardInstance plain = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("civilian", 1, 2));
        CardInstance spentWall = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("tired-wall", 1, 2, CardKeyword.BLOCKER));
        spentWall.setExhausted(true);

        // Aucune attaque en cours.
        assertThatThrownBy(() -> new BlockCommand("p2", Collections.singletonList(blocker.getInstanceId()))
                .execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("bloque");

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);

        // L'attaquant ne bloque pas sa propre attaque.
        assertThatThrownBy(() -> new BlockCommand("p1", Collections.singletonList(blocker.getInstanceId()))
                .execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("défenseur");
        // Liste vide.
        assertThatThrownBy(() -> new BlockCommand("p2", Collections.<UUID>emptyList()).execute(state))
                .isInstanceOf(GameRuleException.class);
        // Unité sans le mot-clé BLOCKER.
        assertThatThrownBy(() -> new BlockCommand("p2", Collections.singletonList(plain.getInstanceId()))
                .execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("BLOCKER");
        // Blocker déjà incliné.
        assertThatThrownBy(() -> new BlockCommand("p2", Collections.singletonList(spentWall.getInstanceId()))
                .execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("prêt");
        // Doublon dans la liste.
        assertThatThrownBy(() -> new BlockCommand("p2",
                Arrays.asList(blocker.getInstanceId(), blocker.getInstanceId())).execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("double");
        // Aucun des refus n'a muté l'état.
        assertThat(blocker.isExhausted()).isFalse();
        assertThat(state.isAwaitingBlock()).isTrue();
    }

    @Test
    @DisplayName("R6 - Sans Blocker prêt, l'attaque se résout immédiatement (vol ou combat)")
    void testR6_NoReadyBlocker_AttackResolvesImmediately() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("sneak", 1, 3));
        CardInstance spentBlocker = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("downed-wall", 1, 5, CardKeyword.BLOCKER));
        spentBlocker.setExhausted(true);
        GameFixtures.addGigs(state, "p2", 2);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);

        // Un Blocker incliné ne peut plus intercepter : le vol est proposé directement.
        assertThat(state.isAwaitingBlock()).isFalse();
        assertThat(state.isAwaitingStealChoice()).isTrue();
        new StealGigCommand("p1", dieIds(state, "p2")).execute(state);
        assertThat(state.getPlayer("p1").getGigs()).containsExactly(2);
    }

    // ------------------------------------------------------------------
    // A & D. Déclaration d'attaque et combat Unité contre Unité
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R6 - Combat Unité vs Unité : cible vaincue si dégâts ≥ puissance")
    void testR6_UnitVsUnit_DefeatsUnitIfDamageGreaterOrEqualPower() {
        // Égalité (4 vs 4) : dégâts ≥ puissance cible → cible vaincue (et l'attaquant
        // tombe aussi, règle officielle FIGHT « on a tie, they defeat each other »).
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("twin-a", 1, 4));
        CardInstance defender = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("twin-b", 1, 4));
        defender.setExhausted(true);

        new AttackCommand("p1", attacker.getInstanceId(), defender.getInstanceId()).execute(state);

        assertThat(defender.getZone()).isEqualTo(Zone.TRASH);
        assertThat(state.getPlayer("p2").getTrash()).contains(defender);
        assertThat(state.getPlayer("p1").getTrash()).contains(attacker);

        // Dégâts supérieurs (6 vs 2) : seule la cible tombe.
        GameState duel = GameFixtures.freshDuel();
        CardInstance strong = GameFixtures.fieldCard(duel, "p1", GameFixtures.unit("brute", 1, 6));
        CardInstance weak = GameFixtures.fieldCard(duel, "p2", GameFixtures.unit("mite", 1, 2));
        weak.setExhausted(true);

        new AttackCommand("p1", strong.getInstanceId(), weak.getInstanceId()).execute(duel);

        assertThat(duel.getPlayer("p2").getTrash()).contains(weak);
        assertThat(duel.getPlayer("p1").getField()).contains(strong);

        // Dégâts inférieurs (2 vs 7) : la cible tient, l'attaquant tombe.
        GameState other = GameFixtures.freshDuel();
        CardInstance puny = GameFixtures.fieldCard(other, "p1", GameFixtures.unit("puny", 1, 2));
        CardInstance colossus = GameFixtures.fieldCard(other, "p2", GameFixtures.unit("colossus", 1, 7));
        colossus.setExhausted(true);

        new AttackCommand("p1", puny.getInstanceId(), colossus.getInstanceId()).execute(other);

        assertThat(other.getPlayer("p2").getField()).contains(colossus);
        assertThat(other.getPlayer("p1").getTrash()).contains(puny);
    }

    @Test
    @DisplayName("R6 - Cibles d'attaque : Unité rivale DÉPENSÉE ou Gig Area (une Unit prête ne peut pas être attaquée)")
    void testR6_AttackTargets_OnlySpentRivalUnits() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("hunter", 1, 4));
        CardInstance readyGuard = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("ready-guard", 1, 1));
        CardInstance ownUnit = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("friend", 1, 1));
        ownUnit.setExhausted(true);

        assertThatThrownBy(() -> new AttackCommand("p1", attacker.getInstanceId(), readyGuard.getInstanceId())
                .validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("inclinée");
        assertThatThrownBy(() -> new AttackCommand("p1", attacker.getInstanceId(), ownUnit.getInstanceId())
                .validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("propres");

        // Attaquer la Gig Area reste possible même sans Blocker ni dé actif.
        new AttackCommand("p1", attacker.getInstanceId()).validate(state);
    }

    @Test
    @DisplayName("R6 - HASTE / GO_SOLO / ADRENALINE ignorent le mal d'invocation")
    void testR6_Haste_IgnoresSummoningSickness() {
        GameFixtures.giveEddies(state, "p1", 9);
        CardInstance hasty = GameFixtures.handCard(state, "p1",
                GameFixtures.unit("hasty", 1, 3, CardKeyword.HASTE));
        CardInstance solo = GameFixtures.handCard(state, "p1",
                GameFixtures.unit("solo", 1, 3, CardKeyword.GO_SOLO));
        CardInstance sleepy = GameFixtures.handCard(state, "p1", GameFixtures.unit("sleepy", 1, 3));

        new com.cyberpunktcg.engine.command.PlayCardCommand("p1", hasty.getInstanceId()).execute(state);
        new com.cyberpunktcg.engine.command.PlayCardCommand("p1", solo.getInstanceId()).execute(state);
        new com.cyberpunktcg.engine.command.PlayCardCommand("p1", sleepy.getInstanceId()).execute(state);

        assertThat(hasty.isSummoningSickness()).isFalse();
        assertThat(solo.isSummoningSickness()).isFalse();
        assertThat(sleepy.isSummoningSickness()).isTrue();

        GameFixtures.addGigs(state, "p2", 1, 1);
        new AttackCommand("p1", hasty.getInstanceId()).execute(state);
        new StealGigCommand("p1", Collections.singletonList(dieIds(state, "p2").get(0))).execute(state);
        assertThat(hasty.isExhausted()).isTrue();
        assertThatThrownBy(() -> new AttackCommand("p1", sleepy.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("invocation");
    }

    @Test
    @DisplayName("R6 - Une seule attaque à la fois : attaque refusée tant que le combat n'est pas résolu")
    void testR6_OneAttackAtATime() {
        CardInstance first = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("first", 1, 3));
        CardInstance second = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("second", 1, 3));
        GameFixtures.addGigs(state, "p2", 1);

        new AttackCommand("p1", first.getInstanceId()).execute(state);
        assertThat(state.isAwaitingStealChoice()).isTrue();

        assertThatThrownBy(() -> new AttackCommand("p1", second.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("en cours");

        new StealGigCommand("p1", dieIds(state, "p2")).execute(state);
        new AttackCommand("p1", second.getInstanceId()).validate(state);
    }

    @Test
    @DisplayName("R6 - Fin de tour : une attaque en attente est résolue automatiquement (dés les plus forts)")
    void testR6_EndTurn_AutoResolvesPendingAttack() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("impatient", 1, 3));
        GameFixtures.addGigs(state, "p2", 1, 6);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);
        assertThat(state.isAwaitingStealChoice()).isTrue();

        new EndTurnCommand("p1").execute(state);

        // Vol résolu automatiquement (le dé le plus fort), puis le tour passe à p2.
        assertThat(state.getPlayer("p1").getGigs()).containsExactly(6);
        assertThat(state.getPlayer("p2").getGigs()).containsExactly(1);
        assertThat(state.isCombatPending()).isFalse();
        assertThat(state.getTurn().getActivePlayerId()).isEqualTo("p2");
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
    }

    // ------------------------------------------------------------------
    // E. Condition de victoire
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R6 - Victoire : 7 dés Gigs ou plus AU DÉBUT de la phase DRAW")
    void testR6_VictoryCondition_7Gigs_AtStartOfDrawPhase() {
        GameFixtures.addGigs(state, "p2", 1, 1, 1, 1, 1);
        // 5 dés chez p2 : début de sa phase DRAW sans victoire.
        new EndTurnCommand("p1").execute(state);
        assertThat(state.isGameOver()).isFalse();
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        GameFixtures.completeDraw(state, "p2");
        assertThat(state.getPlayer("p2").getGigCount()).isEqualTo(6);

        // Un 7e dé obtenu PENDANT son tour : jamais de victoire en continu.
        GameFixtures.addGigs(state, "p2", 1);
        assertThat(state.getPlayer("p2").getGigCount()).isEqualTo(7);
        assertThat(state.isGameOver()).isFalse();

        // La victoire tombe au tout début de la phase DRAW suivante de p2.
        new EndTurnCommand("p2").execute(state);
        GameFixtures.completeDraw(state, "p1");
        assertThat(state.isGameOver()).isFalse();
        new EndTurnCommand("p1").execute(state);

        assertThat(state.isGameOver()).isTrue();
        assertThat(state.getWinnerId()).isEqualTo("p2");
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
    }

    // ------------------------------------------------------------------
    // Aides
    // ------------------------------------------------------------------

    /** Identifiants des dés Gigs actifs (lancés) d'un joueur, dans l'ordre de sa Gig Area. */
    private static List<String> dieIds(GameState state, String playerId) {
        List<String> ids = new ArrayList<String>();
        for (GigDie die : state.getPlayer(playerId).activeGigs()) {
            ids.add(die.id());
        }
        return ids;
    }

    /** Identifiant du dé Gig actif affichant une valeur donnée. */
    private static String dieIdWithValue(GameState state, String playerId, int value) {
        for (GigDie die : state.getPlayer(playerId).activeGigs()) {
            if (die.value() == value) {
                return die.id();
            }
        }
        throw new AssertionError("Aucun dé de valeur " + value + " chez " + playerId);
    }

    /** Identifiant d'un dé actif différent de {@code excluded}. */
    private static String otherDieId(GameState state, String playerId, String excluded) {
        for (GigDie die : state.getPlayer(playerId).activeGigs()) {
            if (!die.id().equals(excluded)) {
                return die.id();
            }
        }
        throw new AssertionError("Aucun autre dé chez " + playerId);
    }
}
