package com.cyberpunktcg.lobby;

import com.cyberpunktcg.domain.deck.DeckService;
import com.cyberpunktcg.domain.deck.dto.DeckResponse;
import com.cyberpunktcg.domain.game.GameState;
import com.cyberpunktcg.domain.game.Player;
import com.cyberpunktcg.service.GameService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires du lobby : création/rejointe/fermeture des salons et
 * démarrage automatique de la partie à deux joueurs.
 *
 * <p>Mini-Feature 9D : un deck sauvegardé (identifiant + propriétaire) est
 * désormais obligatoire pour entrer dans un salon. Ces tests vérifient
 * l'isolation : refus sans deck, refus d'un deck appartenant à un autre
 * joueur, démarrage de la partie avec le deck sélectionné.</p>
 */
@ExtendWith(MockitoExtension.class)
class LobbyServiceTest {

    private static final long HOST_DECK_ID = 101L;
    private static final long GUEST_DECK_ID = 202L;
    private static final List<String> HOST_DECK_CARDS = sampleDeck();
    private static final List<String> GUEST_DECK_CARDS = sampleDeck();

    @Mock
    private GameService gameService;
    @Mock
    private DeckService deckService;

    private LobbyService lobbyService;

    @BeforeEach
    void setUp() {
        lobbyService = new LobbyService(gameService, deckService);
    }

    @Test
    void createRoom_salonEnAttenteAvecHote() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        Room room = lobbyService.createRoom("Val", null, HOST_DECK_ID);

        assertThat(room.getCode()).hasSize(6);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.WAITING);
        assertThat(room.getHostPseudo()).isEqualTo("Val");
        assertThat(room.seatView()).hasSize(1);
        assertThat(room.seatView().get(0).seat()).isZero();
        assertThat(room.seatView().get(0).deckId()).isEqualTo(HOST_DECK_ID);
        assertThat(lobbyService.listOpenRooms()).containsExactly(room);
        verify(gameService, never()).createGame(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void joinRoom_deuxJoueurs_demarreLaPartieAutomatiquement() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        stubOwnedDeck("Johnny", GUEST_DECK_ID, GUEST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame(created.getGameId() == null ? "game-1" : created.getGameId(),
                        inv.getArgument(0), inv.getArgument(1)));

        Room joined = lobbyService.joinRoom("Johnny", created.getCode(), GUEST_DECK_ID);

        assertThat(joined.getStatus()).isEqualTo(RoomStatus.PLAYING);
        assertThat(joined.seatView()).hasSize(2);
        assertThat(joined.seatView().get(0).pseudo()).isEqualTo("Val");
        assertThat(joined.seatView().get(0).deckId()).isEqualTo(HOST_DECK_ID);
        assertThat(joined.seatView().get(1).pseudo()).isEqualTo("Johnny");
        assertThat(joined.seatView().get(1).deckId()).isEqualTo(GUEST_DECK_ID);
        assertThat(joined.getGameId()).isNotBlank();
        // La partie est créée avec les VRAIES cartes des decks persistés (et non un deck par défaut).
        verify(gameService).createGame(eq("Val"), eq("Johnny"), eq(HOST_DECK_CARDS), eq(GUEST_DECK_CARDS));
        assertThat(lobbyService.listOpenRooms()).isEmpty();
    }

    @Test
    void joinRoom_codeInconnu_leveException() {
        assertThatThrownBy(() -> lobbyService.joinRoom("Johnny", "XXXXXX", GUEST_DECK_ID))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("ROOM_NOT_FOUND");
    }

    @Test
    void joinRoom_salonPlein_impossible() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        stubOwnedDeck("Johnny", GUEST_DECK_ID, GUEST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame("g", inv.getArgument(0), inv.getArgument(1)));
        lobbyService.joinRoom("Johnny", created.getCode(), GUEST_DECK_ID);

        stubOwnedDeck("Troisieme", 303L, sampleDeck());
        assertThatThrownBy(() -> lobbyService.joinRoom("Troisieme", created.getCode(), 303L))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("ROOM_NOT_JOINABLE");
    }

    @Test
    void createRoom_pseudoDejaDansUnSalon_refuse() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        lobbyService.createRoom("Val", null, HOST_DECK_ID);

        assertThatThrownBy(() -> lobbyService.createRoom("Val", null, HOST_DECK_ID))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("ALREADY_IN_ROOM");
    }

    @Test
    void pseudoInvalide_refuse() {
        assertThatThrownBy(() -> lobbyService.createRoom("x", null, HOST_DECK_ID))
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
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);
        lobbyService.leaveRoom("Val", created.getCode());

        // Le pseudo de l'hôte est libéré : il peut recréer un salon.
        Room again = lobbyService.createRoom("Val", "Revanche", HOST_DECK_ID);
        assertThat(again.getHostPseudo()).isEqualTo("Val");
        assertThat(again.getName()).isEqualTo("Revanche");
    }

    @Test
    void leaveRoom_hotePart_fermeLeSalon() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);

        lobbyService.leaveRoom("Val", created.getCode());

        assertThat(lobbyService.findByCode(created.getCode())).isEmpty();
    }

    @Test
    void leaveRoom_partieEnCours_passeParConcede() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        stubOwnedDeck("Johnny", GUEST_DECK_ID, GUEST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame("g", inv.getArgument(0), inv.getArgument(1)));
        lobbyService.joinRoom("Johnny", created.getCode(), GUEST_DECK_ID);

        assertThatThrownBy(() -> lobbyService.leaveRoom("Val", created.getCode()))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("GAME_IN_PROGRESS");
    }

    @Test
    void closeByGameId_libereLesJoueurs() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        stubOwnedDeck("Johnny", GUEST_DECK_ID, GUEST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame("g-42", inv.getArgument(0), inv.getArgument(1)));
        Room joined = lobbyService.joinRoom("Johnny", created.getCode(), GUEST_DECK_ID);
        String gameId = joined.getGameId();

        lobbyService.closeByGameId(gameId);

        assertThat(lobbyService.findByPlayer("Val")).isEmpty();
        assertThat(lobbyService.findByPlayer("Johnny")).isEmpty();
        // Les pseudos sont libérés pour rejouer.
        assertThat(lobbyService.createRoom("Val", null, HOST_DECK_ID)).isNotNull();
    }

    // ------------------------------------------------------------------
    // Mini-Feature 9D — sélection d'un deck sauvegardé obligatoire
    // ------------------------------------------------------------------

    @Test
    void testCannotJoinLobbyWithoutDeck() {
        // Pas de deck côté hôte → la création du salon est refusée.
        assertThatThrownBy(() -> lobbyService.createRoom("Val", null, null))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("NO_DECK_SELECTED");
        // Le salon n'est pas créé : aucune réservation n'est faite.
        assertThat(lobbyService.findByPlayer("Val")).isEmpty();
        assertThat(lobbyService.listOpenRooms()).isEmpty();
        // Aucun appel au service de partie : impossible de démarrer sans deck.
        verify(gameService, never()).createGame(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void joinRoom_sansDeck_refuse() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);

        // L'invité n'a pas sélectionné de deck : `deckId = null` → rejet.
        assertThatThrownBy(() -> lobbyService.joinRoom("Johnny", created.getCode(), null))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("NO_DECK_SELECTED");
        // Le salon reste à 1 joueur (l'invité n'a pas été assis).
        assertThat(created.seatView()).hasSize(1);
        assertThat(created.getStatus()).isEqualTo(RoomStatus.WAITING);
        // Aucune partie n'a été créée.
        verify(gameService, never()).createGame(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void joinRoom_deckAppartenantAUnAutre_compteRefuse() {
        // L'hôte a bien un deck, mais l'invité « Johnny » n'en possède pas : le
        // service de decks lève un 404 (introuvable pour ce propriétaire).
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);
        when(deckService.getDeck("Johnny", 999L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Deck introuvable : 999"));

        assertThatThrownBy(() -> lobbyService.joinRoom("Johnny", created.getCode(), 999L))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("DECK_NOT_OWNED");
        assertThat(created.seatView()).hasSize(1);
        verify(gameService, never()).createGame(anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void testGameStarts_WithSelectedDeck() {
        // Les deux joueurs ont chacun un deck sauvegardé distinct.
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        stubOwnedDeck("Johnny", GUEST_DECK_ID, GUEST_DECK_CARDS);
        when(gameService.createGame(anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(inv -> fakeGame("game-xyz", inv.getArgument(0), inv.getArgument(1)));

        Room hostRoom = lobbyService.createRoom("Val", "Arene", HOST_DECK_ID);
        Room played = lobbyService.joinRoom("Johnny", hostRoom.getCode(), GUEST_DECK_ID);

        // Au démarrage, la partie reçoit EXACTEMENT les cartes persistées des
        // deux decks — pas un deck par défaut.
        assertThat(played.getStatus()).isEqualTo(RoomStatus.PLAYING);
        assertThat(played.getGameId()).isEqualTo("game-xyz");
        verify(gameService).createGame(
                eq("Val"),
                eq("Johnny"),
                eq(HOST_DECK_CARDS),
                eq(GUEST_DECK_CARDS)
        );
    }

    @Test
    void deckSupprime_entreJoinEtStart_fermeLeSalon() {
        stubOwnedDeck("Val", HOST_DECK_ID, HOST_DECK_CARDS);
        stubOwnedDeck("Johnny", GUEST_DECK_ID, GUEST_DECK_CARDS);
        Room created = lobbyService.createRoom("Val", null, HOST_DECK_ID);
        // Le deck de l'invité a été supprimé entre-temps : 404 à la résolution.
        when(deckService.getDeck("Johnny", GUEST_DECK_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Deck introuvable : " + GUEST_DECK_ID));

        assertThatThrownBy(() -> lobbyService.joinRoom("Johnny", created.getCode(), GUEST_DECK_ID))
                .isInstanceOf(LobbyException.class)
                .extracting("code").isEqualTo("DECK_NOT_OWNED");
        // Le salon reste en place (l'invité n'a pas été assis).
        assertThat(lobbyService.findByCode(created.getCode())).isPresent();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void stubOwnedDeck(String username, long deckId, List<String> cards) {
        DeckResponse response = new DeckResponse(
                deckId,
                "Deck " + username,
                1L,
                cards,
                cards.size(),
                Instant.now(),
                Instant.now()
        );
        when(deckService.getDeck(username, deckId)).thenReturn(response);
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
