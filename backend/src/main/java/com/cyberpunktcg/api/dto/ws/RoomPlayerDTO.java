package com.cyberpunktcg.api.dto.ws;

/**
 * Un joueur assis dans un salon.
 *
 * @param pseudo       pseudo du joueur (== playerId en partie)
 * @param seat         numéro de siège : 0 (hôte) ou 1
 * @param deckCardCount nombre de cartes du deck choisi
 */
public record RoomPlayerDTO(
        String pseudo,
        int seat,
        int deckCardCount
) {
}
