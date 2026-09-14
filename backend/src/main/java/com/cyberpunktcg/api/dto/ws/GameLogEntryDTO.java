package com.cyberpunktcg.api.dto.ws;

import com.cyberpunktcg.domain.game.GameEvent;

/**
 * Une entrée du journal de partie, telle que vue par le client.
 * {@code index} correspond à l'ordre global dans la partie (0, 1, 2…),
 * pratique pour ne rejouer que les animations manquantes côté front.
 */
public record GameLogEntryDTO(
        int index,
        String type,
        String playerId,
        String description
) {
    public static GameLogEntryDTO from(int index, GameEvent event) {
        return new GameLogEntryDTO(
                index,
                event.getType().name(),
                event.getPlayerId(),
                event.getDescription());
    }
}
