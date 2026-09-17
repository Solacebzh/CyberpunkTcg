package com.cyberpunktcg.ws;

import com.cyberpunktcg.api.dto.ws.CreateRoomRequest;
import com.cyberpunktcg.api.dto.ws.GameCommandDTO;
import com.cyberpunktcg.api.dto.ws.JoinRoomRequest;
import com.cyberpunktcg.api.dto.ws.LeaveRoomRequest;
import com.cyberpunktcg.domain.deck.Deck;
import com.cyberpunktcg.domain.deck.DeckRepository;
import com.cyberpunktcg.domain.user.User;
import com.cyberpunktcg.domain.user.UserRepository;
import com.cyberpunktcg.engine.DeckValidator;
import com.cyberpunktcg.engine.GameConstants;
import com.cyberpunktcg.repository.CardRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Intégration STOMP bout-en-bout : création de salon, rejointe, démarrage
 * automatique, états masqués, actions de jeu, erreurs privées, abandon.
 *
 * <p>Mini-Feature 9D : un deck sauvegardé par joueur est obligatoire pour
 * créer ou rejoindre un salon. On amorce donc un compte (username = pseudo
 * STOMP) et un deck légal pour chaque protagoniste via
 * {@link LobbyDeckFixture}.</p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LobbyGameFlowWebSocketIntegrationTest {

    private static final String HOST = "Val";
    private static final String GUEST = "Johnny";

    /**
     * Mini-Feature 9D : le deck légal amorcé par {@link LobbyDeckFixture}
     * contient {@code MAIN_DECK_MIN_SIZE} cartes hors Legends (40). Six sont
     * distribuées à la donne initiale : le deck visible démarre donc à 34
     * cartes (et 33 après la première pioche).
     */
    private static final int INITIAL_DECK_COUNT =
            DeckValidator.MAIN_DECK_MIN_SIZE - GameConstants.STARTING_HAND_SIZE;

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DeckRepository deckRepository;
    @Autowired
    private CardRepository cardRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private long hostDeckId;
    private long guestDeckId;

    @BeforeEach
    void seed() {
        deckRepository.deleteAll();
        userRepository.deleteAll();
        User host = LobbyDeckFixture.upsertUser(userRepository, passwordEncoder, HOST);
        User guest = LobbyDeckFixture.upsertUser(userRepository, passwordEncoder, GUEST);
        Deck hostDeck = LobbyDeckFixture.persistLegalDeck(deckRepository, cardRepository, "Deck de " + HOST, host.getId());
        Deck guestDeck = LobbyDeckFixture.persistLegalDeck(deckRepository, cardRepository, "Deck de " + GUEST, guest.getId());
        hostDeckId = hostDeck.getId();
        guestDeckId = guestDeck.getId();
    }

    @Test
    void cycleComplet_lobby_partieEtatsMasques_abandon() throws Exception {
        try (StompTestClient host = new StompTestClient();
             StompTestClient guest = new StompTestClient()) {
            host.connect(port, HOST);
            BlockingQueue<JsonNode> hostErrors = host.subscribe("/user/queue/errors");
            BlockingQueue<JsonNode> hostLobbyQueue = host.subscribe("/user/queue/lobby");
            host.send("/app/lobby.create", new CreateRoomRequest(null, hostDeckId));

            JsonNode created = host.await(hostLobbyQueue,
                    node -> "LOBBY_STATE".equals(node.path("type").asText())
                            && "WAITING".equals(node.path("status").asText()), 10);
            assertThat(created).as("confirmation de création attendue").isNotNull();
            String code = created.path("code").asText();
            assertThat(code).hasSize(6);
            assertThat(created.path("players").get(0).path("pseudo").asText()).isEqualTo(HOST);
            // Mini-Feature 9D : le siège expose le deckId sélectionné.
            assertThat(created.path("players").get(0).path("deckId").asLong()).isEqualTo(hostDeckId);
            BlockingQueue<JsonNode> hostLobbyTopic = host.subscribe("/topic/lobby/" + code);

            guest.connect(port, GUEST);
            BlockingQueue<JsonNode> guestErrors = guest.subscribe("/user/queue/errors");
            BlockingQueue<JsonNode> roomsQueue = guest.subscribe("/user/queue/rooms");
            guest.sendEmpty("/app/lobby.list");
            JsonNode rooms = guest.await(roomsQueue, node -> "ROOMS".equals(node.path("type").asText()), 10);
            assertThat(rooms.path("rooms").toString()).contains(code);

            BlockingQueue<JsonNode> guestLobbyTopic = guest.subscribe("/topic/lobby/" + code);
            BlockingQueue<JsonNode> guestLobbyQueue = guest.subscribe("/user/queue/lobby");
            guest.send("/app/lobby.join", new JoinRoomRequest(code, guestDeckId));

            JsonNode hostPlaying = host.await(hostLobbyTopic,
                    node -> "PLAYING".equals(node.path("status").asText()), 10);
            JsonNode guestPlaying = guest.await(guestLobbyTopic,
                    node -> "PLAYING".equals(node.path("status").asText()), 10);
            guest.next(guestLobbyQueue, 10); // confirmation privée, même contenu
            assertThat(hostPlaying).as("l'hôte doit voir la partie démarrer").isNotNull();
            String gameId = hostPlaying.path("gameId").asText();
            assertThat(gameId).isNotBlank();
            assertThat(guestPlaying.path("gameId").asText()).isEqualTo(gameId);

            BlockingQueue<JsonNode> hostNotices = host.subscribe("/topic/game/" + gameId);
            BlockingQueue<JsonNode> guestNotices = guest.subscribe("/topic/game/" + gameId);
            BlockingQueue<JsonNode> hostStates = host.subscribe("/topic/game/" + gameId + "/" + HOST);
            BlockingQueue<JsonNode> guestStates = guest.subscribe("/topic/game/" + gameId + "/" + GUEST);
            // Journal de diagnostic (feature 6.5) : public, donc reçu par les deux joueurs.
            BlockingQueue<JsonNode> hostLogs = host.subscribe("/topic/game/" + gameId + "/log");
            BlockingQueue<JsonNode> guestLogs = guest.subscribe("/topic/game/" + gameId + "/log");
            // Le démarrage pousse déjà un état (best effort) ; on s'abonne puis on resync.
            guest.sendEmpty("/app/game/" + gameId + "/resync");
            host.sendEmpty("/app/game/" + gameId + "/resync");

            JsonNode hostState = host.await(hostStates, this::isState, 10);
            JsonNode guestState = guest.await(guestStates, this::isState, 10);
            assertInitialMaskedState(hostState, guestState, gameId);

            // Le premier joueur est tiré au sort (feature 6.5) : on le lit dans l'état.
            String starter = hostState.path("state").path("turn").path("activePlayerId").asText();
            assertThat(starter).isIn(HOST, GUEST);
            String otherPseudo = HOST.equals(starter) ? GUEST : HOST;
            StompTestClient starterClient = HOST.equals(starter) ? host : guest;
            StompTestClient otherClient = HOST.equals(starter) ? guest : host;
            BlockingQueue<JsonNode> starterStates = HOST.equals(starter) ? hostStates : guestStates;
            BlockingQueue<JsonNode> otherStates = HOST.equals(starter) ? guestStates : hostStates;
            BlockingQueue<JsonNode> starterErrors = HOST.equals(starter) ? hostErrors : guestErrors;
            BlockingQueue<JsonNode> otherErrors = HOST.equals(starter) ? guestErrors : hostErrors;

            // --- Mini-Feature 5.1 : le tour 1 du premier joueur s'ouvre en phase DRAW ---
            // Comme à chaque tour, il doit piocher sa carte puis lancer un dé Gig avant
            // de pouvoir terminer son tour (END_TURN est refusé pendant la phase DRAW).
            starterClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("DRAW_CARD", null, null, null, null, null, null, null, "req-t1-draw"));
            JsonNode starterTurnOneDraw = starterClient.await(starterStates,
                    node -> isState(node) && "req-t1-draw".equals(node.path("clientRequestId").asText()), 10);
            assertThat(starterTurnOneDraw).as("état après la pioche du tour 1 attendu").isNotNull();
            assertThat(starterTurnOneDraw.path("state").path("phase").asText()).isEqualTo("DRAW");
            assertThat(starterTurnOneDraw.path("state").path("turn").path("drawStep").asText())
                    .isEqualTo("AWAITING_DIE_SELECT");

            starterClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("SELECT_DIE", null, null, null, null, null, null, List.of("d4"), "req-t1-die"));
            JsonNode starterTurnOneDie = starterClient.await(starterStates,
                    node -> isState(node) && "req-t1-die".equals(node.path("clientRequestId").asText()), 10);
            assertThat(starterTurnOneDie).as("état après le lancer du tour 1 attendu").isNotNull();
            assertThat(starterTurnOneDie.path("state").path("phase").asText()).isEqualTo("MAIN");
            assertThat(player(starterTurnOneDie.path("state"), starter).path("gigs")).hasSize(1);

            // --- Le premier joueur termine son tour : les deux reçoivent le nouvel état ---
            starterClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("END_TURN", null, null, null, null, null, null, null, "req-end-1"));
            JsonNode starterAfter = starterClient.await(starterStates,
                    node -> isState(node) && node.path("state").path("turn").path("number").asInt() == 2, 10);
            JsonNode otherAfter = otherClient.await(otherStates,
                    node -> isState(node) && node.path("state").path("turn").path("number").asInt() == 2, 10);
            assertThat(starterAfter).as("état du premier joueur attendu").isNotNull();
            assertThat(otherAfter).as("état de l'adversaire attendu").isNotNull();
            assertThat(starterAfter.path("state").path("turn").path("activePlayerId").asText())
                    .isEqualTo(otherPseudo);
            assertThat(starterAfter.path("clientRequestId").asText()).isEqualTo("req-end-1");
            assertThat(starterAfter.path("newEvents")).isNotEmpty();
            // Mini-Feature 5 : la phase DRAW est interactive — la partie attend le clic
            // du joueur entrant sur sa pioche (rien n'est pioché ni lancé automatiquement).
            assertThat(starterAfter.path("state").path("phase").asText()).isEqualTo("DRAW");
            assertThat(starterAfter.path("state").path("turn").path("drawStep").asText()).isEqualTo("AWAITING_DRAW");
            assertThat(player(otherAfter.path("state"), otherPseudo).path("hand")).hasSize(6);
            assertThat(player(otherAfter.path("state"), otherPseudo).path("gigs")).isEmpty();

            // --- Le journal de diagnostic est diffusé en temps réel aux deux joueurs ---
            // Le tour 1 a déjà publié des lignes de journal (pioche, lancer) : on attend
            // précisément le message de fin de tour (il porte TURN_RESET).
            JsonNode hostLog = host.await(hostLogs,
                    node -> isLog(node) && fieldOf(node.path("entries"), "actionType").contains("TURN_RESET"), 10);
            JsonNode guestLog = guest.await(guestLogs,
                    node -> isLog(node) && fieldOf(node.path("entries"), "actionType").contains("TURN_RESET"), 10);
            assertThat(hostLog).as("journal poussé à l'hôte attendu").isNotNull();
            assertThat(guestLog).as("journal poussé à l'invité attendu").isNotNull();
            assertThat(hostLog.path("gameId").asText()).isEqualTo(gameId);
            assertThat(hostLog.path("entries")).isNotEmpty();
            List<String> actionTypes = fieldOf(hostLog.path("entries"), "actionType");
            List<String> descriptions = fieldOf(hostLog.path("entries"), "description");
            // La fin de tour journalise la phase END, la vérification de victoire et
            // l'ouverture de la phase DRAW (cf. docs/DEBUG-GUIDE.md).
            assertThat(actionTypes).contains("VICTORY_CHECK", "TURN_RESET", "DRAW_STEP");
            assertThat(descriptions).anyMatch(line -> line.contains("Phase DRAW"));
            assertThat(descriptions).anyMatch(line -> line.contains("Vérification victoire"));
            assertThat(fieldOf(hostLog.path("entries"), "result")).doesNotContain("ILLEGAL");
            // L'état complet embarque le journal : le panneau de debug s'amorce après un resync.
            assertThat(starterAfter.path("state").path("gameLog")).isNotEmpty();
            assertThat(fieldOf(starterAfter.path("state").path("gameLog"), "description"))
                    .anyMatch(line -> line.contains("Vérification victoire"));

            // --- Phase DRAW interactive : impossible de sauter la pioche ---
            otherClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("SELECT_DIE", null, null, null, null, null, null, List.of("d4"), "req-early-die"));
            JsonNode earlyDie = otherClient.await(otherErrors,
                    node -> "ILLEGAL_ACTION".equals(node.path("code").asText())
                            && "req-early-die".equals(node.path("clientRequestId").asText()), 10);
            assertThat(earlyDie).as("choisir un dé avant de piocher doit être refusé").isNotNull();
            otherClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("END_TURN", null, null, null, null, null, null, null, "req-early-end"));
            JsonNode earlyEnd = otherClient.await(otherErrors,
                    node -> "ILLEGAL_ACTION".equals(node.path("code").asText())
                            && "req-early-end".equals(node.path("clientRequestId").asText()), 10);
            assertThat(earlyEnd).as("terminer le tour pendant la phase DRAW doit être refusé").isNotNull();

            // --- Le joueur entrant clique sur sa pioche : +1 carte, puis choix du dé ---
            otherClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("DRAW_CARD", null, null, null, null, null, null, null, "req-draw"));
            JsonNode afterDraw = otherClient.await(otherStates,
                    node -> isState(node) && "req-draw".equals(node.path("clientRequestId").asText()), 10);
            assertThat(afterDraw).as("état après la pioche attendu").isNotNull();
            assertThat(afterDraw.path("state").path("phase").asText()).isEqualTo("DRAW");
            assertThat(afterDraw.path("state").path("turn").path("drawStep").asText()).isEqualTo("AWAITING_DIE_SELECT");
            assertThat(player(afterDraw.path("state"), otherPseudo).path("hand")).hasSize(7);
            assertThat(player(afterDraw.path("state"), otherPseudo).path("deckCount").asInt())
                    .isEqualTo(INITIAL_DECK_COUNT - 1);
            assertThat(fieldOf(afterDraw.path("newEvents"), "type")).contains("CARD_DRAWN");

            // Le d20 se lance toujours en dernier : refusé tant qu'il reste d'autres dés.
            otherClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("SELECT_DIE", null, null, null, null, null, null, List.of("d20"), "req-d20"));
            JsonNode d20Error = otherClient.await(otherErrors,
                    node -> "ILLEGAL_ACTION".equals(node.path("code").asText())
                            && "req-d20".equals(node.path("clientRequestId").asText()), 10);
            assertThat(d20Error).isNotNull();
            assertThat(d20Error.path("message").asText()).contains("d20");

            // --- Choix d'un dé valide : Gig gagné, passage automatique en MAIN ---
            otherClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("SELECT_DIE", null, null, null, null, null, null, List.of("d6"), "req-die"));
            JsonNode afterDie = otherClient.await(otherStates,
                    node -> isState(node) && "req-die".equals(node.path("clientRequestId").asText()), 10);
            JsonNode starterSeesMain = starterClient.await(starterStates,
                    node -> isState(node) && "MAIN".equals(node.path("state").path("phase").asText())
                            && node.path("state").path("turn").path("number").asInt() == 2, 10);
            assertThat(afterDie).as("état après le lancer attendu").isNotNull();
            assertThat(starterSeesMain).as("le rival doit voir la phase MAIN").isNotNull();
            assertThat(afterDie.path("state").path("phase").asText()).isEqualTo("MAIN");
            assertThat(afterDie.path("state").path("turn").path("drawStep").isMissingNode()
                    || afterDie.path("state").path("turn").path("drawStep").isNull()).isTrue();
            JsonNode roller = player(afterDie.path("state"), otherPseudo);
            assertThat(roller.path("gigs")).hasSize(1);
            assertThat(roller.path("gigs").get(0).asInt()).isBetween(1, 6);
            assertThat(roller.path("gigDice")).hasSize(1);
            assertThat(roller.path("gigDice").get(0).asText()).isEqualTo("d6");
            assertThat(roller.path("gigCount").asInt()).isEqualTo(1);
            assertThat(roller.path("fixerDice")).hasSize(5);
            assertThat(textValues(roller.path("fixerDice"))).containsExactly("d4", "d8", "d10", "d12", "d20");
            assertThat(fieldOf(afterDie.path("newEvents"), "type")).contains("GIG_ROLLED", "PHASE_CHANGED");
            // Le journal a bien consigné la pioche puis le lancer.
            JsonNode gigLog = host.await(hostLogs, node -> isLog(node)
                    && fieldOf(node.path("entries"), "actionType").contains("GIG_ROLL"), 10);
            assertThat(gigLog).as("journal du lancer de Gig attendu").isNotNull();
            assertThat(fieldOf(gigLog.path("entries"), "description")).anyMatch(line -> line.contains("Lancer de Gig"));

            // --- Une action illégale du joueur actif n'erre QUE chez lui ---
            otherClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("PLAY_CARD", UUID.randomUUID(), null, null,
                            null, null, null, null, "req-bad"));
            JsonNode error = otherClient.await(otherErrors,
                    node -> "ERROR".equals(node.path("type").asText())
                            && "ILLEGAL_ACTION".equals(node.path("code").asText()), 10);
            assertThat(error).as("le joueur fautif doit recevoir une erreur privée").isNotNull();
            assertThat(error.path("clientRequestId").asText()).isEqualTo("req-bad");
            assertThat(starterErrors.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();

            // Le refus est consigné dans le journal de diagnostic et diffusé (ILLEGAL + motif).
            JsonNode refusalLog = host.await(hostLogs, node -> isLog(node) && hasIllegal(node), 10);
            assertThat(refusalLog).as("refus journalisé et diffusé attendu").isNotNull();
            JsonNode refusal = illegalEntry(refusalLog.path("entries"));
            assertThat(refusal.path("description").asText()).contains("REFUSÉ");
            assertThat(refusal.path("result").asText()).isEqualTo("ILLEGAL");
            assertThat(refusal.path("details").path("reason").asText()).isNotBlank();
            assertThat(refusal.path("playerId").asText()).isEqualTo(otherPseudo);

            // --- Un joueur ne peut pas jouer hors de son tour ---
            starterClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("END_TURN", null, null, null, null, null, null, null, null));
            JsonNode outOfTurnError = starterClient.await(starterErrors,
                    node -> "ILLEGAL_ACTION".equals(node.path("code").asText()), 10);
            assertThat(outOfTurnError).isNotNull();
            assertThat(otherErrors.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();

            // --- Action inconnue ---
            otherClient.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("DANCE", null, null, null, null, null, null, null, null));
            assertThat(otherClient.await(otherErrors,
                    node -> "ILLEGAL_ACTION".equals(node.path("code").asText())
                            && node.path("message").asText().contains("inconnue"), 10)).isNotNull();

            // --- Abandon de l'invité : GAME_OVER public, états finaux, vainqueur = hôte ---
            guest.send("/app/game/" + gameId + "/action",
                    new GameCommandDTO("CONCEDE", null, null, null, null, null, null, null, "req-quit"));
            JsonNode hostOver = host.await(hostStates,
                    node -> isState(node) && node.path("state").path("gameOver").asBoolean(), 10);
            JsonNode guestOver = guest.await(guestStates,
                    node -> isState(node) && node.path("state").path("gameOver").asBoolean(), 10);
            assertThat(hostOver).isNotNull();
            assertThat(guestOver).isNotNull();
            assertThat(hostOver.path("state").path("winnerId").asText()).isEqualTo(HOST);
            JsonNode notice = host.await(hostNotices,
                    node -> "GAME_OVER".equals(node.path("type").asText()), 10);
            JsonNode guestNotice = guest.await(guestNotices,
                    node -> "GAME_OVER".equals(node.path("type").asText()), 10);
            assertThat(notice.path("winnerId").asText()).isEqualTo(HOST);
            assertThat(guestNotice.path("winnerId").asText()).isEqualTo(HOST);

            // Le salon est fermé : l'hôte peut recréer une partie.
            host.send("/app/lobby.create", new CreateRoomRequest("Revanche", hostDeckId));
            JsonNode revanche = host.await(hostLobbyQueue,
                    node -> "LOBBY_STATE".equals(node.path("type").asText())
                            && "WAITING".equals(node.path("status").asText()), 10);
            assertThat(revanche).isNotNull();
            host.send("/app/lobby.leave", new LeaveRoomRequest(revanche.path("code").asText()));
        }
    }

    @Test
    void connexion_sansPseudo_estRefusee() {
        assertThatThrownBy(() -> new StompTestClient().connect(port, null))
                .isInstanceOf(Exception.class);
    }

    private boolean isState(JsonNode node) {
        return "STATE".equals(node.path("type").asText());
    }

    /** Message du journal de diagnostic (`/topic/game/{gameId}/log`). */
    private boolean isLog(JsonNode node) {
        return "LOG".equals(node.path("type").asText());
    }

    private boolean hasIllegal(JsonNode logMessage) {
        for (JsonNode entry : logMessage.path("entries")) {
            if ("ILLEGAL".equals(entry.path("result").asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode illegalEntry(JsonNode entries) {
        for (JsonNode entry : entries) {
            if ("ILLEGAL".equals(entry.path("result").asText())) {
                return entry;
            }
        }
        throw new IllegalStateException("Aucune entrée ILLEGAL dans : " + entries);
    }

    /** Valeurs d'un tableau JSON de chaînes. */
    private List<String> textValues(JsonNode array) {
        List<String> values = new java.util.ArrayList<String>();
        for (JsonNode node : array) {
            values.add(node.asText());
        }
        return values;
    }

    private List<String> fieldOf(JsonNode array, String field) {
        List<String> values = new java.util.ArrayList<String>();
        for (JsonNode node : array) {
            values.add(node.path(field).asText());
        }
        return values;
    }

    private void assertInitialMaskedState(JsonNode hostState, JsonNode guestState, String gameId) {
        assertThat(hostState).as("état de l'hôte attendu").isNotNull();
        assertThat(guestState).as("état de l'invité attendu").isNotNull();
        JsonNode host = hostState.path("state");
        JsonNode guest = guestState.path("state");
        assertThat(host.path("gameId").asText()).isEqualTo(gameId);
        assertThat(host.path("yourPlayerId").asText()).isEqualTo(HOST);
        assertThat(guest.path("yourPlayerId").asText()).isEqualTo(GUEST);
        // Mini-Feature 5.1 : la partie démarre en phase DRAW (sous-étape AWAITING_DRAW)
        // pour le premier joueur — il doit piocher puis lancer son dé avant le MAIN.
        assertThat(host.path("phase").asText()).isEqualTo("DRAW");
        assertThat(host.path("turn").path("drawStep").asText()).isEqualTo("AWAITING_DRAW");
        assertThat(host.path("gameOver").asBoolean()).isFalse();
        // Le premier joueur est tiré au sort : le tour 1 appartient à l'un des deux joueurs.
        assertThat(host.path("turn").path("activePlayerId").asText()).isIn(HOST, GUEST);
        assertThat(host.path("turn").path("number").asInt()).isEqualTo(1);

        JsonNode hostViewOfHost = player(host, HOST);
        JsonNode hostViewOfGuest = player(host, GUEST);
        // L'hôte voit SA main en clair (6 cartes aux vrais identifiants)…
        assertThat(hostViewOfHost.path("hand")).hasSize(6);
        assertThat(hostViewOfHost.path("hand")).allSatisfy(card ->
                assertThat(card.path("cardId").asText()).isNotEqualTo("hidden").isNotBlank());
        assertThat(hostViewOfHost.path("deckCount").asInt()).isEqualTo(INITIAL_DECK_COUNT);
        // …et celle de l'invité entièrement masquée.
        assertThat(hostViewOfGuest.path("hand")).hasSize(6);
        assertThat(hostViewOfGuest.path("hand")).allSatisfy(card -> {
            assertThat(card.path("cardId").asText()).isEqualTo("hidden");
            assertThat(card.path("name").asText()).isEqualTo("Carte masquée");
            assertThat(card.path("power").isMissingNode() || card.path("power").isNull()).isTrue();
        });
        assertThat(hostViewOfGuest.path("deckCount").asInt()).isEqualTo(INITIAL_DECK_COUNT);
        // L'invité voit sa propre main en clair.
        JsonNode guestViewOfGuest = player(guest, GUEST);
        assertThat(guestViewOfGuest.path("hand")).allSatisfy(card ->
                assertThat(card.path("cardId").asText()).isNotEqualTo("hidden"));
        // Les deux joueurs sont connectés.
        assertThat(hostViewOfHost.path("connected").asBoolean()).isTrue();
        assertThat(hostViewOfGuest.path("connected").asBoolean()).isTrue();
    }

    private JsonNode player(JsonNode state, String playerId) {
        for (JsonNode player : state.path("players")) {
            if (playerId.equals(player.path("playerId").asText())) {
                return player;
            }
        }
        throw new IllegalStateException("Joueur absent de l'état : " + playerId);
    }
}
