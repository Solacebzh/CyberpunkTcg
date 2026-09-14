package com.cyberpunktcg.ws;

import com.cyberpunktcg.api.dto.ws.RoomDTO;
import com.cyberpunktcg.api.dto.ws.RoomListDTO;
import com.cyberpunktcg.lobby.LobbyService;
import com.cyberpunktcg.lobby.Room;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Diffusion des messages de lobby : état d'un salon, liste publique,
 * confirmations privées.
 */
@Component
public class LobbyBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final LobbyService lobbyService;

    public LobbyBroadcaster(SimpMessagingTemplate messagingTemplate, LobbyService lobbyService) {
        this.messagingTemplate = messagingTemplate;
        this.lobbyService = lobbyService;
    }

    /** Diffuse l'état du salon à tous ses abonnés. */
    public void broadcastRoom(Room room) {
        messagingTemplate.convertAndSend(WsDestinations.lobby(room.getCode()), RoomDTO.from(room));
    }

    /** Envoie en privé l'état d'un salon (confirmation create/join/leave). */
    public void sendRoomTo(String pseudo, Room room) {
        messagingTemplate.convertAndSendToUser(pseudo, WsDestinations.LOBBY_QUEUE, RoomDTO.from(room));
    }

    /** Diffuse la liste des salons ouverts sur le topic public. */
    public void broadcastRoomList() {
        messagingTemplate.convertAndSend(WsDestinations.ROOMS_TOPIC,
                RoomListDTO.from(lobbyService.listOpenRooms().stream().map(RoomDTO::from).toList()));
    }

    /** Réponse privée à {@code SEND /app/lobby.list}. */
    public void sendRoomListTo(String pseudo) {
        messagingTemplate.convertAndSendToUser(pseudo, WsDestinations.ROOMS_QUEUE,
                RoomListDTO.from(lobbyService.listOpenRooms().stream().map(RoomDTO::from).toList()));
    }
}
