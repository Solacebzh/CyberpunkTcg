package com.cyberpunktcg.engine.command;

import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameActionResult;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameEventType;
import com.cyberpunktcg.domain.game.GameLogEntry;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameFixtures;
import com.cyberpunktcg.engine.GameRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mini-Feature 3 — <strong>« Vente = Création de ressource »</strong> (règle R3 du mini-lot).
 *
 * <p>Règle officielle appliquée (Guide § MAIN PHASE — « SELL FOR EDDIE (ONCE PER
 * TURN) » et § GLOSSARY — SELL) :</p>
 * <ol>
 *   <li>{@link GameConstants#SALES_PER_TURN} vente par tour et par joueur, en phase
 *   {@code MAIN} uniquement ;</li>
 *   <li>vendre ne rapporte <strong>aucun Eddie immédiatement</strong> : la carte est
 *   révélée au rival ({@code CARD_REVEALED}), retirée de la main, puis placée dans
 *   {@link Zone#EDDIES_AREA} avec {@code faceDown = true} et {@code exhausted = false} ;</li>
 *   <li>la carte devient une <strong>ressource</strong> du terrain : elle vaut 1 €$
 *   <em>par tour</em>, obtenu en l'inclinant ({@link SpendEddiesCommand}, R6) — possible
 *   dès le tour de la vente puisqu'elle est posée prête.</li>
 * </ol>
 *
 * <p>Tests purs (aucun contexte Spring) : le duel vient de
 * {@link GameFixtures#freshDuel()} — {@code p1} actif, phase {@code MAIN}, tour 1,
 * 0 Eddie, mains vides.</p>
 */
class SellCardCommandTest {

    private GameState state;
    private Player seller;

    @BeforeEach
    void setUp() {
        state = GameFixtures.freshDuel();
        seller = state.getPlayer("p1");
    }

    // ------------------------------------------------------------------
    // Cas de succès — la vente crée une ressource
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R3 - Vendre : main → EDDIES_AREA, faceDown=true, exhausted=false, 0 Eddie immédiat")
    void testR3_SellCard_GoesToEddiesArea_FaceDown_NotExhausted() {
        // Coût imprimé volontairement élevé (4) : la vente ne paie pas le coût de la carte.
        CardInstance sold = GameFixtures.handCard(state, "p1", GameFixtures.unit("junk", 4, 3));
        int handBefore = seller.getHand().size();
        assertThat(seller.getEddies()).isZero();

        List<GameEvent> events = new SellCardCommand("p1", sold.getInstanceId()).execute(state);

        // 1. La carte a quitté la main et rejoint l'Eddies Area (ni Trash, ni Field).
        assertThat(seller.getHand()).hasSize(handBefore - 1).doesNotContain(sold);
        assertThat(sold.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(seller.getEddiesArea()).containsExactly(sold);
        assertThat(seller.getTrash()).doesNotContain(sold);

        // 2. Ressource face cachée, PRÊTE à être utilisée (exhausted = false).
        assertThat(sold.isFaceDown()).isTrue();
        assertThat(sold.isExhausted()).isFalse();

        // 3. Aucun Eddie crédité par la vente elle-même.
        assertThat(seller.getEddies()).isZero();

        // 4. La limite du tour est posée.
        assertThat(seller.hasSoldThisTurn()).isTrue();

        // 5. Révélation au rival + vente journalisées (journal public et diagnostic).
        assertThat(events).anyMatch(event -> event.getType() == GameEventType.CARD_SOLD);
        assertThat(state.getGameLog().getEntries()).anyMatch(entry ->
                "CARD_REVEALED".equals(entry.getActionType())
                        && entry.getResult() == GameActionResult.INFO
                        && entry.getDescription().contains(sold.getName()));
        GameLogEntry sale = saleEntry();
        assertThat(sale.getResult()).isEqualTo(GameActionResult.SUCCESS);
        assertThat(sale.getDetails())
                .containsEntry("zone", Zone.EDDIES_AREA.name())
                .containsEntry("faceDown", true)
                .containsEntry("exhausted", false)
                .containsEntry("eddieGained", 0)
                .containsEntry("eddiesTotal", 0)
                .containsEntry("salesPerTurn", GameConstants.SALES_PER_TURN);
    }

    @Test
    @DisplayName("R3 - La carte vendue est une ressource : inclinable dès ce tour pour 1 €$")
    void testR3_SellCard_CreatesResourceUsableSameTurn() {
        CardInstance sold = GameFixtures.handCard(state, "p1", GameFixtures.unit("scrap", 2, 2));

        new SellCardCommand("p1", sold.getInstanceId()).execute(state);
        assertThat(seller.getEddies()).isZero();

        // Posée prête : le joueur peut l'incliner tout de suite (R6, phase MAIN autorisée).
        new SpendEddiesCommand("p1", sold.getInstanceId()).execute(state);

        assertThat(seller.getEddies()).isEqualTo(1);
        assertThat(sold.isExhausted()).isTrue();
        assertThat(sold.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(seller.getEddiesArea()).containsExactly(sold);

        // 1 €$ par tour seulement : la même carte ne peut pas être inclinée deux fois.
        assertThatThrownBy(() -> new SpendEddiesCommand("p1", sold.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("déjà inclinée");
    }

    // ------------------------------------------------------------------
    // Cas de refus — limite et garde-fous
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R3 - Limite : 1 seule vente par tour (2e refusée, de nouveau légale au tour suivant)")
    void testR3_SellCard_LimitOnePerTurn() {
        CardInstance first = GameFixtures.handCard(state, "p1", GameFixtures.unit("sell-1", 1, 1));
        CardInstance second = GameFixtures.handCard(state, "p1", GameFixtures.unit("sell-2", 1, 1));

        new SellCardCommand("p1", first.getInstanceId()).execute(state);
        assertThat(seller.hasSoldThisTurn()).isTrue();

        // La seconde vente du même tour est refusée — par validate() comme par execute().
        SellCardCommand illegal = new SellCardCommand("p1", second.getInstanceId());
        assertThatThrownBy(() -> illegal.validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Une seule vente par tour");
        assertThatThrownBy(() -> illegal.execute(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Une seule vente par tour");

        // Aucune mutation : la carte visée reste en main, l'Eddies Area inchangée.
        assertThat(second.getZone()).isEqualTo(Zone.HAND);
        assertThat(seller.getHand()).contains(second);
        assertThat(seller.getEddiesArea()).containsExactly(first);
        assertThat(seller.getEddies()).isZero();

        // Tour suivant de p1 (p1 termine, puis p2) : la limite est réinitialisée.
        new EndTurnCommand("p1").execute(state);
        new EndTurnCommand("p2").execute(state);
        assertThat(state.getTurn().getActivePlayerId()).isEqualTo("p1");
        assertThat(seller.hasSoldThisTurn()).isFalse();

        new SellCardCommand("p1", second.getInstanceId()).execute(state);
        assertThat(second.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(second.isFaceDown()).isTrue();
        assertThat(second.isExhausted()).isFalse();
        assertThat(seller.hasSoldThisTurn()).isTrue();
        // Toujours aucun Eddie immédiat : la vente crée la ressource, elle ne paie pas.
        assertThat(seller.getEddies()).isZero();
        assertThat(seller.getEddiesArea()).containsExactly(first, second);
    }

    @Test
    @DisplayName("R3 - Refus : hors tour, hors phase MAIN, carte hors main, partie terminée")
    void testR3_SellCard_IllegalContexts() {
        CardInstance inHand = GameFixtures.handCard(state, "p1", GameFixtures.unit("in-hand", 1, 1));
        CardInstance onField = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("on-field", 1, 1));
        CardInstance rivalCard = GameFixtures.handCard(state, "p2", GameFixtures.unit("rival", 1, 1));

        // Ce n'est pas le tour de p2.
        assertThatThrownBy(() -> new SellCardCommand("p2", rivalCard.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Ce n'est pas le tour de p2");

        // On ne vend qu'une carte de sa main (pas une Unit déjà posée).
        assertThatThrownBy(() -> new SellCardCommand("p1", onField.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("On ne vend qu'une carte de sa main");

        // On ne vend qu'en phase MAIN.
        state.setPhase(Phase.COMBAT);
        assertThatThrownBy(() -> new SellCardCommand("p1", inHand.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("On ne vend qu'en phase Main");
        state.setPhase(Phase.MAIN);

        // Joueur inconnu dans la partie.
        assertThatThrownBy(() -> new SellCardCommand("p3", inHand.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Joueur inconnu");

        // Partie terminée : plus aucune action de jeu.
        state.setWinner("p2", "abandon");
        assertThatThrownBy(() -> new SellCardCommand("p1", inHand.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Partie terminée");

        // Aucune de ces tentatives n'a muté l'état.
        assertThat(inHand.getZone()).isEqualTo(Zone.HAND);
        assertThat(state.getPlayer("p1").getEddiesArea()).isEmpty();
        assertThat(state.getPlayer("p1").hasSoldThisTurn()).isFalse();
    }

    @Test
    @DisplayName("R3 - Contrat de la commande : arguments obligatoires, type et intention")
    void testR3_SellCard_CommandContract() {
        UUID instanceId = UUID.randomUUID();
        SellCardCommand command = new SellCardCommand("p1", instanceId);

        assertThat(command.getPlayerId()).isEqualTo("p1");
        assertThat(command.getCardInstanceId()).isEqualTo(instanceId);
        assertThat(command.actionType()).isEqualTo("SELL_CARD");
        assertThat(command.describe()).contains("p1").contains("vendre");

        assertThatThrownBy(() -> new SellCardCommand(null, instanceId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("joueur");
        assertThatThrownBy(() -> new SellCardCommand("p1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carte");
    }

    // ------------------------------------------------------------------
    // Helpers de lecture du journal de diagnostic
    // ------------------------------------------------------------------

    /** Entrée {@code SELL_CARD} en succès (la vente réussie du test). */
    private GameLogEntry saleEntry() {
        return state.getGameLog().getEntries().stream()
                .filter(entry -> "SELL_CARD".equals(entry.getActionType())
                        && entry.getResult() == GameActionResult.SUCCESS)
                .findFirst()
                .orElseThrow(() -> new AssertionError("vente réussie absente du journal de diagnostic"));
    }
}
