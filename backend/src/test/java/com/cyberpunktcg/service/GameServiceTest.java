package com.cyberpunktcg.service;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardRarity;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SellCardCommand;
import com.cyberpunktcg.repository.CardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * Tests du service de partie avec catalogue simulé (Mockito, sans Spring) :
 * création, exécution des commandes, vues masquées.
 */
@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private CardRepository cardRepository;

    private GameService gameService;

    @BeforeEach
    void setUp() {
        gameService = new GameService(cardRepository);
    }

    @Test
    void createGame_repartitLegendsEtDistribueSixCartes() {
        List<String> idsOne = deckIds("a");
        List<String> idsTwo = deckIds("b");
        stubCatalog(idsOne, idsTwo);

        GameState state = gameService.createGame("p1", "p2", idsOne, idsTwo);

        assertThat(state.getGameId()).isNotBlank();
        assertThat(state.getTurn().getActivePlayerId()).isEqualTo("p1");
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(state.getPlayer("p1").getLegendsArea()).hasSize(3);
        assertThat(state.getPlayer("p1").getLegendsArea())
                .allMatch(CardInstance::isFaceDown);
        assertThat(state.getPlayer("p1").getHand()).hasSize(6);
        assertThat(state.getPlayer("p1").getDeck()).hasSize(4);
        assertThat(state.getPlayer("p1").getFixerDice()).hasSize(6);
        assertThat(state.getPlayer("p1").getGigs()).isEmpty();
        assertThat(state.getPlayer("p2").getHand()).hasSize(6);
    }

    @Test
    void createGame_carteInconnue_rejet404() {
        org.mockito.Mockito.when(cardRepository.findAllById(anyList()))
                .thenReturn(Collections.<Card>emptyList());

        List<String> ids = new ArrayList<String>();
        ids.add("missing-card");
        try {
            gameService.createGame("p1", "p2", ids, ids);
            org.junit.jupiter.api.Assertions.fail("carte inconnue acceptée");
        } catch (ResponseStatusException error) {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void executeCommand_validePuisExecute() {
        List<String> idsOne = deckIds("a");
        List<String> idsTwo = deckIds("b");
        stubCatalog(idsOne, idsTwo);
        GameState state = gameService.createGame("p1", "p2", idsOne, idsTwo);
        CardInstance toSell = state.getPlayer("p1").getHand().get(0);

        List<GameEvent> events = gameService.executeCommand(state.getGameId(),
                new SellCardCommand("p1", toSell.getInstanceId()));

        assertThat(events).isNotEmpty();
        assertThat(gameService.getGameStateInternal(state.getGameId())
                .getPlayer("p1").getEddies()).isEqualTo(1);

        assertThatThrownBy(() -> gameService.executeCommand(state.getGameId(),
                new PlayCardCommand("p1", UUID.randomUUID())))
                .isInstanceOf(GameRuleException.class);
    }

    @Test
    void getGameState_masqueLesSecretsAdverses() {
        List<String> idsOne = deckIds("a");
        List<String> idsTwo = deckIds("b");
        stubCatalog(idsOne, idsTwo);
        GameState state = gameService.createGame("p1", "p2", idsOne, idsTwo);
        CardInstance spyInHand = state.getPlayer("p2").getHand().get(0);
        CardInstance spyLegend = state.getPlayer("p2").getLegendsArea().get(0);

        GameState rivalView = gameService.getGameState(state.getGameId(), "p1");
        GameState ownView = gameService.getGameState(state.getGameId(), "p2");

        assertThat(findIn(rivalView, "p2", spyInHand.getInstanceId()).getCardId()).isEqualTo("hidden");
        assertThat(findIn(rivalView, "p2", spyInHand.getInstanceId()).getBasePower()).isNull();
        assertThat(findIn(rivalView, "p2", spyLegend.getInstanceId()).getCardId()).isEqualTo("hidden");
        assertThat(findIn(ownView, "p2", spyInHand.getInstanceId()).getCardId())
                .isEqualTo(spyInHand.getCardId());
        assertThat(rivalView.getPlayer("p2").getHand()).hasSize(6);
        assertThat(rivalView.getPlayer("p2").getDeck()).hasSize(4);
        assertThat(rivalView.getPlayer("p2").getDeck().get(0).getCardId()).isEqualTo("hidden");
        // Le deck est masqué même pour son propriétaire (seule la taille est publique).
        assertThat(ownView.getPlayer("p2").getDeck().get(0).getCardId()).isEqualTo("hidden");
    }

    @Test
    void getGameState_partieInconnue_rejet404() {
        try {
            gameService.getGameState("nope", "p1");
            org.junit.jupiter.api.Assertions.fail("partie inconnue acceptée");
        } catch (ResponseStatusException error) {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------
    // Fabriques locales
    // ------------------------------------------------------------------

    /** 3 legends + 10 units (main 6 + deck 4 après distribution). */
    private static List<String> deckIds(String side) {
        List<String> ids = new ArrayList<String>();
        ids.add("legend-" + side + "-1");
        ids.add("legend-" + side + "-2");
        ids.add("legend-" + side + "-3");
        for (int i = 0; i < 10; i++) {
            ids.add("unit-" + side + "-" + i);
        }
        return ids;
    }

    private void stubCatalog(List<String> idsOne, List<String> idsTwo) {
        List<Card> catalog = new ArrayList<Card>();
        for (String id : idsOne) {
            catalog.add(testCard(id));
        }
        for (String id : idsTwo) {
            catalog.add(testCard(id));
        }
        Answer<List<Card>> answer = new Answer<List<Card>>() {
            @Override
            @SuppressWarnings("unchecked")
            public List<Card> answer(org.mockito.invocation.InvocationOnMock invocation) {
                List<String> wanted = (List<String>) invocation.getArgument(0);
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

    private static Card testCard(String id) {
        CardType type = id.startsWith("legend-") ? CardType.LEGEND : CardType.UNIT;
        Integer power = type == CardType.LEGEND ? null : 2;
        return new Card(id, "Test " + id, null, type, CardColor.BLUE, 1,
                1, power, null, new ArrayList<String>(),
                new ArrayList<CardKeyword>(), "test", new ArrayList<String>(),
                null, "TEST", "001", CardRarity.COMMON);
    }

    private static CardInstance findIn(GameState view, String playerId, UUID instanceId) {
        return view.getPlayer(playerId).findAnywhere(instanceId)
                .orElseThrow(() -> new IllegalStateException("Exemplaire absent de la vue"));
    }
}
