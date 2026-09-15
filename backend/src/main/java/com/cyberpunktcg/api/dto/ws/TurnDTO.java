package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.game.GameState;

/**
 * Décrit le tour courant : son numéro, le joueur actif et — pendant la phase
 * {@code DRAW} interactive (Mini-Feature 5) — la sous-étape attendue.
 *
 * @param number         numéro du tour (commence à 1)
 * @param activePlayerId joueur actif
 * @param drawStep       {@code DRAW_START}, {@code AWAITING_DRAW},
 *                       {@code AWAITING_DIE_SELECT}, {@code ROLLING_DIE},
 *                       {@code DRAW_COMPLETE} ; absent ({@code null}) hors phase DRAW
 */
public record TurnDTO(
        int number,
        String activePlayerId,
        String drawStep
) {
    public static TurnDTO from(GameState state) {
        return new TurnDTO(
                state.getTurn().getNumber(),
                state.getActivePlayer().getId(),
                state.getDrawStep() == null ? null : state.getDrawStep().name());
    }
}
