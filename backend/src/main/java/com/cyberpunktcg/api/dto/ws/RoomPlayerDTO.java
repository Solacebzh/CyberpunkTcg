package com.cyberpunktcg.api.dto.ws;

/**
 * Un joueur assis dans un salon.
 *
 * @param pseudo pseudo du joueur (== playerId en partie)
 * @param seat   numéro de siège : 0 (hôte) ou 1
 * @param deckId identifiant du deck sauvegardé choisi par le joueur
 *               (clé vers {@code decks.id}, Mini-Feature 9D).
 *               {@code null} tant qu'aucun deck n'a été sélectionné.
 */
public record RoomPlayerDTO(
        String pseudo,
        int seat,
        Long deckId
) {
}
