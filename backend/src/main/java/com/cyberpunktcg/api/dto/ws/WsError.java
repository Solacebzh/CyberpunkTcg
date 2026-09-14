package com.cyberpunktcg.api.dto.ws;

/**
 * Erreur privée envoyée sur {@code /user/queue/errors} au seul client fautif.
 * Aucune erreur de règle n'est jamais diffusée à l'adversaire.
 *
 * @param type            toujours {@code "ERROR"}
 * @param code            code machine stable (voir docs/WEBSOCKET-PROTOCOL.md)
 * @param message         message lisible (français)
 * @param destination     destination SEND qui a échoué (si connue)
 * @param gameId          partie concernée (si connue)
 * @param clientRequestId renvoyé tel quel si fourni dans la commande
 */
public record WsError(
        String type,
        String code,
        String message,
        String destination,
        String gameId,
        String clientRequestId
) {
    public static WsError of(String code, String message, String destination, String gameId, String clientRequestId) {
        return new WsError("ERROR", code, message, destination, gameId, clientRequestId);
    }
}
