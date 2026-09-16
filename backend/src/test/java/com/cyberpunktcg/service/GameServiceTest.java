package com.cyberpunktcg.service;

import com.cyberpunktcg.domain.card.Card;
import com.cyberpunktcg.domain.card.CardColor;
import com.cyberpunktcg.domain.card.CardKeyword;
import com.cyberpunktcg.domain.card.CardRarity;
import com.cyberpunktcg.domain.card.CardType;
import com.cyberpunktcg.domain.game.CardInstance;
import com.cyberpunktcg.domain.game.DrawStep;
import com.cyberpunktcg.domain.game.GameActionResult;
import com.cyberpunktcg.domain.game.GameEvent;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Phase;
import com.cyberpunktcg.domain.game.Zone;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.engine.GameRuleException;
import com.cyberpunktcg.engine.command.DrawCardCommand;
import com.cyberpunktcg.engine.command.PlayCardCommand;
import com.cyberpunktcg.engine.command.SelectDieCommand;
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
import java.util.Random;
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
        // Le premier joueur est tiré au sort (feature 6.5) : l'un des deux joueurs commence.
        String starter = state.getTurn().getActivePlayerId();
        assertThat(starter).isIn("p1", "p2");
        String second = "p1".equals(starter) ? "p2" : "p1";
        assertThat(state.getTurn().getNumber()).isEqualTo(1);
        // Mini-Feature 5.1 : le premier joueur démarre en phase DRAW (sous-étape
        // AWAITING_DRAW), pas en MAIN — il doit cliquer pour piocher puis lancer
        // son dé Gig avant d'entrer en phase MAIN.
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);
        for (String playerId : List.of("p1", "p2")) {
            assertThat(state.getPlayer(playerId).getLegendsArea()).hasSize(3);
            assertThat(state.getPlayer(playerId).getLegendsArea())
                    .allMatch(CardInstance::isFaceDown);
            assertThat(state.getPlayer(playerId).getHand()).hasSize(6);
            assertThat(state.getPlayer(playerId).getDeck()).hasSize(4);
            assertThat(state.getPlayer(playerId).getFixerDice()).hasSize(6);
            assertThat(state.getPlayer(playerId).getGigs()).isEmpty();
        }
        // Malus de mise en place : le premier joueur a 2 Legends déjà inclinées (1 seul Eddie).
        assertThat(state.getPlayer(starter).countSpentLegends())
                .isEqualTo(GameConstants.FIRST_PLAYER_SPENT_LEGENDS);
        assertThat(state.getPlayer(starter).legendsAvailableForEddies()).hasSize(1);
        assertThat(state.getPlayer(second).countSpentLegends()).isZero();
        assertThat(state.getPlayer(second).legendsAvailableForEddies()).hasSize(3);
        // Journal de diagnostic : la mise en place est consignée.
        assertThat(state.getGameLog().size()).isGreaterThanOrEqualTo(3);
        assertThat(state.getGameLog().getEntries()).anyMatch(entry ->
                "GAME_START".equals(entry.getActionType()));
    }

    @Test
    void createGame_tireLePremierJoueurAuSortEtAppliqueLeMalus() {
        // Graine fixe : deux parties successives partent du même joueur, la troisième peut changer.
        List<String> ids = deckIds("a");
        stubCatalog(ids, ids);
        GameService seeded = new GameService(cardRepository, new Random(7L));
        GameState first = seeded.createGame("p1", "p2", ids, ids);
        GameState second = seeded.createGame("p1", "p2", ids, ids);
        assertThat(first.getTurn().getActivePlayerId())
                .isEqualTo(second.getTurn().getActivePlayerId());
        assertThat(first.getPlayer(first.getTurn().getActivePlayerId()).countSpentLegends()).isEqualTo(2);
    }

    @Test
    void testR5_Turn1_FirstPlayer_MustDrawAndRollDie() {
        // Mini-Feature 5.1 — « Tour 1 du Premier Joueur » : le premier joueur (celui qui
        // subit le malus des 2 Legends inclinées) DOIT exécuter sa phase DRAW comme tous
        // les autres tours : clic pour piocher, puis choix et lancer d'un dé Gig.
        List<String> idsOne = deckIds("a");
        List<String> idsTwo = deckIds("b");
        stubCatalog(idsOne, idsTwo);
        GameState state = gameService.createGame("p1", "p2", idsOne, idsTwo);

        // Le jeu démarre au Tour 1 en phase DRAW, sous-étape AWAITING_DRAW pour le premier joueur.
        String starter = state.getTurn().getActivePlayerId();
        String second = "p1".equals(starter) ? "p2" : "p1";
        assertThat(state.getTurn().getNumber()).isEqualTo(1);
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DRAW);

        // Malus de mise en place préservé : 2 Legends inclinées, 1 seule libre ; le
        // second joueur n'a aucune Legend inclinée.
        assertThat(state.getPlayer(starter).countSpentLegends())
                .isEqualTo(GameConstants.FIRST_PLAYER_SPENT_LEGENDS);
        assertThat(state.getPlayer(starter).legendsAvailableForEddies()).hasSize(1);
        assertThat(state.getPlayer(second).countSpentLegends()).isZero();

        // DrawCardCommand et SelectDieCommand sont acceptées au Tour 1 pour le premier joueur.
        int handBefore = state.getPlayer(starter).getHand().size();
        int deckBefore = state.getPlayer(starter).getDeck().size();
        int fixerBefore = state.getPlayer(starter).getFixerDice().size();

        gameService.executeCommand(state.getGameId(), new DrawCardCommand(starter));
        assertThat(state.getPhase()).isEqualTo(Phase.DRAW);
        assertThat(state.getDrawStep()).isEqualTo(DrawStep.AWAITING_DIE_SELECT);
        assertThat(state.getPlayer(starter).getHand()).hasSize(handBefore + 1);
        assertThat(state.getPlayer(starter).getDeck()).hasSize(deckBefore - 1);

        // Le d20 reste refusé tant qu'il reste d'autres dés (règle du Start Phase).
        assertThatThrownBy(() -> gameService.executeCommand(state.getGameId(),
                new SelectDieCommand(starter, "d20")))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("d20");

        gameService.executeCommand(state.getGameId(), new SelectDieCommand(starter, "d4"));

        // Passage automatique en phase MAIN, sous-étape DRAW effacée.
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        assertThat(state.getDrawStep()).isNull();
        assertThat(state.getPlayer(starter).getGigs()).hasSize(1);
        assertThat(state.getPlayer(starter).getGigDice()).containsExactly("d4");
        assertThat(state.getPlayer(starter).getFixerDice()).hasSize(fixerBefore - 1);

        // Le malus n'est toujours pas levé : il ne l'est qu'au tour 2 du premier joueur
        // (quand la phase DRAW redresse toutes les cartes dépensées).
        assertThat(state.getPlayer(starter).countSpentLegends())
                .isEqualTo(GameConstants.FIRST_PLAYER_SPENT_LEGENDS);
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
        String active = state.getTurn().getActivePlayerId();
        // Mini-Feature 5.1 : le tour 1 s'ouvre en phase DRAW — piocher puis lancer un
        // dé Gig avant d'entrer en phase MAIN (seule phase où la vente est légale).
        gameService.executeCommand(state.getGameId(), new DrawCardCommand(active));
        gameService.executeCommand(state.getGameId(), new SelectDieCommand(active, "d4"));
        assertThat(state.getPhase()).isEqualTo(Phase.MAIN);
        CardInstance toSell = state.getPlayer(active).getHand().get(0);

        List<GameEvent> events = gameService.executeCommand(state.getGameId(),
                new SellCardCommand(active, toSell.getInstanceId()));

        assertThat(events).isNotEmpty();
        // Mini-Feature 3 : la vente ne crédite aucun Eddie — la carte devient une
        // ressource de l'Eddies Area (face cachée, prête à être inclinée pour 1 €$).
        assertThat(toSell.getZone()).isEqualTo(Zone.EDDIES_AREA);
        assertThat(toSell.isFaceDown()).isTrue();
        assertThat(toSell.isExhausted()).isFalse();
        assertThat(gameService.getGameStateInternal(state.getGameId())
                .getPlayer(active).getEddies()).isZero();
        // La vente est consignée dans le journal de diagnostic.
        assertThat(gameService.getGameLog(state.getGameId(), 20)).anyMatch(entry ->
                "SELL_CARD".equals(entry.getActionType())
                        && entry.getResult() == GameActionResult.SUCCESS);

        assertThatThrownBy(() -> gameService.executeCommand(state.getGameId(),
                new PlayCardCommand(active, UUID.randomUUID())))
                .isInstanceOf(GameRuleException.class);
        assertThat(gameService.getGameLog(state.getGameId(), 20)).anyMatch(entry ->
                "ILLEGAL".equals(entry.getResult().name())
                        && entry.getDescription().contains("REFUSÉ"));
    }

    @Test
    void executeCommand_refus_estConsigneDansLeJournalDeDiagnostic() {
        List<String> idsOne = deckIds("a");
        List<String> idsTwo = deckIds("b");
        stubCatalog(idsOne, idsTwo);
        GameState state = gameService.createGame("p1", "p2", idsOne, idsTwo);
        String idle = state.getOpponent(state.getTurn().getActivePlayerId()).getId();
        CardInstance card = state.getPlayer(idle).getHand().get(0);

        assertThatThrownBy(() -> gameService.executeCommand(state.getGameId(),
                new SellCardCommand(idle, card.getInstanceId())))
                .isInstanceOf(GameRuleException.class)
                .hasMessageContaining("tour");

        List<com.cyberpunktcg.domain.game.GameLogEntry> log =
                gameService.getGameLog(state.getGameId(), 10);
        assertThat(log).anyMatch(entry -> "SELL_CARD".equals(entry.getActionType())
                && entry.getResult() == GameActionResult.ILLEGAL
                && entry.getDescription().contains("REFUSÉ")
                && entry.getDetails().containsKey("reason"));
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
    // Mini-Feature 9D — la partie utilise le deck sélectionné par le joueur
    // ------------------------------------------------------------------

    @Test
    void testGameStarts_WithSelectedDeck() {
        // Mini-Feature 9D : `LobbyService` résout la liste de cartes depuis le
        // deck persisté et appelle `GameService.createGame(...)` avec. Le
        // service de partie doit utiliser TELLE QUELLE cette liste pour bâtir
        // le joueur : on marque les cartes avec un préfixe distinctif
        // (`saved-...`) et on vérifie qu'on les retrouve dans la Legends Area
        // et dans le deck du joueur correspondant.
        List<String> savedDeckOne = new ArrayList<>();
        savedDeckOne.add("saved-legend-1");
        savedDeckOne.add("saved-legend-2");
        savedDeckOne.add("saved-legend-3");
        for (int i = 0; i < 10; i++) {
            savedDeckOne.add("saved-unit-" + i);
        }
        List<String> savedDeckTwo = new ArrayList<>();
        savedDeckTwo.add("saved-legend-4");
        savedDeckTwo.add("saved-legend-5");
        savedDeckTwo.add("saved-legend-6");
        for (int i = 0; i < 10; i++) {
            savedDeckTwo.add("other-unit-" + i);
        }
        stubCatalog(savedDeckOne, savedDeckTwo);

        GameState state = gameService.createGame("p1", "p2", savedDeckOne, savedDeckTwo);

        // Chaque joueur reçoit SES Legends (et pas un deck par défaut
        // calculé depuis le catalogue). La Legends Area doit donc contenir
        // exactement les préfixes du deck transmis.
        assertThat(state.getPlayer("p1").getLegendsArea())
                .extracting(CardInstance::getCardId)
                .containsExactlyInAnyOrder("saved-legend-1", "saved-legend-2", "saved-legend-3");
        assertThat(state.getPlayer("p2").getLegendsArea())
                .extracting(CardInstance::getCardId)
                .containsExactlyInAnyOrder("saved-legend-4", "saved-legend-5", "saved-legend-6");

        // Les 10 cartes non-Legend de chaque deck (moins les 6 distribuées
        // en main) sont bien dans la pioche correspondante — pas un
        // sous-ensemble aléatoire du catalogue global.
        assertThat(state.getPlayer("p1").getDeck())
                .allMatch(card -> card.getCardId().startsWith("saved-"));
        assertThat(state.getPlayer("p2").getDeck())
                .allMatch(card -> card.getCardId().startsWith("other-"));
        // Main = 6 cartes piochées parmi les 10 cartes non-Legend du deck.
        assertThat(state.getPlayer("p1").getHand()).hasSize(6);
        assertThat(state.getPlayer("p2").getHand()).hasSize(6);
        assertThat(state.getPlayer("p1").getDeck()).hasSize(4);
        assertThat(state.getPlayer("p2").getDeck()).hasSize(4);
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
