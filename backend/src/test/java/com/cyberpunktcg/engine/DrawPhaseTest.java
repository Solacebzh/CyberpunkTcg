package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameActionResult;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLogEntry;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.GigDie;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.DrawCardCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SelectDieCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.engine.command.SpendResourceCommand;
import com.cyberpunktcg.engine.command.StealGigCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mini-Feature 5 — <strong>phase DRAW interactive et choix des dés</strong> (R5).
 *
 * <p>Règle officielle appliquée (Guide § TURN ORDER — START PHASE) :</p>
 * <ol>
 *   <li>READY SPENT CARDS — toutes les cartes inclinées sont redressées ;</li>
 *   <li>DRAW 1 — le joueur pioche la carte du dessus (deck vide = défaite) ;</li>
 *   <li>GAIN A GIG — « Take a die from your fixer area, roll it, and add it to
 *   your friendly Gig area. You can choose any die except the d20, which is
 *   always rolled last. »</li>
 * </ol>
 *
 * <p>Le serveur pilote la machine à états {@link DrawStep} ({@link DrawPhaseHandler}) :
 * {@code EndTurnCommand} s'arrête en {@code AWAITING_DRAW}, le joueur doit
 * envoyer {@code DRAW_CARD} ({@link DrawCardCommand}) puis {@code SELECT_DIE}
 * ({@link SelectDieCommand}) ; la phase {@code MAIN} ne s'ouvre qu'ensuite.
 * Tests purs (sans Spring) sur {@link GameFixtures#freshDuel()} : p1 actif en
 * MAIN au tour 1, decks de 10 cartes, mains vides.</p>
 */
class DrawPhaseTest {

    private GameState state;

    @BeforeEach
    void setUp() {
        state = GameFixtures.freshDuel();
    }

    // ------------------------------------------------------------------
    // R5.1 — La pioche exige une action du joueur
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R5 - Séquence DRAW : fin de tour → AWAITING_DRAW, la pioche attend le clic du joueur")
    void testR5_Draw_Sequence_RequiresPlayerActionForDraw() {
        Player p2 = state.getPlayer("p2");
        CardInstance spent = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("spent", 1, 1));
        spent.setExhausted(true);
        GameFixtures.giveEddies(state, "p2", 3);
        int deckBefore = p2.getDeck().size();
        int handBefore = p2.getHand().size();

        List<GameEvent> endEvents = new EndTurnCommand("p1").execute(state);

        // 1. DRAW_START résolu par le serveur : tour passé, cartes redressées, 0 Eddie…
        assertThat(state.getTurn().getNumber()).isEqualTo(2);
        assertThat(state.getTurn().getActivePlayerId()).isEqualTo("p2");
        assertThat(spent.isExhausted()).isFalse();
        assertThat(p2.getEddies()).isZero();
        // …mais la partie S'ARRÊTE en phase DRAW, en attente de la pioche : rien n'a été pioché ni lancé.
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);
        assertThat(state.getDrawStep().awaitsPlayer()).isTrue();
        assertThat(p2.getDeck()).hasSize(deckBefore);
        assertThat(p2.getHand()).hasSize(handBefore);
        assertThat(p2.getGigs()).isEmpty();
        assertThat(p2.getFixerDice()).hasSize(6);
        assertThat(types(endEvents))
                .contains(GameEventType.TURN_ENDED, GameEventType.TURN_STARTED, GameEventType.PHASE_CHANGED)
                .doesNotContain(GameEventType.CARD_DRAWN, GameEventType.GIG_ROLLED);
        assertThat(state.getTurn().copy().getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);

        // 2. Impossible de brûler l'étape : ni choisir un dé, ni terminer le tour, ni jouer.
        assertThatThrownBy(() -> new SelectDieCommand("p2", "d4").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("AWAITING_DIE_SELECT");
        assertThatThrownBy(() -> new EndTurnCommand("p2").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Draw");
        CardInstance card = GameFixtures.handCard(state, "p2", GameFixtures.unit("early", 0, 1));
        assertThatThrownBy(() -> new PlayCardCommand("p2", card.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class);
        assertThatThrownBy(() -> new SellCardCommand("p2", card.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class);
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);

        // 3. Seul le joueur actif peut piocher, et uniquement en AWAITING_DRAW.
        assertThatThrownBy(() -> new DrawCardCommand("p1").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("tour");

        // 4. Le clic sur la pioche : +1 carte, puis attente du choix du dé.
        List<GameEvent> drawEvents = new DrawCardCommand("p2").execute(state);
        assertThat(p2.getDeck()).hasSize(deckBefore - 1);
        assertThat(p2.getHand()).hasSize(handBefore + 2); // carte de test + carte piochée
        assertThat(types(drawEvents)).containsExactly(GameEventType.CARD_DRAWN);
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DIE_SELECT);
        assertThat(p2.getGigs()).isEmpty();

        // 5. Une seule pioche par tour : re-cliquer est refusé.
        assertThatThrownBy(() -> new DrawCardCommand("p2").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("AWAITING_DRAW");
        assertThatThrownBy(() -> new EndTurnCommand("p2").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("dé");

        // 6. Le choix du dé clôt la phase : Gig gagné, passage automatique en MAIN.
        List<GameEvent> dieEvents = new SelectDieCommand("p2", "d6").execute(state);
        assertThat(types(dieEvents)).containsExactly(GameEventType.GIG_ROLLED, GameEventType.PHASE_CHANGED);
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(state.getDrawStep()).isNull();
        assertThat(p2.getGigs()).hasSize(1);
        assertThat(p2.getGigs().get(0)).isBetween(1, 6);
        assertThat(p2.getGigDice()).containsExactly("d6");
        assertThat(p2.getFixerDice()).containsExactly("d4", "d8", "d10", "d12", "d20");

        // 7. Le journal de diagnostic raconte la séquence dans l'ordre : DRAW → GIG_ROLL → MAIN.
        List<String> lines = descriptions();
        int drawIdx = lastIndexContaining(lines, "Phase DRAW");
        int gigIdx = lastIndexContaining(lines, "Lancer de Gig");
        int mainIdx = lastIndexContaining(lines, "Phase MAIN");
        assertThat(drawIdx).isGreaterThanOrEqualTo(0);
        assertThat(gigIdx).isGreaterThan(drawIdx);
        assertThat(mainIdx).isGreaterThan(gigIdx);
        assertThat(actionTypes()).contains("VICTORY_CHECK", "TURN_RESET", "DRAW_STEP", "DRAW", "GIG_ROLL");

        // 8. Hors phase DRAW, les commandes de la phase DRAW sont refusées.
        assertThatThrownBy(() -> new DrawCardCommand("p2").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("phase Draw");
        assertThatThrownBy(() -> new SelectDieCommand("p2", "d4").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("phase Draw");
        // Et la MAIN est bien jouable : fin de tour acceptée.
        new EndTurnCommand("p2").execute(state);
        assertThat(state.getTurn().getActivePlayerId()).isEqualTo("p1");
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);
    }

    // ------------------------------------------------------------------
    // R5.2 — Choix du dé : n'importe lequel sauf le d20, toujours en dernier
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R5 - Choix du dé : tout dé sauf le d20 tant qu'il en reste d'autres ; le d20 seulement en dernier")
    void testR5_SelectDie_OnlySmallestDieUntilAllUsedExceptD20() {
        Player p2 = state.getPlayer("p2");
        assertThat(p2.selectableFixerDice()).containsExactly("d4", "d6", "d8", "d10", "d12");
        assertThat(p2.canSelectFixerDie("d20")).isFalse();

        // Tour 2 de p2 : le d20 est refusé tant qu'il reste d'autres dés, l'état ne bouge pas.
        new EndTurnCommand("p1").execute(state);
        new DrawCardCommand("p2").execute(state);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DIE_SELECT);
        assertThatThrownBy(() -> new SelectDieCommand("p2", "d20").execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("d20")
                .hasMessageContaining("dernier");
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DIE_SELECT);
        assertThat(p2.getFixerDice()).hasSize(6);
        assertThat(p2.getGigs()).isEmpty();

        // Un dé inconnu est refusé ; le libre choix parmi les autres dés est accepté (pas
        // forcément le plus petit) et l'identifiant est normalisé ("D10" ≡ "d10").
        assertThatThrownBy(() -> new SelectDieCommand("p2", "d7").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("inconnu");
        assertThatThrownBy(() -> new SelectDieCommand("p2", " "))
                .isInstanceOf(IllegalArgumentException.class);
        new SelectDieCommand("p2", "D10").execute(state);
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(p2.getFixerDice()).containsExactly("d4", "d6", "d8", "d12", "d20");
        assertThat(p2.getGigDice()).containsExactly("d10");
        assertThat(p2.getGigs().get(0)).isBetween(1, 10);

        // Un dé déjà lancé ne peut pas être rechoisi au tour suivant.
        GameFixtures.passTurn(state, "p2");          // tour 3 : p1 pioche + lance
        new EndTurnCommand("p1").execute(state);     // tour 4 : p2 en AWAITING_DRAW
        new DrawCardCommand("p2").execute(state);
        assertThatThrownBy(() -> new SelectDieCommand("p2", "d10").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("plus dans la Fixer Area");
        assertThatThrownBy(() -> new SelectDieCommand("p2", "d20").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("dernier");
        new SelectDieCommand("p2", "d12").execute(state);
        assertThat(p2.getFixerDice()).containsExactly("d4", "d6", "d8", "d20");

        // On épuise les autres dés : tant qu'il en reste un hors d20, le d20 reste interdit.
        for (String die : Arrays.asList("d4", "d6", "d8")) {
            GameFixtures.passTurn(state, "p2");
            new EndTurnCommand("p1").execute(state);
            new DrawCardCommand("p2").execute(state);
            assertThat(p2.canSelectFixerDie("d20")).isFalse();
            assertThatThrownBy(() -> new SelectDieCommand("p2", "d20").validate(state))
                    .isInstanceOf(GameRuleException.class)
                    .hasMessageContaining("dernier");
            new SelectDieCommand("p2", die).execute(state);
            assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        }
        assertThat(p2.getFixerDice()).containsExactly("d20");
        assertThat(p2.selectableFixerDice()).containsExactly("d20");
        assertThat(p2.getGigs()).hasSize(5);
        assertThat(p2.getGigDice()).containsExactly("d10", "d12", "d4", "d6", "d8");

        // Dernier dé : le d20 devient (le seul) choix possible.
        GameFixtures.passTurn(state, "p2");
        new EndTurnCommand("p1").execute(state);
        new DrawCardCommand("p2").execute(state);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DIE_SELECT);
        new SelectDieCommand("p2", "d20").execute(state);
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(p2.getFixerDice()).isEmpty();
        assertThat(p2.getGigs()).hasSize(6);
        assertThat(p2.getGigs().get(5)).isBetween(1, 20);
        assertThat(p2.getGigDice().get(5)).isEqualTo("d20");

        // Fixer Area vide : plus de dé à choisir, la pioche suffit et la MAIN s'ouvre directement.
        GameFixtures.passTurn(state, "p2");
        new EndTurnCommand("p1").execute(state);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);
        new DrawCardCommand("p2").execute(state);
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(state.getDrawStep()).isNull();
        assertThat(p2.getGigs()).hasSize(6);
    }

    // ------------------------------------------------------------------
    // R5.3 — Deck vide au moment de piocher = défaite immédiate
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R5 - Deck vide : cliquer sur la pioche fait perdre immédiatement (journal DRAW FAILED + VICTORY)")
    void testR5_EmptyDeck_IsLoss() {
        state.getPlayer("p2").getDeck().clear();

        // La fin de tour n'est PAS fatale en soi : la défaite tombe à la pioche.
        new EndTurnCommand("p1").execute(state);
        assertThat(state.isGameOver()).isFalse();
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);

        List<GameEvent> events = new DrawCardCommand("p2").execute(state);

        assertThat(state.isGameOver()).isTrue();
        assertThat(state.getWinnerId()).isEqualTo("p1");
        assertThat(state.getEndReason()).contains("deck-out");
        assertThat(state.getPlayer("p2").getHand()).isEmpty();
        assertThat(state.getPlayer("p2").getGigs()).isEmpty();
        assertThat(types(events)).containsExactly(GameEventType.GAME_WON);

        // Journal de diagnostic : pioche en échec puis victoire du rival.
        List<GameLogEntry> entries = state.getGameLog().recent(10);
        GameLogEntry draw = findLast(entries, "DRAW");
        assertThat(draw.getResult()).isEqualTo(GameActionResult.FAILED);
        assertThat(draw.getDescription()).contains("deck est vide");
        GameLogEntry victory = findLast(entries, "VICTORY");
        assertThat(victory.getResult()).isEqualTo(GameActionResult.SUCCESS);
        assertThat(victory.getPlayerId()).isEqualTo("p1");
        assertThat(victory.getDescription()).contains("deck-out");

        // Partie terminée : plus aucune commande n'est acceptée.
        assertThatThrownBy(() -> new SelectDieCommand("p2", "d4").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("terminée");
        assertThatThrownBy(() -> new EndTurnCommand("p2").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("terminée");
    }

    // ------------------------------------------------------------------
    // Compléments : victoire à 7 Gigs avant la pioche, vues masquées, vol de Gig
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R5 - La victoire à 7 Gigs se vérifie AVANT la pioche : aucune étape DRAW n'est ouverte")
    void testR5_VictoryCheckHappensBeforeDrawSteps() {
        GameFixtures.addGigs(state, "p2", 1, 2, 3, 4, 5, 6, 6);

        new EndTurnCommand("p1").execute(state);

        assertThat(state.isGameOver()).isTrue();
        assertThat(state.getWinnerId()).isEqualTo("p2");
        assertThat(state.getPlayer("p2").getHand()).isEmpty();
        assertThat(state.getPlayer("p2").getFixerDice()).hasSize(6);
        assertThatThrownBy(() -> new DrawCardCommand("p2").validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("terminée");
    }

    @Test
    @DisplayName("R5 - La sous-étape DRAW et les types de dés des Gigs sont visibles dans les vues masquées")
    void testR5_DrawStepAndGigDiceSurviveMaskedCopies() {
        new EndTurnCommand("p1").execute(state);
        GameState viewOfRival = state.maskedCopyFor("p1");
        assertThat(viewOfRival.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(viewOfRival.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);
        assertThat(viewOfRival.getPlayer("p2").getFixerDice()).hasSize(6);

        new DrawCardCommand("p2").execute(state);
        assertThat(state.maskedCopyFor("p2").getDrawStep()).isEqualTo(DrawStep.AWAITING_DIE_SELECT);

        new SelectDieCommand("p2", "d8").execute(state);
        GameState after = state.maskedCopyFor("p1");
        assertThat(after.getDrawStep()).isNull();
        assertThat(after.getPlayer("p2").getGigs()).hasSize(1);
        assertThat(after.getPlayer("p2").getGigDice()).containsExactly("d8");
        assertThat(after.getPlayer("p2").getFixerDice()).doesNotContain("d8").hasSize(5);
    }

    @Test
    @DisplayName("R5 - Un Gig volé emporte le type de son dé ; un Gig injecté hors lancer est typé '?'")
    void testR5_StolenGigKeepsItsDieType() {
        // p2 gagne un Gig au d12 à son tour 2, puis p1 le lui vole à son tour 3.
        new EndTurnCommand("p1").execute(state);
        new DrawCardCommand("p2").execute(state);
        new SelectDieCommand("p2", "d12").execute(state);
        GameFixtures.addGigs(state, "p2", 1);
        assertThat(state.getPlayer("p2").getGigDice()).containsExactly("d12", Player.UNKNOWN_DIE);
        int rolled = state.getPlayer("p2").getGigs().get(0);

        GameFixtures.passTurn(state, "p2"); // p1 : pioche + d4
        assertThat(state.getPlayer("p1").getGigDice()).containsExactly("d4");
        CardInstance thief = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("thief", 1, 2));
        new AttackCommand("p1", thief.getInstanceId()).execute(state);

        // Mini-Feature 6 : power 2 → quota N = 1, plafond strict M = min(1, dés actifs) = 1.
        // L'attaquant choisit le dé volé (ici le d12) via StealGigCommand.
        assertThat(state.isAwaitingStealChoice()).isTrue();
        assertThat(state.getPendingAttack().getQuota()).isEqualTo(1);
        assertThat(state.getPendingAttack().getStealable()).isEqualTo(1);
        String d12Id = null;
        for (GigDie die : state.getPlayer("p2").activeGigs()) {
            if ("d12".equals(die.die())) {
                d12Id = die.id();
            }
        }
        assertThat(d12Id).isNotNull();
        new StealGigCommand("p1", java.util.Collections.singletonList(d12Id)).execute(state);

        // Le dé choisi est volé : type et valeur suivent le Gig (Mini-Feature 5/6).
        assertThat(state.getPlayer("p1").getGigs()).hasSize(2);
        assertThat(state.getPlayer("p1").getGigDice().get(0)).isEqualTo("d4");
        assertThat(state.getPlayer("p1").getGigs().get(1)).isEqualTo(rolled);
        assertThat(state.getPlayer("p1").getGigDice().get(1)).isEqualTo("d12");
        assertThat(state.getPlayer("p2").getGigDice()).containsExactly(Player.UNKNOWN_DIE);
    }

    @Test
    @DisplayName("R5 - Les ressources ne s'inclinent qu'en MAIN : refusées pendant la phase DRAW")
    void testR5_SpendResourceRefusedDuringDraw() {
        CardInstance legend = GameFixtures.legendCard(state, "p2", GameFixtures.legend("oracle", null), true);
        new EndTurnCommand("p1").execute(state);
        assertThatThrownBy(() -> new SpendResourceCommand("p2", legend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Main");
        GameFixtures.completeDraw(state, "p2");
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        new SpendResourceCommand("p2", legend.getInstanceId()).execute(state);
        assertThat(state.getPlayer("p2").getEddies()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    private static List<GameEventType> types(List<GameEvent> events) {
        List<GameEventType> types = new ArrayList<GameEventType>();
        for (GameEvent event : events) {
            types.add(event.getType());
        }
        return types;
    }

    private List<String> descriptions() {
        List<String> lines = new ArrayList<String>();
        for (GameLogEntry entry : state.getGameLog().recent(60)) {
            lines.add(entry.getDescription());
        }
        return lines;
    }

    private List<String> actionTypes() {
        List<String> types = new ArrayList<String>();
        for (GameLogEntry entry : state.getGameLog().recent(60)) {
            types.add(entry.getActionType());
        }
        return types;
    }

    private static int lastIndexContaining(List<String> lines, String needle) {
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                index = i;
            }
        }
        return index;
    }

    private static GameLogEntry findLast(List<GameLogEntry> entries, String actionType) {
        GameLogEntry found = null;
        for (GameLogEntry entry : entries) {
            if (actionType.equals(entry.getActionType())) {
                found = entry;
            }
        }
        if (found == null) {
            throw new AssertionError("Aucune entrée " + actionType + " dans le journal");
        }
        return found;
    }
}
