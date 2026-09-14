package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.game.GameState;

/**
 * Décrit le tour courant : son numéro et le joueur actif.
 */
public record TurnDTO(
        int number,
        String activePlayerId
) {
    public static TurnDTO from(GameState state) {
        return new TurnDTO(state.getTurn().getNumber(), state.getActivePlayer().getId());
    }
}
