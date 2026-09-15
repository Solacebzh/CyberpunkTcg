package com.cyberpunktcg.engine;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameActionResult;
import com.cyberpunktcg.domain.game.GameLogEntry;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.command.AttackCommand;
import com.cyberpunktcg.engine.command.EndTurnCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.engine.command.SpendLegendCommand;
import com.cyberpunktcg.repository.CardRepository;
import com.cyberpunktcg.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Tests d'intégration des règles critiques (feature 6.5) : chaque scénario joue
 * une partie réelle à travers {@link GameService} (validation, exécution,
 * journal de diagnostic) et vérifie les sept règles confirmées.
 *
 * <ul>
 *   <li>{@code testFullGameFlow} — partie complète : mise en place, Eddies des
 *   Legends, phases, pioche, lancer de Gig, vol de Gig ;</li>
 *   <li>{@code testPlayCardCostValidation} — coûts en Eddies et plafond de RAM
 *   par couleur ;</li>
 *   <li>{@code testSellCardLimit} — une seule vente par tour ;</li>
 *   <li>{@code testCombatWithBlocker} — interception par BLOCKER et combat
 *   (puissance = dégâts, unités vaincues à la défausse) ;</li>
 *   <li>{@code testVictoryCondition} — victoire à 7 Gigs au début du tour ;</li>
 *   <li>{@code testLegendFlip} — retournement de Legend gratuit, effet FLIP
 *   (pas ON_PLAY) ;</li>
 *   <li>{@code testQuickReaction} — réactions QUICK uniquement, fermeture de la
 *   fenêtre en fin de tour.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GameIntegrationTest {

    /** {@link Random} figé : p1 est toujours le premier joueur (partie lisible). */
    private static final Random ALWAYS_TRUE = new Random() {
        @Override
        public boolean nextBoolean() {
            return true;
        }
    };

    @Mock
    private CardRepository cardRepository;

    private GameService service;

    @BeforeEach
    void setUp() {
        service = new GameService(cardRepository, ALWAYS_TRUE);
    }

    // ------------------------------------------------------------------
    // 1. Flux complet d'une partie
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testFullGameFlow — partie complète : Legends, pioche, Gig, vol")
    void testFullGameFlow() {
        GameState state = newGame();
        assertThat(state.getTurn().getActivePlayerId()).isEqualTo("p1");
        assertThat(state.getPlayer("p1").countSpentLegends()).isEqualTo(2);

        // Économie : les Legends face cachée restantes servent d'Eddies.
        CardInstance thirdLegend = state.getPlayer("p1").legendsAvailableForEddies().get(0);
        execute(state, new SpendLegendCommand("p1", thirdLegend.getInstanceId()));
        assertThat(state.getPlayer("p1").getEddies()).isEqualTo(1);
        assertThat(thirdLegend.isExhausted()).isTrue();

        // La Legend inclinée ne se redresse pas (dépensée définitivement).
        execute(state, new EndTurnCommand("p1"));
        execute(state, new EndTurnCommand("p2"));
        assertThat(thirdLegend.isExhausted()).isTrue();
        assertThat(state.getPlayer("p1").legendsAvailableForEddies()).isEmpty();

        // Tour 2 de p2 : phase DRAW → pioche d'une carte → lancer d'un Gig → MAIN.
        assertThat(state.getTurn().getNumber()).isEqualTo(3);
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(state.getPlayer("p2").getGigs()).hasSize(1);
        // p1 a pioché au début de son 2e tour (phase DRAW) : 6 cartes de mise en place + 1.
        assertThat(state.getPlayer("p1").getHand()).hasSize(7);
        assertThat(state.getPlayer("p2").getHand()).hasSize(7);

        // Vol de Gig : p1 a lancé son premier Gig au début de ce 2e tour (1 Gig),
        // puis attaque la Gig Area de p2 avec une Unit prête : 2 Gigs contre 0.
        assertThat(state.getPlayer("p1").getGigCount()).isEqualTo(1);
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("runner", 2, 3));
        execute(state, new AttackCommand("p1", attacker.getInstanceId()));
        assertThat(state.getPhase()).isEqualTo(Phase.COMBAT);
        assertThat(state.getPlayer("p1").getGigCount()).isEqualTo(2);
        assertThat(state.getPlayer("p2").getGigCount()).isZero();
        assertThat(attacker.isExhausted()).isTrue();

        // La partie se poursuit et la chronologie est consignée dans le journal.
        execute(state, new EndTurnCommand("p1"));
        execute(state, new EndTurnCommand("p2"));
        assertThat(state.isGameOver()).isFalse();
        assertThat(state.getTurn().getNumber()).isEqualTo(5);

        List<String> descriptions = descriptions(state, 60);
        assertThat(descriptions).anyMatch(line -> line.contains("Phase DRAW"));
        assertThat(descriptions).anyMatch(line -> line.contains("Vérification victoire"));
        assertThat(descriptions).anyMatch(line -> line.contains("Lancer de Gig"));
        assertThat(descriptions).anyMatch(line -> line.contains("incline"));
        assertThat(descriptions).anyMatch(line -> line.contains("vole un Gig"));
        assertThat(state.getGameLog().size()).isLessThanOrEqualTo(GameConstants.MAX_LOG_ENTRIES);
    }

    // ------------------------------------------------------------------
    // 2. Coûts et plafond de RAM
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testPlayCardCostValidation — Eddies insuffisants refusés, plafond de RAM vérifié")
    void testPlayCardCostValidation() {
        List<Card> legendsOne = new ArrayList<Card>();
        legendsOne.add(GameFixtures.coloredLegend("legend-blue-1", CardColor.BLUE, 2, null));
        legendsOne.add(GameFixtures.coloredLegend("legend-blue-2", CardColor.BLUE, 2, null));
        legendsOne.add(GameFixtures.coloredLegend("legend-red-1", CardColor.RED, 2, null));
        GameState state = newGame(legendsOne, defaultLegends("b"));

        CardInstance expensive = GameFixtures.handCard(state, "p1",
                GameFixtures.coloredUnit("expensive", CardColor.RED, 1, 4, 3));
        String refusal = expectRefusal(state, new PlayCardCommand("p1", expensive.getInstanceId()));
        assertThat(refusal).contains("Eddies insuffisants");
        assertThat(state.getPlayer("p1").getField()).isEmpty();

        GameFixtures.giveEddies(state, "p1", 4);
        execute(state, new PlayCardCommand("p1", expensive.getInstanceId()));
        assertThat(state.getPlayer("p1").getEddies()).isZero();
        assertThat(state.getPlayer("p1").getField()).contains(expensive);
        assertThat(descriptions(state, 10))
                .anyMatch(line -> line.contains("coût: 4 Eddies"));

        // Plafond de RAM : 2 Legends bleues à 2 RAM (plafond 4) et 1 rouge à 2 RAM (plafond 2).
        Player player = state.getPlayer("p1");
        assertThat(player.ramCeilingFor(CardColor.BLUE)).isEqualTo(4);
        assertThat(player.ramCeilingFor(CardColor.RED)).isEqualTo(2);
        assertThat(player.ramCeilingFor(CardColor.GREEN)).isZero();

        CardInstance tooBig = GameFixtures.handCard(state, "p1",
                GameFixtures.coloredUnit("too-big", CardColor.RED, 3, 1, 1));
        assertThat(expectRefusal(state, new PlayCardCommand("p1", tooBig.getInstanceId())))
                .contains("RAM rouge");

        // La RAM n'est pas consommée : plusieurs cartes à 2 RAM restent jouables.
        GameFixtures.giveEddies(state, "p1", 10);
        for (int i = 0; i < 3; i++) {
            CardInstance red = GameFixtures.handCard(state, "p1",
                    GameFixtures.coloredUnit("red-" + i, CardColor.RED, 2, 1, 1));
            execute(state, new PlayCardCommand("p1", red.getInstanceId()));
        }
        assertThat(player.getField()).hasSize(4);

        // Le refus est consigné dans le journal de diagnostic (ILLEGAL + motif).
        assertThat(state.getGameLog().getEntries()).anyMatch(entry ->
                entry.getResult() == GameActionResult.ILLEGAL
                        && entry.getDescription().contains("REFUSÉ")
                        && entry.getDetails().containsKey("reason"));
    }

    // ------------------------------------------------------------------
    // 3. Une seule vente par tour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testSellCardLimit — une vente par tour, révélée puis face cachée")
    void testSellCardLimit() {
        GameState state = newGame();
        CardInstance first = GameFixtures.handCard(state, "p1",
                GameFixtures.coloredUnit("sell-1", CardColor.RED, 1, 2, 2));
        CardInstance second = GameFixtures.handCard(state, "p1",
                GameFixtures.coloredUnit("sell-2", CardColor.RED, 1, 3, 3));

        execute(state, new SellCardCommand("p1", first.getInstanceId()));
        assertThat(state.getPlayer("p1").getEddies()).isEqualTo(1);
        assertThat(first.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(first.isFaceDown()).isTrue();
        assertThat(state.getPlayer("p1").hasSoldThisTurn()).isTrue();
        assertThat(descriptions(state, 10)).anyMatch(line -> line.contains("vend"));
        assertThat(state.getGameLog().getEntries()).anyMatch(entry ->
                "CARD_REVEALED".equals(entry.getActionType()));

        assertThat(expectRefusal(state, new SellCardCommand("p1", second.getInstanceId())))
                .contains("Une seule vente par tour");
        assertThat(second.getZone()).isEqualTo(Zone.HAND);

        // La limite se réinitialise au tour suivant.
        execute(state, new EndTurnCommand("p1"));
        execute(state, new EndTurnCommand("p2"));
        assertThat(state.getPlayer("p1").hasSoldThisTurn()).isFalse();
        execute(state, new SellCardCommand("p1", second.getInstanceId()));
        assertThat(state.getPlayer("p1").getEddies()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 4. Combat et interception par BLOCKER
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testCombatWithBlocker — BLOCKER intercepte, puissance = dégâts, défausse")
    void testCombatWithBlocker() {
        GameState state = newGame();
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("attacker", 2, 4));
        CardInstance blocker = GameFixtures.fieldCard(state, "p2",
                GameFixtures.unit("blocker", 3, 5, CardKeyword.BLOCKER));
        CardInstance bystander = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("bystander", 1, 2));
        GameFixtures.addGigs(state, "p2", 3);

        // Un BLOCKER prêt doit intercepter : ni une autre Unit, ni la Gig Area.
        assertThat(expectRefusal(state, new AttackCommand("p1", attacker.getInstanceId(),
                bystander.getInstanceId()))).contains("BLOCKER");
        assertThat(expectRefusal(state, new AttackCommand("p1", attacker.getInstanceId())))
                .contains("intercepté");

        // L'attaquant (4) est vaincu par le BLOCKER (5) : vaincu = défaussé.
        execute(state, new AttackCommand("p1", attacker.getInstanceId(), blocker.getInstanceId()));
        assertThat(state.getPlayer("p1").getField()).isEmpty();
        assertThat(state.getPlayer("p1").getTrash()).contains(attacker);
        assertThat(state.getPlayer("p2").getField()).contains(blocker);
        assertThat(descriptions(state, 10)).anyMatch(line ->
                line.contains("Combat") && line.contains("4") && line.contains("5"));

        // Un attaquant plus fort défausse le BLOCKER (damage = puissance).
        CardInstance big = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("big", 5, 6));
        execute(state, new AttackCommand("p1", big.getInstanceId(), blocker.getInstanceId()));
        assertThat(state.getPlayer("p2").getField()).doesNotContain(blocker);
        assertThat(state.getPlayer("p2").getTrash()).contains(blocker);
        assertThat(state.getPlayer("p1").getField()).contains(big);
    }

    // ------------------------------------------------------------------
    // 5. Victoire à 7 Gigs au début du tour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testVictoryCondition — 7 Gigs vérifiés au début du tour, pas à la fin")
    void testVictoryCondition() {
        GameState state = newGame();
        GameFixtures.addGigs(state, "p1", 1, 1, 1, 1, 1, 1);

        execute(state, new EndTurnCommand("p1"));
        // Tour de p2 : la victoire de p1 n'est PAS vérifiée pendant son tour.
        assertThat(state.isGameOver()).isFalse();
        assertThat(state.getPlayer("p1").getGigCount()).isEqualTo(6);
        execute(state, new EndTurnCommand("p2"));

        // Début du tour 3 de p1 : 6 Gigs → pas encore la victoire, puis lancer de Gig → 7 Gigs.
        assertThat(state.getTurn().getNumber()).isEqualTo(3);
        assertThat(state.isGameOver()).isFalse();
        assertThat(state.getPlayer("p1").getGigCount()).isEqualTo(7);

        execute(state, new EndTurnCommand("p1"));
        execute(state, new EndTurnCommand("p2"));

        // Début du tour 5 de p1 : 7 Gigs au début du tour = victoire immédiate.
        assertThat(state.isGameOver()).isTrue();
        assertThat(state.getWinnerId()).isEqualTo("p1");
        assertThat(state.getEndReason()).contains("7");
        List<String> log = descriptions(state, 60);
        assertThat(log).anyMatch(line -> line.contains("Vérification victoire")
                && line.contains("6/7"));
        assertThat(log).anyMatch(line -> line.contains("VICTOIRE"));
    }

    // ------------------------------------------------------------------
    // 6. Retournement de Legend
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testLegendFlip — Legend retournée gratuitement, effet FLIP seul")
    void testLegendFlip() {
        GameState state = newGame();
        Card definition = GameFixtures.coloredLegend("legend-flip", CardColor.RED, 2,
                "FLIP:DRAW:2");
        CardInstance legend = GameFixtures.legendCard(state, "p1", definition, true);
        GameFixtures.handCard(state, "p1", GameFixtures.coloredUnit("decoy", CardColor.RED, 1, 1, 1));
        int handBefore = state.getPlayer("p1").getHand().size();
        int deckBefore = state.getPlayer("p1").getDeck().size();

        // Aucun Eddie disponible : le retournement est gratuit.
        assertThat(state.getPlayer("p1").getEddies()).isZero();
        execute(state, new PlayCardCommand("p1", legend.getInstanceId()));

        assertThat(legend.isFaceDown()).isFalse();
        assertThat(state.getPlayer("p1").getLegendsArea()).contains(legend);
        assertThat(state.getPlayer("p1").getEddies()).isZero();
        // Effet FLIP : pioche 2 (la main gagne 2 cartes, le deck en perd 2).
        assertThat(state.getPlayer("p1").getHand()).hasSize(handBefore + 2);
        assertThat(state.getPlayer("p1").getDeck()).hasSize(deckBefore - 2);
        assertThat(descriptions(state, 10)).anyMatch(line ->
                line.contains("retourne la Legend") && line.contains("effet FLIP"));

        // Une Legend déjà retournée n'est plus jouable (et ne sert plus d'Eddie).
        assertThat(expectRefusal(state, new PlayCardCommand("p1", legend.getInstanceId())))
                .contains("face cachée");
    }

    // ------------------------------------------------------------------
    // 7. Réactions QUICK uniquement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("testQuickReaction — seul QUICK réagit pendant la fenêtre, fermée en fin de tour")
    void testQuickReaction() {
        GameState state = newGame();
        CardInstance attacker = GameFixtures.fieldCard(state, "p1", GameFixtures.unit("attacker", 2, 4));
        CardInstance defender = GameFixtures.fieldCard(state, "p2", GameFixtures.unit("defender", 1, 2));
        GameFixtures.addGigs(state, "p2", 2);
        CardInstance slowProgram = GameFixtures.handCard(state, "p2",
                GameFixtures.coloredProgram("slow", CardColor.RED, 1, 0, "ON_PLAY:DRAW:1"));
        CardInstance quickProgram = GameFixtures.handCard(state, "p2",
                GameFixtures.coloredProgram("quick", CardColor.RED, 1, 0, "QUICK:DRAW:1",
                        CardKeyword.QUICK));

        execute(state, new AttackCommand("p1", attacker.getInstanceId(), defender.getInstanceId()));
        assertThat(state.isReactionWindowOpen()).isTrue();
        assertThat(state.getReactionWindow().getDefendingPlayerId()).isEqualTo("p2");
        assertThat(descriptions(state, 10)).anyMatch(line ->
                line.contains("Fenêtre de réaction ouverte") && line.contains("QUICK"));

        // Une carte sans QUICK est refusée pendant la fenêtre, même utile.
        assertThat(expectRefusal(state, new PlayCardCommand("p2", slowProgram.getInstanceId())))
                .contains("QUICK");
        assertThat(slowProgram.getZone()).isEqualTo(Zone.HAND);

        // Une carte QUICK est acceptée : le défenseur agit hors de son tour.
        int handBefore = state.getPlayer("p2").getHand().size();
        execute(state, new PlayCardCommand("p2", quickProgram.getInstanceId()));
        assertThat(state.getPlayer("p2").getTrash()).contains(quickProgram);
        assertThat(state.getPlayer("p2").getHand()).hasSize(handBefore - 1 + 1);

        // La fenêtre ne survit pas à la fin du tour.
        execute(state, new EndTurnCommand("p1"));
        assertThat(state.isReactionWindowOpen()).isFalse();
        assertThat(descriptions(state, 10)).anyMatch(line ->
                line.contains("Fenêtre de réaction fermée"));
    }

    // ------------------------------------------------------------------
    // Fabriques locales
    // ------------------------------------------------------------------

    /** Partie de test : p1 commence, deck de 3 Legends rouges (2 RAM) + 9 Units par joueur. */
    private GameState newGame() {
        return newGame(defaultLegends("a"), defaultLegends("b"));
    }

    /** Partie de test avec des Legends choisies (plafonds de RAM contrôlés). */
    private GameState newGame(List<Card> legendsOne, List<Card> legendsTwo) {
        List<Card> catalog = new ArrayList<Card>();
        List<String> idsOne = new ArrayList<String>();
        List<String> idsTwo = new ArrayList<String>();
        for (Card legend : legendsOne) {
            catalog.add(legend);
            idsOne.add(legend.getId());
        }
        for (Card legend : legendsTwo) {
            catalog.add(legend);
            idsTwo.add(legend.getId());
        }
        for (int i = 0; i < 9; i++) {
            Card unitOne = GameFixtures.coloredUnit("unit-a-" + i, CardColor.RED, 1, 1, 1);
            Card unitTwo = GameFixtures.coloredUnit("unit-b-" + i, CardColor.RED, 1, 1, 1);
            catalog.add(unitOne);
            catalog.add(unitTwo);
            idsOne.add(unitOne.getId());
            idsTwo.add(unitTwo.getId());
        }
        stubCatalog(catalog);
        return service.createGame("p1", "p2", idsOne, idsTwo);
    }

    private List<Card> defaultLegends(String side) {
        List<Card> legends = new ArrayList<Card>();
        for (int i = 0; i < 3; i++) {
            legends.add(GameFixtures.coloredLegend("legend-" + side + "-" + i, CardColor.RED, 2, null));
        }
        return legends;
    }

    private void execute(GameState state, com.cyberpunktcg.engine.command.GameCommand command) {
        service.executeCommand(state.getGameId(), command);
    }

    /** Exécute une commande en attendant un refus et retourne le motif consigné. */
    private String expectRefusal(GameState state,
                                 com.cyberpunktcg.engine.command.GameCommand command) {
        assertThatThrownBy(() -> service.executeCommand(state.getGameId(), command))
                .isInstanceOf(GameRuleException.class);
        List<GameLogEntry> log = service.getGameLog(state.getGameId(), 200);
        GameLogEntry last = log.get(log.size() - 1);
        assertThat(last.getResult()).isEqualTo(GameActionResult.ILLEGAL);
        assertThat(last.getDescription()).contains("REFUSÉ");
        return String.valueOf(last.getDetails().get("reason"));
    }

    private List<String> descriptions(GameState state, int tail) {
        List<String> lines = new ArrayList<String>();
        for (GameLogEntry entry : state.getGameLog().recent(tail)) {
            lines.add(entry.getDescription());
        }
        return lines;
    }

    private void stubCatalog(List<Card> catalog) {
        Answer<List<Card>> answer = new Answer<List<Card>>() {
            @Override
            public List<Card> answer(org.mockito.invocation.InvocationOnMock invocation) {
                List<?> wanted = invocation.getArgument(0);
                List<Card> result = new ArrayList<Card>();
                for (Card card : catalog) {
                    if (wanted.contains(card.getId())) {
                        result.add(card);
                    }
                }
                return result;
            }
        };
        org.mockito.Mockito.when(cardRepository.findAllById(anyList())).thenAnswer(answer);
    }

    /** Instance de carte par identifiant dans une zone donnée (aide d'assertion). */
    static CardInstance find(GameState state, String playerId, UUID instanceId) {
        return state.getPlayer(playerId).findAnywhere(instanceId)
                .orElseThrow(() -> new IllegalStateException("Instance absente : " + instanceId));
    }
}
