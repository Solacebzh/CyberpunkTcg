package com.cyberpunktcg.api.dto.ws;

/**
 * Résumé d'un salon pour la liste publique (pas de decks, pas d'état interne).
 */
public record RoomSummaryDTO(
        String code,
        String name,
        String hostPseudo,
        int playerCount
) {
}
