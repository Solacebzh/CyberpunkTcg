package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.BlockCommand;
import com.cyberpunktcg.engine.command.DrawCardCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SelectDieCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.engine.command.StealGigCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests purs des commandes (sans Spring) : jouer, attaquer, vendre, finir le tour.
 */
class GameCommandTest {

    private GameState state;

    @BeforeEach
    void setUp() {
        state = GameFixtures.freshDuel();
    }

    @Test
    void jouerUnite_paieLeCoutEtSubitLeMalDInvocation() {
        GameFixtures.giveEddies(state, "p1", 5);
        CardInstance unit = GameFixtures.handCard(state, "p1", GameFixtures.unit("bruiser", 3, 4));
        CardInstance rival = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("guard", 1, 1));

        new PlayCardCommand("p1", unit.getInstanceId()).execute(state);

        assertThat(state.getPlayer("p1").getEddies()).isEqualTo(2);
        assertThat(state.getPlayer("p1").getField()).contains(unit);
        assertThat(unit.isSummoningSickness()).isTrue();
        assertThatThrownBy(() -> new AttackCommand("p1", unit.getInstanceId(), rival.getInstanceId())
                .validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("invocation");
    }

    @Test
    void jouerUnite_coutInsuffisantOuSeuilStreetCred_refuse() {
        CardInstance ogre = GameFixtures.handCard(state, "p1", GameFixtures.unit("ogre", 5, 6));
        GameFixtures.giveEddies(state, "p1", 2);
        assertThatThrownBy(() -> new PlayCardCommand("p1", ogre.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Eddies");

        CardInstance vip = GameFixtures.handCard(state, "p1", GameFixtures.unitWithCred("vip", 1, 1, 5));
        assertThatThrownBy(() -> new PlayCardCommand("p1", vip.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Street Cred");

        GameFixtures.addGigs(state, "p1", 6);
        GameFixtures.giveEddies(state, "p1", 10);
        new PlayCardCommand("p1", vip.getInstanceId()).execute(state);
        assertThat(state.getPlayer("p1").getField()).contains(vip);
    }

    @Test
    void jouerProgram_resoutSonEffetPuisEstDefausse() {
        GameFixtures.giveEddies(state, "p1", 1);
        CardInstance program = GameFixtures.handCard(state, "p1",
                GameFixtures.program("cantrip", 1, "ON_PLAY:DRAW:1"));

        new PlayCardCommand("p1", program.getInstanceId()).execute(state);

        assertThat(state.getPlayer("p1").getTrash()).contains(program);
        assertThat(state.getPlayer("p1").getHand()).hasSize(1);
        assertThat(state.getPlayer("p1").getDeck()).hasSize(9);
    }

    @Test
    void jouerGear_sAttacheEtAdditionneSaPuissance() {
        CardInstance host = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("runner", 1, 2));
        GameFixtures.giveEddies(state, "p1", 1);
        CardInstance gear = GameFixtures.handCard(state, "p1", GameFixtures.gear("blades", 1, 3));

        assertThatThrownBy(() -> new PlayCardCommand("p1", gear.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Gear");

        new PlayCardCommand("p1", gear.getInstanceId(), host.getInstanceId()).execute(state);

        assertThat(gear.getAttachedTo()).isEqualTo(host.getInstanceId());
        assertThat(host.getAttachments()).containsExactly(gear.getInstanceId());
        assertThat(state.totalPowerFor(host)).isEqualTo(5);
    }

    @Test
    void attaquer_puissanceSuperieure_vaincLeDefenseur() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("brute", 1, 5));
        CardInstance defender = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("mite", 1, 3));
        // Règle officielle : « Ready Units can't be attacked » (Mini-Feature 6).
        defender.setExhausted(true);

        new AttackCommand("p1", attacker.getInstanceId(), defender.getInstanceId()).execute(state);

        assertThat(state.getPlayer("p2").getField()).doesNotContain(defender);
        assertThat(state.getPlayer("p2").getTrash()).contains(defender);
        assertThat(state.getPlayer("p1").getField()).contains(attacker);
        assertThat(attacker.isExhausted()).isTrue();
        assertThat(state.getPhase()).isEqualTo(Phase.COMBAT);
        assertThat(state.isReactionWindowOpen()).isTrue();
        assertThat(state.getReactionWindow().getDefendingPlayerId()).isEqualTo("p2");
    }

    @Test
    void attaquer_egalite_vaincLesDeux() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("twin-a", 1, 4));
        CardInstance defender = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("twin-b", 1, 4));
        defender.setExhausted(true);

        new AttackCommand("p1", attacker.getInstanceId(), defender.getInstanceId()).execute(state);

        assertThat(state.getPlayer("p1").getTrash()).contains(attacker);
        assertThat(state.getPlayer("p2").getTrash()).contains(defender);
    }

    @Test
    void blocker_interceptionAuChoixDuDefenseur() {
        // Blocker plus fort que l'attaquant (4 > 1) : il survit, donc son
        // inclinaison reste observable (`defeatUnit` → `clearCombatMarkers()`
        // redresse une carte vaincue avant de la défausser).
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("raider", 1, 1));
        CardInstance blocker = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("wall", 1, 4, CardKeyword.BLOCKER));
        CardInstance other = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("bystander", 1, 1));
        other.setExhausted(true);
        GameFixtures.addGigs(state, "p2", 3);

        // Mini-Feature 6 : le blocage n'est plus imposé à l'attaquant — l'attaque
        // est déclarée, puis le défenseur décide d'intercepter (fenêtre Blocker).
        new AttackCommand("p1", attacker.getInstanceId(), other.getInstanceId()).execute(state);
        assertThat(state.isAwaitingBlock()).isTrue();
        // Une Unit PRÊTE (le Blocker) ne peut pas être attaquée directement.
        assertThatThrownBy(() -> new AttackCommand("p1", attacker.getInstanceId(), blocker.getInstanceId())
                .validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("en cours");

        new BlockCommand("p2", blocker.getInstanceId()).execute(state);

        assertThat(blocker.isExhausted()).isTrue();                    // bloquer dépense
        assertThat(state.getPlayer("p2").getField()).contains(blocker);  // 4 > 1 : il survit
        assertThat(state.getPlayer("p1").getTrash()).contains(attacker); // attaquant vaincu
        assertThat(state.getPlayer("p2").getField()).contains(other);    // cible déclarée épargnée
        assertThat(other.getDamage()).isZero();
        assertThat(state.getPlayer("p1").getGigCount()).isZero();        // aucun Gig volé
        assertThat(state.isCombatPending()).isFalse();
    }

    @Test
    void volDeGig_lAttaquantChoisitLeDeAuPlafondStrict() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("ghost", 1, 2));
        GameFixtures.addGigs(state, "p2", 1, 5);

        new AttackCommand("p1", attacker.getInstanceId()).execute(state);

        // power 2 → quota N = 1 ; 2 dés actifs chez le défenseur → plafond M = 1.
        assertThat(state.isAwaitingStealChoice()).isTrue();
        assertThat(state.getPendingAttack().getQuota()).isEqualTo(1);
        assertThat(state.getPendingAttack().getStealable()).isEqualTo(1);

        // L'attaquant désigne le dé qu'il veut (ici celui qui affiche 5).
        String chosen = state.getPlayer("p2").getGigDieIds().get(1);
        new StealGigCommand("p1", Collections.singletonList(chosen)).execute(state);

        assertThat(state.getPlayer("p1").getGigs()).containsExactly(5);
        assertThat(state.getPlayer("p2").getGigs()).containsExactly(1);
        assertThat(state.isGameOver()).isFalse();
        assertThat(state.isCombatPending()).isFalse();
    }

    @Test
    void quick_enReactionSeulesLesCartesQuickDuDefenseur() {
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("invader", 1, 3));
        CardInstance defender = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("holder", 1, 3));
        defender.setExhausted(true);
        GameFixtures.giveEddies(state, "p2", 5);
        CardInstance plain = GameFixtures.handCard(state, "p2", GameFixtures.unit("slow", 1, 1));
        CardInstance quick = GameFixtures.handCard(state, "p2",
                GameFixtures.program("flash", 1, "QUICK:DRAW:1", CardKeyword.QUICK));

        // Hors fenêtre, le non-actif ne joue rien.
        assertThatThrownBy(() -> new PlayCardCommand("p2", quick.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class);

        new AttackCommand("p1", attacker.getInstanceId(), defender.getInstanceId()).execute(state);

        assertThatThrownBy(() -> new PlayCardCommand("p2", plain.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("QUICK");

        int hand = state.getPlayer("p2").getHand().size();
        new PlayCardCommand("p2", quick.getInstanceId()).execute(state);
        assertThat(state.getPlayer("p2").getTrash()).contains(quick);
        assertThat(state.getPlayer("p2").getHand()).hasSize(hand);
    }

    @Test
    void vente_uniqueParTour_puisDeNouveauApresUnTour() {
        GameFixtures.handCard(state, "p1", GameFixtures.unit("junk-a", 1, 1));
        CardInstance second = GameFixtures.handCard(state, "p1", GameFixtures.unit("junk-b", 1, 1));
        CardInstance first = state.getPlayer("p1").getHand().get(0);

        new SellCardCommand("p1", first.getInstanceId()).execute(state);
        // Mini-Feature 3 : la vente CRÉE une ressource (Eddies Area, face cachée,
        // prête à incliner) et ne rapporte aucun Eddie immédiatement.
        assertThat(state.getPlayer("p1").getEddies()).isZero();
        assertThat(first.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(first.isFaceDown()).isTrue();
        assertThat(first.isExhausted()).isFalse();

        assertThatThrownBy(() -> new SellCardCommand("p1", second.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("vente");

        GameFixtures.passTurn(state, "p1");
        GameFixtures.passTurn(state, "p2");
        new SellCardCommand("p1", second.getInstanceId()).execute(state);
        // R2 : les Eddies ne se reportent pas — la réserve retombe à 0 en début de
        // tour ; cette seconde vente ne crédite rien non plus, elle ajoute seulement
        // une carte-ressource à l'Eddies Area.
        assertThat(state.getPlayer("p1").getEddies()).isZero();
        assertThat(state.getPlayer("p1").getEddiesArea()).contains(first, second);
    }

    @Test
    void endTurn_victoireA7GigsAuDebutDuTour_bloqueLaPartie() {
        GameFixtures.addGigs(state, "p2", 1, 2, 3, 4, 5, 6, 6);

        new EndTurnCommand("p1").execute(state);

        assertThat(state.isGameOver()).isTrue();
        assertThat(state.getWinnerId()).isEqualTo("p2");
        assertThat(state.getEventLog())
                .filteredOn(event -> event.getType() == GameEventType.GAME_WON)
                .isNotEmpty();
        assertThatThrownBy(() -> new EndTurnCommand("p2").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("terminée");
    }

    @Test
    void endTurn_ouvreLaPhaseDrawInteractive_puisPiocheEtGigSurActionDuJoueur() {
        int deck = state.getPlayer("p2").getDeck().size();

        new EndTurnCommand("p1").execute(state);

        // Mini-Feature 5 : la fin de tour s'arrête en phase DRAW, en attente de la pioche.
        assertThat(state.getTurn().getNumber()).isEqualTo(2);
        assertThat(state.getTurn().getActivePlayerId()).isEqualTo("p2");
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);
        assertThat(state.getPlayer("p2").getHand()).isEmpty();
        assertThat(state.getPlayer("p2").getGigs()).isEmpty();
        assertThat(state.isReactionWindowOpen()).isFalse();

        new DrawCardCommand("p2").execute(state);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DIE_SELECT);
        assertThat(state.getPlayer("p2").getHand()).hasSize(1);
        assertThat(state.getPlayer("p2").getDeck()).hasSize(deck - 1);

        new SelectDieCommand("p2", "d4").execute(state);
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(state.getDrawStep()).isNull();
        assertThat(state.getPlayer("p2").getGigs()).hasSize(1);
        assertThat(state.getPlayer("p2").getGigs().get(0)).isBetween(1, 4);
        assertThat(state.getPlayer("p2").getFixerDice()).hasSize(5);
    }

    @Test
    void endTurn_deckVide_defaiteALaPioche() {
        state.getPlayer("p2").getDeck().clear();

        new EndTurnCommand("p1").execute(state);
        assertThat(state.isGameOver()).isFalse();
        new DrawCardCommand("p2").execute(state);

        assertThat(state.isGameOver()).isTrue();
        assertThat(state.getWinnerId()).isEqualTo("p1");
    }

    @Test
    void legend_callCouteUnEddieEtDeclencheFlip() {
        CardInstance legend = GameFixtures.legendCard(state, "p1",
                GameFixtures.legend("oracle", "FLIP:DRAW:1"), true);

        // R4.1 : « Call a Legend (once per turn) — spend 1 €$ » : sans Eddie, c'est refusé
        assertThatThrownBy(() -> new PlayCardCommand("p1", legend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Eddies insuffisants");

        GameFixtures.giveEddies(state, "p1", 1);
        new PlayCardCommand("p1", legend.getInstanceId()).execute(state);

        assertThat(legend.isFaceDown()).isFalse();
        assertThat(legend.getZone()).isEqualTo(Zone.LEGENDS_AREA);
        assertThat(state.getPlayer("p1").getEddies()).isZero(); // 1 €$ payé
        assertThat(state.getPlayer("p1").getHand()).hasSize(1); // effet FLIP:DRAW:1

        assertThatThrownBy(() -> new PlayCardCommand("p1", legend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class);
    }

    @Test
    void goSolo_peutAttaquerLeTourDePose() {
        GameFixtures.giveEddies(state, "p1", 2);
        CardInstance solo = GameFixtures.handCard(state, "p1",
                GameFixtures.unit("lone-wolf", 2, 3, CardKeyword.GO_SOLO));
        CardInstance defender = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("guard", 1, 1));
        defender.setExhausted(true);

        new PlayCardCommand("p1", solo.getInstanceId()).execute(state);
        assertThat(solo.isSummoningSickness()).isFalse();

        new AttackCommand("p1", solo.getInstanceId(), defender.getInstanceId()).execute(state);
        assertThat(state.getPlayer("p2").getTrash()).contains(defender);
    }
}
