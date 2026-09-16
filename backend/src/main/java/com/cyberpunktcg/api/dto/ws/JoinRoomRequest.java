package com.cyberpunktcg.api.dto.ws;

/**
 * Requête {@code SEND /app/lobby.join}. Le pseudo vient de la session STOMP.
 *
 * @param roomCode code du salon (ex. {@code K7QP2M})
 * @param deckId   identifiant d'un deck sauvegardé par le joueur
 *                 ({@code GET /api/decks}). Obligatoire (Mini-Feature 9D) :
 *                 le serveur vérifie que le deck existe, appartient bien au
 *                 joueur et qu'il respecte les règles officielles ; les cartes
 *                 sont résolues au démarrage de la partie.
 */
public record JoinRoomRequest(
        String roomCode,
        Long deckId
) {
}
