package com.cyberpunktcg.api.dto.ws;

/**
 * Requête {@code SEND /app/lobby.create}.
 * Le pseudo n'est PAS lu ici : il provient du handshake STOMP (en-tête
 * {@code pseudo} du frame CONNECT), source de vérité de l'identité.
 *
 * @param roomName nom optionnel affiché dans la liste des salons
 * @param deckId   identifiant d'un deck sauvegardé par le joueur
 *                 ({@code GET /api/decks}). Obligatoire (Mini-Feature 9D) :
 *                 le serveur vérifie que le deck existe, appartient bien au
 *                 joueur et qu'il respecte les règles officielles ; les cartes
 *                 sont résolues au démarrage de la partie.
 */
public record CreateRoomRequest(
        String roomName,
        Long deckId
) {
}
