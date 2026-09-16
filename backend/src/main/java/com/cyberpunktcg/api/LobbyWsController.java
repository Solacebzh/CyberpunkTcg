package com.cyberpunktcg.api;

import com.cyberpunktcg.api.dto.ws.CreateRoomRequest;
import com.cyberpunktcg.api.dto.ws.JoinRoomRequest;
import com.cyberpunktcg.api.dto.ws.LeaveRoomRequest;
import com.cyberpunktcg.api.dto.ws.WsError;
import com.cyberpunktcg.lobby.LobbyException;
import com.cyberpunktcg.lobby.LobbyService;
import com.cyberpunktcg.lobby.Room;
import com.cyberpunktcg.lobby.RoomStatus;
import com.cyberpunktcg.ws.GameBroadcaster;
import com.cyberpunktcg.ws.LobbyBroadcaster;
import com.cyberpunktcg.ws.WsDestinations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Endpoints STOMP du lobby (préfixe {@code /app}).
 * Toutes les identités proviennent du {@link Principal} posé par
 * {@code StompAuthInterceptor} lors du CONNECT, jamais des payloads.
 */
@Controller
public class LobbyWsController {

    private static final Logger log = LoggerFactory.getLogger(LobbyWsController.class);

    private final LobbyService lobbyService;
    private final LobbyBroadcaster lobbyBroadcaster;
    private final GameBroadcaster gameBroadcaster;

    public LobbyWsController(LobbyService lobbyService,
                             LobbyBroadcaster lobbyBroadcaster,
                             GameBroadcaster gameBroadcaster) {
        this.lobbyService = lobbyService;
        this.lobbyBroadcaster = lobbyBroadcaster;
        this.gameBroadcaster = gameBroadcaster;
    }

    /**
     * Crée un salon (hôte = siège 0). Réponse privée sur /user/queue/lobby.
     *
     * <p>Mini-Feature 9D : {@code request.deckId()} est désormais
     * <strong>obligatoire</strong>. Le backend vérifie que le deck existe,
     * appartient au joueur et reste valide avant d'autoriser la création.</p>
     */
    @MessageMapping("/lobby.create")
    public void createRoom(@Payload CreateRoomRequest request, StompHeaderAccessor accessor) {
        String pseudo = currentPseudo(accessor);
        if (pseudo == null) {
            return;
        }
        try {
            Long deckId = request == null ? null : request.deckId();
            Room room = lobbyService.createRoom(pseudo, request == null ? null : request.roomName(), deckId);
            lobbyBroadcaster.sendRoomTo(pseudo, room);
            lobbyBroadcaster.broadcastRoom(room);
            lobbyBroadcaster.broadcastRoomList();
        } catch (LobbyException error) {
            sendError(pseudo, error.getCode(), error.getMessage(), "/app/lobby.create", null);
        }
    }

    /**
     * Rejoint un salon ; démarre automatiquement la partie s'il était à 1 joueur.
     *
     * <p>Mini-Feature 9D : {@code request.deckId()} est obligatoire et doit
     * désigner un deck sauvegardé appartenant au joueur. Sans deck, l'accès
     * au salon est refusé (cf. {@link com.cyberpunktcg.lobby.LobbyService}
     * pour les codes d'erreur).</p>
     */
    @MessageMapping("/lobby.join")
    public void joinRoom(@Payload JoinRoomRequest request, StompHeaderAccessor accessor) {
        String pseudo = currentPseudo(accessor);
        if (pseudo == null) {
            return;
        }
        try {
            Long deckId = request == null ? null : request.deckId();
            Room room = lobbyService.joinRoom(pseudo, request == null ? null : request.roomCode(), deckId);
            lobbyBroadcaster.sendRoomTo(pseudo, room);
            lobbyBroadcaster.broadcastRoom(room);
            lobbyBroadcaster.broadcastRoomList();
            if (room.getStatus() == RoomStatus.PLAYING && room.getGameId() != null) {
                gameBroadcaster.gameStarted(room.getGameId());
            }
        } catch (LobbyException error) {
            sendError(pseudo, error.getCode(), error.getMessage(), "/app/lobby.join", null);
        }
    }

    /** Quitte un salon en attente (en partie, passer par l'action CONCEDE). */
    @MessageMapping("/lobby.leave")
    public void leaveRoom(@Payload(required = false) LeaveRoomRequest request, StompHeaderAccessor accessor) {
        String pseudo = currentPseudo(accessor);
        if (pseudo == null) {
            return;
        }
        String code = request == null ? null : request.roomCode();
        try {
            Room room = lobbyService.leaveRoom(pseudo, code)
                    .orElseThrow(() -> new LobbyException("ROOM_NOT_FOUND", "Aucun salon à quitter"));
            lobbyBroadcaster.sendRoomTo(pseudo, room);
            if (room.getStatus() == RoomStatus.WAITING) {
                lobbyBroadcaster.broadcastRoom(room);
            }
            lobbyBroadcaster.broadcastRoomList();
        } catch (LobbyException error) {
            sendError(pseudo, error.getCode(), error.getMessage(), "/app/lobby.leave", null);
        }
    }

    /** Liste publique des salons ouverts, réponse privée sur /user/queue/rooms. */
    @MessageMapping("/lobby.list")
    public void listRooms(StompHeaderAccessor accessor) {
        String pseudo = currentPseudo(accessor);
        if (pseudo == null) {
            return;
        }
        lobbyBroadcaster.sendRoomListTo(pseudo);
    }

    private String currentPseudo(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            log.warn("Message sans identité STOMP rejeté");
            return null;
        }
        return principal.getName();
    }

    private void sendError(String pseudo, String code, String message, String destination, String gameId) {
        gameBroadcaster.error(pseudo, WsError.of(code, message, destination, gameId, null));
    }
}
