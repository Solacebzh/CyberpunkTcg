package com.cyberpunktcg.lobby;

import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.service.DefaultDeckService;
import com.cyberpunktcg.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du lobby : création/rejointe/fermeture des salons et
 * démarrage automatique de la partie à deux joueurs.
 */
@ExtendWith(MockitoExtension.class)
class LobbyServiceTest {

    private static final List<String> DECK = sampleDeck();

    @Mock
    private GameService gameService;
    @Mock
    private DefaultDeckService deckService;

    private LobbyService lobbyService;

    @BeforeEach
    void setUp() {
        lobbyService = new LobbyService(gameService, deckService);
        org.mockito.Mockito.lenient().when(deckService.resolveDeck(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    List<String> requested = inv.getArgument(0);
                    return requested == null || requested.isEmpty() ? DECK : List.copyOf(requested);
                });
    }

    @Test
    void createRoom_salonEnAttenteAvecHote() {
        Room room = lobbyService.createRoom("Val", null, null);

        assertThat(room.getCode()).hasSize(6);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.WAITING);
        assertThat(room.getHostPseudo()).isEqualTo("Val");
        assertThat(room.seatView()).hasSize(1);
        assertThat(room.seatView().get(0).seat()).isZero();
        assertThat(lobbyService.listOpenRooms()).containsExactly(room);
        verify(gameService, never()).createGame(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void joinRoom_deuxJoueurs_demarreLaPartieAutomatiquement() {
        Room created = lobbyService.createRoom("Val", null, null);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame(created.getGameId() == null ? "game-1" : created.getGameId(),
                        inv.getArgument(0), inv.getArgument(1)));

        Room joined = lobbyService.joinRoom("Johnny", created.getCode(), null);

        assertThat(joined.getStatus()).isEqualTo(RoomStatus.PLAYING);
        assertThat(joined.seatView()).hasSize(2);
        assertThat(joined.seatView().get(0).pseudo()).isEqualTo("Val");
        assertThat(joined.seatView().get(1).pseudo()).isEqualTo("Johnny");
        assertThat(joined.getGameId()).isNotBlank();
        verify(gameService).createGame(org.mockito.ArgumentMatchers.eq("Val"),
                org.mockito.ArgumentMatchers.eq("Johnny"), anyList(), anyList());
        assertThat(lobbyService.listOpenRooms()).isEmpty();
    }

    @Test
    void joinRoom_codeInconnu_leveException() {
        assertThatThrownBy(() -> lobbyService.joinRoom("Johnny", "XXXXXX", null))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    void joinRoom_salonPlein_impossible() {
        Room created = lobbyService.createRoom("Val", null, null);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame("g", inv.getArgument(0), inv.getArgument(1)));
        lobbyService.joinRoom("Johnny", created.getCode(), null);

        assertThatThrownBy(() -> lobbyService.joinRoom("Troisieme", created.getCode(), null))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("ROOM_NOT_JOINABLE");
    }

    @Test
    void createRoom_pseudoDejaDansUnSalon_refuse() {
        lobbyService.createRoom("Val", null, null);

        assertThatThrownBy(() -> lobbyService.createRoom("Val", null, null))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("ALREADY_IN_ROOM");
    }

    @Test
    void pseudoInvalide_refuse() {
        assertThatThrownBy(() -> lobbyService.createRoom("x", null, null))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("INVALID_PSEUDO");
    }

    @Test
    void leaveRoom_aucunSalon_renvoieVide() {
        assertThat(lobbyService.leaveRoom("Nino", null)).isEmpty();
        assertThat(lobbyService.leaveRoom("Nino", "XXXXXX")).isEmpty();
    }

    @Test
    void apresFermeture_lesPseudosSontLiberes() {
        Room created = lobbyService.createRoom("Val", null, null);
        lobbyService.leaveRoom("Val", created.getCode());

        // Le pseudo de l'hôte est libéré : il peut recréer un salon.
        Room again = lobbyService.createRoom("Val", "Revanche", null);
        assertThat(again.getHostPseudo()).isEqualTo("Val");
        assertThat(again.getName()).isEqualTo("Revanche");
    }

    @Test
    void leaveRoom_hotePart_fermeLeSalon() {
        Room created = lobbyService.createRoom("Val", null, null);

        lobbyService.leaveRoom("Val", created.getCode());

        assertThat(lobbyService.findByCode(created.getCode())).isEmpty();
    }

    @Test
    void leaveRoom_partieEnCours_passeParConcede() {
        Room created = lobbyService.createRoom("Val", null, null);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame("g", inv.getArgument(0), inv.getArgument(1)));
        lobbyService.joinRoom("Johnny", created.getCode(), null);

        assertThatThrownBy(() -> lobbyService.leaveRoom("Val", created.getCode()))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("GAME_IN_PROGRESS");
    }

    @Test
    void closeByGameId_libereLesJoueurs() {
        Room created = lobbyService.createRoom("Val", null, null);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame("g-42", inv.getArgument(0), inv.getArgument(1)));
        Room joined = lobbyService.joinRoom("Johnny", created.getCode(), null);
        String gameId = joined.getGameId();

        lobbyService.closeByGameId(gameId);

        assertThat(lobbyService.findByPlayer("Val")).isEmpty();
        assertThat(lobbyService.findByPlayer("Johnny")).isEmpty();
        // Les pseudos sont libérés pour rejouer.
        assertThat(lobbyService.createRoom("Val", null, null)).isNotNull();
    }

    private static List<String> sampleDeck() {
        List<String> ids = new ArrayList<>();
        ids.add("l1");
        ids.add("l2");
        ids.add("l3");
        for (int i = 0; i < 10; i++) {
            ids.add("u" + i);
        }
        return List.copyOf(ids);
    }

    private GameState fakeGame(String gameId, String p1, String p2) {
        return new GameState(gameId, List.of(new Player(p1, p1), new Player(p2, p2)), 1L);
    }
}
