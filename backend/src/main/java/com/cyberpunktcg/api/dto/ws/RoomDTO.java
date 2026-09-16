package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.lobby.Room;
import com.cyberpunktcg.lobby.RoomStatus;

import java.time.Instant;
import java.util.List;

/**
 * État public d'un salon, diffusé sur {@code /topic/lobby/{roomCode}} et
 * renvoyé en privé sur {@code /user/queue/lobby} après create/join/leave.
 *
 * @param type   toujours {@code "LOBBY_STATE"}
 * @param code   code à partager pour rejoindre
 * @param name   nom d'affichage
 * @param status WAITING (1 joueur, rejoignable) ou PLAYING (partie démarrée)
 * @param hostPseudo pseudo de l'hôte
 * @param players joueurs assis (dans l'ordre des sièges)
 * @param gameId identifiant de partie une fois {@code status=PLAYING}
 * @param createdAt création du salon
 */
public record RoomDTO(
        String type,
        String code,
        String name,
        RoomStatus status,
        String hostPseudo,
        List<RoomPlayerDTO> players,
        String gameId,
        Instant createdAt
) {
    public static RoomDTO from(Room room) {
        return new RoomDTO(
                "LOBBY_STATE",
                room.getCode(),
                room.getName(),
                room.getStatus(),
                room.getHostPseudo(),
                room.seatView().stream()
                        .map(seat -> new RoomPlayerDTO(seat.pseudo(), seat.seat(), seat.deckId()))
                        .toList(),
                room.getGameId(),
                room.getCreatedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }
}
