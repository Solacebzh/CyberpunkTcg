package com.cyberpunktcg.api.dto.ws;

import java.util.List;

/**
 * Liste des salons rejoignables. Diffusée sur {@code /topic/rooms} à chaque
 * changement et renvoyée en privé sur {@code /user/queue/rooms} après
 * {@code SEND /app/lobby.list}.
 */
public record RoomListDTO(
        String type,
        List<RoomSummaryDTO> rooms
) {
    public static RoomListDTO from(List<RoomDTO> rooms) {
        List<RoomSummaryDTO> summaries = rooms.stream()
                .filter(room -> room.status() == com.cyberpunktcg.lobby.RoomStatus.WAITING)
                .map(room -> new RoomSummaryDTO(room.code(), room.name(), room.hostPseudo(), room.players().size()))
                .toList();
        return new RoomListDTO("ROOMS", summaries);
    }
}
