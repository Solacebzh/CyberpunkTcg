package com.cyberpunktcg.api.dto.ws;

import java.util.List;

/**
 * Requête {@code SEND /app/lobby.create}.
 * Le pseudo n'est PAS lu ici : il provient du handshake STOMP (en-tête
 * {@code pseudo} du frame CONNECT), source de vérité de l'identité.
 *
 * @param roomName     nom optionnel affiché dans la liste des salons
 * @param deckCardIds  liste optionnelle d'identifiants de cartes du catalogue
 *                     (3 legends + 10 units recommandé). Absente → deck par défaut.
 */
public record CreateRoomRequest(
        String roomName,
        List<String> deckCardIds
) {
}
