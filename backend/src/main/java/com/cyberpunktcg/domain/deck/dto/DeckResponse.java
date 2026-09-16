package com.cyberpunktcg.domain.deck.dto;

import com.cyberpunktcg.domain.deck.Deck;

import java.time.Instant;
import java.util.List;

/**
 * Représentation REST d'un deck sauvegardé.
 *
 * <p>{@code cardIds} reprend exactement la liste persistée (ordre et exemplaires
 * compris) : le client peut la rejouer telle quelle dans le deck builder.</p>
 */
public record DeckResponse(
        Long id,
        String name,
        Long userId,
        List<String> cardIds,
        int totalCards,
        Instant createdAt,
        Instant updatedAt
) {

    public static DeckResponse from(Deck deck) {
        List<String> cardIds = deck.getCardIds();
        return new DeckResponse(
                deck.getId(),
                deck.getName(),
                deck.getUserId(),
                cardIds,
                cardIds.size(),
                deck.getCreatedAt(),
                deck.getUpdatedAt()
        );
    }
}
