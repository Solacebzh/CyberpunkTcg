package com.cyberpunktcg.ws;

import com.cyberpunktcg.api.dto.ws.CreateRoomRequest;
import com.cyberpunktcg.api.dto.ws.GameCommandDTO;
import com.cyberpunktcg.api.dto.ws.JoinRoomRequest;
import com.cyberpunktcg.domain.deck.Deck;
import com.cyberpunktcg.domain.deck.DeckRepository;
import com.cyberpunktcg.domain.user.User;
import com.cyberpunktcg.domain.user.UserRepository;
import com.cyberpunktcg.repository.CardRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.BlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Intégration du délai de grâce de reconnexion (réglé à 1 s pour les tests) :
 * déconnexion sans retour = forfait automatique ; reconnexion = annulation.
 *
 * <p>Mini-Feature 9D : un deck sauvegardé par joueur est obligatoire pour
 * créer ou rejoindre un salon (voir {@link LobbyDeckFixture}).</p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.game.disconnect-grace=PT1S")
class DisconnectReconnectionWebSocketIntegrationTest {

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

    @BeforeEach
    void cleanState() {
        deckRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void deconnexionSansRetour_apresLeDelai_forfaitAutomatique() throws Exception {
        // On amorce explicitement les decks des deux joueurs (DH / DG).
        User dh = LobbyDeckFixture.upsertUser(userRepository, passwordEncoder, "DH");
        User dg = LobbyDeckFixture.upsertUser(userRepository, passwordEncoder, "DG");
        Deck dhDeck = LobbyDeckFixture.persistLegalDeck(deckRepository, cardRepository, "Deck DH", dh.getId());
        Deck dgDeck = LobbyDeckFixture.persistLegalDeck(deckRepository, cardRepository, "Deck DG", dg.getId());

        StompTestClient host = new StompTestClient();
        StompTestClient guest = new StompTestClient();
        try {
            StartedGame game = startGame(host, guest, "DH", "DG", dhDeck.getId(), dgDeck.getId());

            guest.close(); // déconnexion brutale (DISCONNECT + fermeture TCP)

            JsonNode disconnected = host.await(game.hostNotices(),
                    node -> "PLAYER_DISCONNECTED".equals(node.path("type").asText()), 10);
            assertThat(disconnected).isNotNull();
            assertThat(disconnected.path("playerId").asText()).isEqualTo("DG");
            assertThat(disconnected.path("reconnectDeadInSeconds").asInt()).isEqualTo(1);

            JsonNode offline = host.await(game.hostStates(),
                    node -> isState(node) && !player(node.path("state"), "DG").path("connected").asBoolean(), 10);
            assertThat(offline).as("l'état doit montrer DG déconnecté").isNotNull();

            JsonNode gameOver = host.await(game.hostNotices(),
                    node -> "GAME_OVER".equals(node.path("type").asText()), 10);
            assertThat(gameOver).as("le forfait doit être déclaré après le délai de grâce").isNotNull();
            assertThat(gameOver.path("winnerId").asText()).isEqualTo("DH");

            JsonNode finalState = host.await(game.hostStates(),
                    node -> isState(node) && node.path("state").path("gameOver").asBoolean(), 10);
            assertThat(finalState).isNotNull();
            assertThat(finalState.path("state").path("endReason").asText()).contains("Forfait");

            // Le salon est libéré : l'hôte peut en recréer un (avec son deck).
            BlockingQueue<JsonNode> lobbyQueue = host.subscribe("/user/queue/lobby");
            host.send("/app/lobby.create", new CreateRoomRequest(null, dhDeck.getId()));
            assertThat(host.await(lobbyQueue,
                    node -> "WAITING".equals(node.path("status").asText()), 10)).isNotNull();
        } finally {
            host.close();
            guest.close();
        }
    }

    @Test
    void reconnexionDansLeDelai_annuleLeForfait() throws Exception {
        User rh = LobbyDeckFixture.upsertUser(userRepository, passwordEncoder, "RH");
        User rg = LobbyDeckFixture.upsertUser(userRepository, passwordEncoder, "RG");
        Deck rhDeck = LobbyDeckFixture.persistLegalDeck(deckRepository, cardRepository, "Deck RH", rh.getId());
        Deck rgDeck = LobbyDeckFixture.persistLegalDeck(deckRepository, cardRepository, "Deck RG", rg.getId());

        StompTestClient host = new StompTestClient();
        StompTestClient guest = new StompTestClient();
        StompTestClient returningGuest = new StompTestClient();
        try {
            StartedGame game = startGame(host, guest, "RH", "RG", rhDeck.getId(), rgDeck.getId());

            guest.close();
            assertThat(host.await(game.hostNotices(),
                    node -> "PLAYER_DISCONNECTED".equals(node.path("type").asText())
                            && "RG".equals(node.path("playerId").asText()), 10)).isNotNull();

            // Retour avant la fin du délai de 1 s.
            Thread.sleep(300);
            returningGuest.connect(port, "RG");
            returningGuest.subscribe("/user/queue/errors");
            BlockingQueue<JsonNode> returningNotices = returningGuest.subscribe(
                    "/topic/game/" + game.gameId());
            BlockingQueue<JsonNode> returningStates = returningGuest.subscribe(
                    "/topic/game/" + game.gameId() + "/RG");
            returningGuest.sendEmpty("/app/game/" + game.gameId() + "/resync");
            assertThat(returningGuest.await(returningStates, this::isState, 10))
                    .as("le joueur de retour récupère son état").isNotNull();

            JsonNode reconnected = host.await(game.hostNotices(),
                    node -> "PLAYER_RECONNECTED".equals(node.path("type").asText())
                            && "RG".equals(node.path("playerId").asText()), 10);
            assertThat(reconnected).isNotNull();
            assertThat(host.await(game.hostStates(),
                    node -> isState(node) && player(node.path("state"), "RG").path("connected").asBoolean(), 10))
                    .as("l'état doit de nouveau montrer RG connecté").isNotNull();

            // On dépasse largement le délai de grâce : pas de forfait.
            Thread.sleep(2_000);
            host.sendEmpty("/app/game/" + game.gameId() + "/resync");
            JsonNode current = host.await(game.hostStates(), this::isState, 10);
            assertThat(current.path("state").path("gameOver").asBoolean()).isFalse();
            assertThat(player(current.path("state"), "RG").path("connected").asBoolean()).isTrue();
            assertThat(game.hostNotices().poll()).isNull(); // aucun GAME_OVER reçu

            // Nettoyage : l'invité abandonne pour libérer le salon.
            returningGuest.send("/app/game/" + game.gameId() + "/action",
                    new GameCommandDTO("CONCEDE", null, null, null, null, null, null, null, null));
            assertThat(host.await(game.hostNotices(),
                    node -> "GAME_OVER".equals(node.path("type").asText()), 10)).isNotNull();
        } finally {
            host.close();
            guest.close();
            returningGuest.close();
        }
    }

    private StartedGame startGame(StompTestClient host, StompTestClient guest,
                                  String hostPseudo, String guestPseudo,
                                  long hostDeckId, long guestDeckId) throws Exception {
        host.connect(port, hostPseudo);
        host.subscribe("/user/queue/errors");
        BlockingQueue<JsonNode> hostLobbyQueue = host.subscribe("/user/queue/lobby");
        host.send("/app/lobby.create", new CreateRoomRequest(null, hostDeckId));
        JsonNode created = host.await(hostLobbyQueue,
                node -> "WAITING".equals(node.path("status").asText()), 10);
        String code = created.path("code").asText();
        BlockingQueue<JsonNode> hostLobbyTopic = host.subscribe("/topic/lobby/" + code);

        guest.connect(port, guestPseudo);
        guest.subscribe("/user/queue/errors");
        BlockingQueue<JsonNode> guestLobbyTopic = guest.subscribe("/topic/lobby/" + code);
        guest.send("/app/lobby.join", new JoinRoomRequest(code, guestDeckId));

        JsonNode playing = host.await(hostLobbyTopic,
                node -> "PLAYING".equals(node.path("status").asText()), 10);
        guest.await(guestLobbyTopic, node -> "PLAYING".equals(node.path("status").asText()), 10);
        String gameId = playing.path("gameId").asText();

        BlockingQueue<JsonNode> hostNotices = host.subscribe("/topic/game/" + gameId);
        BlockingQueue<JsonNode> hostStates = host.subscribe("/topic/game/" + gameId + "/" + hostPseudo);
        guest.subscribe("/topic/game/" + gameId);
        guest.subscribe("/topic/game/" + gameId + "/" + guestPseudo);
        host.sendEmpty("/app/game/" + gameId + "/resync");
        guest.sendEmpty("/app/game/" + gameId + "/resync");
        assertThat(host.await(hostStates, this::isState, 10)).isNotNull();

        return new StartedGame(gameId, host, hostNotices, hostStates);
    }

    private boolean isState(JsonNode node) {
        return "STATE".equals(node.path("type").asText());
    }

    private JsonNode player(JsonNode state, String playerId) {
        for (JsonNode player : state.path("players")) {
            if (playerId.equals(player.path("playerId").asText())) {
                return player;
            }
        }
        throw new IllegalStateException("Joueur absent : " + playerId);
    }

    /** Poignées sur les files du joueur hôte d'une partie démarrée. */
    private record StartedGame(String gameId,
                               StompTestClient host,
                               BlockingQueue<JsonNode> hostNotices,
                               BlockingQueue<JsonNode> hostStates) {
    }
}
