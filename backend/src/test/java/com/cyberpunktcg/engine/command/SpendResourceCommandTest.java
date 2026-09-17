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
 * Mini-Feature 4 — <strong>« Générer des Eddies »</strong> (règle R4 du mini-lot).
 *
 * <p>Règle officielle appliquée : pour obtenir 1 Eddie
 * ({@link Player#getAvailableEddies()}), le joueur utilise une ACTION pendant
 * sa <strong>Main Phase</strong> : il incline soit une Legend de sa
 * {@link Zone#LEGENDS_AREA} ({@code exhausted = true} → +1 Eddie), soit une
 * carte vendue de sa {@link Zone#EDDIES_AREA} (même mécanique). La commande
 * unifiée {@link SpendResourceCommand} accepte en cible l'ID de l'une ou de
 * l'autre carte et valide : propriétaire = ordonnateur, zone = Legends Area
 * <em>ou</em> Eddies Area, {@code exhausted == false}.</p>
 *
 * <p>Les actions filaires {@code SPEND_LEGEND} / {@code SPEND_EDDIES} restent
 * disponibles : {@link SpendLegendCommand} et {@link SpendEddiesCommand} sont
 * devenus des alias légers héritant de la même règle unifiée (R4).</p>
 *
 * <p>Tests purs (aucun contexte Spring) : le duel vient de
 * {@link GameFixtures#freshDuel()} — {@code p1} actif, phase {@code MAIN},
 * tour 1, 0 Eddie, mains vides.</p>
 */
class SpendResourceCommandTest {

    private GameState state;
    private Player p1;

    @BeforeEach
    void setUp() {
        state = GameFixtures.freshDuel();
        p1 = state.getPlayer("p1");
    }

    // ------------------------------------------------------------------
    // Cas de succès — les deux ressources unifiées
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R4 - Incliner sa Legend : +1 Eddie, carte inclinée (exhausted=true), reste en Legends Area")
    void testR4_SpendLegend_Gives1Eddie_AndBecomesExhausted() {
        // Legend face cachée (cas d'usage principal : réserve d'Eddies du setup).
        CardInstance legend = GameFixtures.legendCard(state, "p1",
                GameFixtures.legend("oracle", null), true);
        assertThat(p1.getEddies()).isZero();
        assertThat(legend.getZone()).isEqualTo(Zone.LEGENDS_AREA);
        assertThat(legend.isExhausted()).isFalse();

        List<GameEvent> events = new SpendResourceCommand("p1", legend.getInstanceId()).execute(state);

        // +1 Eddie crédité à la réserve (availableEddies).
        assertThat(p1.getEddies()).isEqualTo(1);
        assertThat(p1.getAvailableEddies()).isEqualTo(1);
        // La Legend est inclinée mais reste sur le terrain (redressée au START suivant).
        assertThat(legend.isExhausted()).isTrue();
        assertThat(legend.getZone()).isEqualTo(Zone.LEGENDS_AREA);
        assertThat(p1.getLegendsArea()).containsExactly(legend);
        assertThat(p1.legendsAvailableForEddies()).isEmpty();

        // Éléments publics + journal de diagnostic.
        assertThat(events).anyMatch(event -> event.getType() == GameEventType.EFFECT_RESOLVED);
        GameLogEntry entry = spendEntry();
        assertThat(entry.getResult()).isEqualTo(GameActionResult.SUCCESS);
        assertThat(entry.getDescription()).contains(legend.getName()).contains("+1 Eddie");
        assertThat(entry.getDetails())
                .containsEntry("cardId", legend.getCardId())
                .containsEntry("zone", Zone.LEGENDS_AREA.name())
                .containsEntry("exhausted", true)
                .containsEntry("eddieGained", GameConstants.EDDIES_PER_RESOURCE)
                .containsEntry("eddiesTotal", 1);
    }

    @Test
    @DisplayName("R4 - Incliner une carte vendue (Eddies Area) : +1 Eddie, carte inclinée, reste face cachée")
    void testR4_SpendEddieCard_Gives1Eddie_AndBecomesExhausted() {
        // Flux officiel : vente (Mini-Feature 3) → ressource posée prête, puis inclinaison (R4).
        // Mini-Feature 10C : on vend un GEAR (les Units/Legends sont invendables).
        CardInstance sold = GameFixtures.handCard(state, "p1", GameFixtures.gear("scrap", 2, 2));
        new SellCardCommand("p1", sold.getInstanceId()).execute(state);
        assertThat(sold.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(sold.isFaceDown()).isTrue();
        assertThat(sold.isExhausted()).isFalse();
        assertThat(p1.getEddies()).isZero(); // la vente ne crédite rien

        new SpendResourceCommand("p1", sold.getInstanceId()).execute(state);

        // +1 Eddie crédité à la réserve.
        assertThat(p1.getEddies()).isEqualTo(1);
        assertThat(p1.getAvailableEddies()).isEqualTo(1);
        // La carte est inclinée, reste dans l'Eddies Area, toujours face cachée.
        assertThat(sold.isExhausted()).isTrue();
        assertThat(sold.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(sold.isFaceDown()).isTrue();
        assertThat(p1.getEddiesArea()).containsExactly(sold);
        assertThat(p1.eddiesAvailableForEddies()).isEmpty();

        GameLogEntry entry = spendEntry();
        assertThat(entry.getResult()).isEqualTo(GameActionResult.SUCCESS);
        assertThat(entry.getDetails())
                .containsEntry("cardId", sold.getCardId())
                .containsEntry("zone", Zone.EDDIES_AREA.name())
                .containsEntry("exhausted", true)
                .containsEntry("eddieGained", GameConstants.EDDIES_PER_RESOURCE)
                .containsEntry("eddiesTotal", 1);
    }

    @Test
    @DisplayName("R4 - Cycle : la ressource est redressée au tour suivant et rapporte à nouveau 1 Eddie")
    void testR4_ResourceReadyAgainNextTurn() {
        CardInstance sold = GameFixtures.handCard(state, "p1", GameFixtures.program("recycler", 1));
        new SellCardCommand("p1", sold.getInstanceId()).execute(state);
        new SpendResourceCommand("p1", sold.getInstanceId()).execute(state);
        assertThat(sold.isExhausted()).isTrue();
        assertThat(p1.getEddies()).isEqualTo(1);

        // Mini-Feature 5 : chaque fin de tour est suivie de la phase DRAW interactive
        // du joueur entrant (pioche + choix du dé), jouée ici par la fixture.
        GameFixtures.passTurn(state, "p1");
        GameFixtures.passTurn(state, "p2");

        // START PHASE : carte redressée, réserve d'Eddies remise à 0 (R2).
        assertThat(sold.isExhausted()).isFalse();
        assertThat(p1.getEddies()).isZero();

        new SpendResourceCommand("p1", sold.getInstanceId()).execute(state);
        assertThat(p1.getEddies()).isEqualTo(1);
        assertThat(sold.isExhausted()).isTrue();
    }

    // ------------------------------------------------------------------
    // Cas de refus — limite, propriété, zone, timing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R4 - Refus : carte déjà inclinée (1 €$ par tour et par carte)")
    void testR4_AlreadyExhausted_Refused() {
        CardInstance legend = GameFixtures.legendCard(state, "p1",
                GameFixtures.legend("oracle", null), true);
        new SpendResourceCommand("p1", legend.getInstanceId()).execute(state);
        assertThat(p1.getEddies()).isEqualTo(1);

        SpendResourceCommand illegal = new SpendResourceCommand("p1", legend.getInstanceId());
        assertThatThrownBy(() -> illegal.validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("déjà inclinée");
        assertThatThrownBy(() -> illegal.execute(state))
                .isInstanceOf(GameRuleException.class);

        // Aucune mutation : toujours 1 Eddie, la Legend reste inclinée.
        assertThat(p1.getEddies()).isEqualTo(1);
        assertThat(legend.isExhausted()).isTrue();
        assertThat(p1.legendsAvailableForEddies()).isEmpty();
    }

    @Test
    @DisplayName("R4 - Refus : carte du rival, carte hors zones ressources, instance inconnue")
    void testR4_OwnershipAndZone_Refused() {
        CardInstance rivalLegend = GameFixtures.legendCard(state, "p2",
                GameFixtures.legend("rival", null), true);
        CardInstance inHand = GameFixtures.handCard(state, "p1", GameFixtures.unit("pocket", 1, 1));
        CardInstance onField = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("deployed", 1, 1));

        // Carte du rival : même dans une zone de ressource, ce n'est pas la sienne.
        assertThatThrownBy(() -> new SpendResourceCommand("p1", rivalLegend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("n'appartient pas");
        // Carte du joueur mais hors Legends Area / Eddies Area.
        assertThatThrownBy(() -> new SpendResourceCommand("p1", inHand.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Eddies Area");
        assertThatThrownBy(() -> new SpendResourceCommand("p1", onField.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Eddies Area");
        // Instance inconnue de la partie.
        assertThatThrownBy(() -> new SpendResourceCommand("p1", UUID.randomUUID()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("introuvable");

        // Aucune de ces tentatives n'a muté l'état.
        assertThat(p1.getEddies()).isZero();
        assertThat(inHand.getZone()).isEqualTo(Zone.HAND);
        assertThat(onField.getZone()).isEqualTo(Zone.FIELD);
    }

    @Test
    @DisplayName("R4 - Refus : pas son tour, hors phase MAIN, joueur inconnu, partie terminée")
    void testR4_IllegalContexts_Refused() {
        CardInstance legend = GameFixtures.legendCard(state, "p1",
                GameFixtures.legend("oracle", null), true);

        // Ce n'est pas le tour de p2 (règle d'abord : joueur actif).
        assertThatThrownBy(() -> new SpendResourceCommand("p2", legend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Ce n'est pas le tour de p2");
        // Règle R4 : l'action se joue pendant la Main Phase uniquement.
        state.setPhase(Phase.COMBAT);
        assertThatThrownBy(() -> new SpendResourceCommand("p1", legend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("phase Main");
        state.setPhase(Phase.MAIN);
        // Joueur inconnu de la partie.
        assertThatThrownBy(() -> new SpendResourceCommand("p3", legend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Joueur inconnu");
        // Partie terminée : plus aucune action de jeu.
        state.setWinner("p2", "abandon");
        assertThatThrownBy(() -> new SpendResourceCommand("p1", legend.getInstanceId()).validate(state))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("Partie terminée");

        // Rien n'a été muté.
        assertThat(legend.isExhausted()).isFalse();
        assertThat(p1.getEddies()).isZero();
    }

    // ------------------------------------------------------------------
    // Contrat de la commande
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R4 - Contrat : arguments obligatoires, type d'action, intention")
    void testR4_CommandContract() {
        UUID instanceId = UUID.randomUUID();
        SpendResourceCommand command = new SpendResourceCommand("p1", instanceId);

        assertThat(command.getPlayerId()).isEqualTo("p1");
        assertThat(command.getResourceInstanceId()).isEqualTo(instanceId);
        assertThat(command.actionType()).isEqualTo("SPEND_RESOURCE");
        assertThat(command.describe()).contains("inclin").contains("Eddie");

        assertThatThrownBy(() -> new SpendResourceCommand(null, instanceId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("joueur");
        assertThatThrownBy(() -> new SpendResourceCommand("p1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carte");
    }

    // ------------------------------------------------------------------
    // Helpers de lecture du journal de diagnostic
    // ------------------------------------------------------------------

    /** Entrée {@code SPEND_RESOURCE} en succès (la dernière dépense du test). */
    private GameLogEntry spendEntry() {
        return state.getGameLog().getEntries().stream()
                .filter(entry -> "SPEND_RESOURCE".equals(entry.getActionType())
                        && entry.getResult() == GameActionResult.SUCCESS)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("dépense réussie absente du journal de diagnostic"));
    }
}
