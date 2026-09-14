package com.cyberpunktcg.api.dto.ws;

/**
 * Requête {@code SEND /app/lobby.leave}. Le code peut être omis : le serveur
 * retrouve alors le salon courant du pseudo.
 */
public record LeaveRoomRequest(
        String roomCode
) {
}
