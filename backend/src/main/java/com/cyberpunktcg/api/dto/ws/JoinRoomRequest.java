package com.cyberpunktcg.api.dto.ws;

import java.util.List;

/**
 * Requête {@code SEND /app/lobby.join}. Le pseudo vient de la session STOMP.
 *
 * @param roomCode    code du salon (ex. {@code K7QP2M})
 * @param deckCardIds deck optionnel ; absent → deck par défaut
 */
public record JoinRoomRequest(
        String roomCode,
        List<String> deckCardIds
) {
}
